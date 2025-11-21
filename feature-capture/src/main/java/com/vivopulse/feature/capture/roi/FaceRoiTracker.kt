package com.vivopulse.feature.capture.roi

import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Tracks face ROI for rPPG signal extraction.
 * 
 * Features:
 * - ML Kit face detection
 * - Forehead ROI computation from landmarks
 * - Simple optical flow tracking (Lucas-Kanade approximation)
 * - Stable ROI with jitter reduction
 */
class FaceRoiTracker(
    private val detectionInterval: Int = 5 // Detect face every N frames
) {
    private val tag = "FaceRoiTracker"
    
    private val detector: FaceDetector
    
    private var frameCount = 0
    private var lastDetectedFace: Face? = null
    private var currentRoi: Rect? = null
    private var lastFrameData: ByteArray? = null
    private var lastFrameWidth = 0
    private var lastFrameHeight = 0
    
    private val _roiState = MutableStateFlow<FaceRoi?>(null)
    val roiState: StateFlow<FaceRoi?> = _roiState.asStateFlow()
    
    private var consecutiveLostFrames = 0
    private val maxLostFrames = 15 // Lost after 15 frames (~0.5s at 30fps)
    
    // ROI smoothing with exponential moving average
    private var smoothedRoi: Rect? = null
    private val smoothingAlpha = 0.3f // Lower = more smoothing
    
    init {
        // Configure ML Kit Face Detector
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f) // Minimum face size (15% of frame)
            .enableTracking() // Enable face tracking for better performance
            .build()
        
        detector = FaceDetection.getClient(options)
    }
    
    /**
     * Process a frame and update ROI.
     * 
     * @param yPlane Y plane from YUV_420_888
     * @param width Frame width
     * @param height Frame height
     * @param rotation Image rotation
     */
    fun processFrame(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rotation: Int = 0
    ) {
        frameCount++
        
        // Detect face periodically or if lost
        if (frameCount % detectionInterval == 0 || currentRoi == null) {
            detectFace(yPlane, width, height, rotation)
        } else {
            // Track existing ROI using optical flow approximation
            trackRoi(yPlane, width, height)
        }
        
        // Update state
        updateRoiState()
    }
    
    /**
     * Detect face using ML Kit.
     */
    private fun detectFace(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rotation: Int
    ) {
        try {
            // Convert Y plane to InputImage for ML Kit (copy to avoid buffer reuse issues)
            val detectionBytes = yPlane.copyOf()
            val buffer = ByteBuffer.wrap(detectionBytes)
            val image = InputImage.fromByteBuffer(
                buffer,
                width,
                height,
                rotation,
                InputImage.IMAGE_FORMAT_NV21 // ML Kit expects NV21 for single plane
            )
            
            detector.process(image)
                .addOnSuccessListener { faces ->
                    handleDetectionResult(faces, width, height)
                }
                .addOnFailureListener { e ->
                    Log.e(tag, "Face detection failed", e)
                    handleLostFace()
                }
            
            storeLastFrame(yPlane, width, height)
            
        } catch (e: Exception) {
            Log.e(tag, "Error processing frame", e)
            handleLostFace()
        }
    }
    
    /**
     * Handle face detection results.
     */
    private fun handleDetectionResult(faces: List<Face>, width: Int, height: Int) {
        if (faces.isEmpty()) {
            handleLostFace()
            return
        }
        
        // Use first (largest) detected face
        val face = faces.first()
        lastDetectedFace = face
        
        // Compute forehead ROI from landmarks
        val foreheadRoi = computeForeheadRoi(face, width, height)
        
        if (foreheadRoi != null) {
            // Apply smoothing to reduce jitter
            currentRoi = if (smoothedRoi == null) {
                smoothedRoi = foreheadRoi
                foreheadRoi
            } else {
                smoothRoi(smoothedRoi!!, foreheadRoi)
            }
            
            consecutiveLostFrames = 0
            Log.d(tag, "Face detected, ROI: $currentRoi")
        } else {
            handleLostFace()
        }
    }
    
    /**
     * Compute forehead ROI from face landmarks.
     * 
     * ROI is positioned above the eyes, centered on the face.
     * Size is 15-25% of face width.
     */
    private fun computeForeheadRoi(face: Face, frameWidth: Int, frameHeight: Int): Rect? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)
        
        // Need both eyes for reliable ROI
        if (leftEye == null || rightEye == null) {
            Log.w(tag, "Missing eye landmarks, using fallback")
            return computeFallbackRoi(face, frameWidth, frameHeight)
        }
        
        val leftEyePos = leftEye.position
        val rightEyePos = rightEye.position
        
        // Eye center and distance
        val eyeCenterX = (leftEyePos.x + rightEyePos.x) / 2f
        val eyeCenterY = (leftEyePos.y + rightEyePos.y) / 2f
        val eyeDistance = kotlin.math.abs(rightEyePos.x - leftEyePos.x)
        
        // ROI dimensions (20% of face width)
        val roiWidth = (eyeDistance * 0.8f).toInt() // Slightly narrower than eye distance
        val roiHeight = (roiWidth * 0.6f).toInt() // Rectangular, not square
        
        // Position: Above eyes, centered
        val roiCenterX = eyeCenterX.toInt()
        val roiCenterY = (eyeCenterY - eyeDistance * 0.4f).toInt() // Above eyes
        
        val roi = Rect(
            roiCenterX - roiWidth / 2,
            roiCenterY - roiHeight / 2,
            roiCenterX + roiWidth / 2,
            roiCenterY + roiHeight / 2
        )
        
        // Ensure ROI is within frame bounds
        return constrainRoiToFrame(roi, frameWidth, frameHeight)
    }
    
    /**
     * Fallback ROI when landmarks are missing.
     * Uses face bounding box.
     */
    private fun computeFallbackRoi(face: Face, frameWidth: Int, frameHeight: Int): Rect? {
        val faceBounds = face.boundingBox
        
        // ROI in upper portion of face
        val roiWidth = (faceBounds.width() * 0.6f).toInt()
        val roiHeight = (roiWidth * 0.6f).toInt()
        
        val roiCenterX = faceBounds.centerX()
        val roiCenterY = (faceBounds.top + faceBounds.height() * 0.25f).toInt()
        
        val roi = Rect(
            roiCenterX - roiWidth / 2,
            roiCenterY - roiHeight / 2,
            roiCenterX + roiWidth / 2,
            roiCenterY + roiHeight / 2
        )
        
        return constrainRoiToFrame(roi, frameWidth, frameHeight)
    }
    
    /**
     * Constrain ROI to frame boundaries.
     */
    private fun constrainRoiToFrame(roi: Rect, frameWidth: Int, frameHeight: Int): Rect {
        return Rect(
            max(0, roi.left),
            max(0, roi.top),
            min(frameWidth, roi.right),
            min(frameHeight, roi.bottom)
        )
    }
    
    /**
     * Track ROI using simple optical flow approximation.
     * 
     * This is a simplified Lucas-Kanade approximation:
     * - Compare average brightness in ROI between frames
     * - Apply small corrections based on gradient
     */
    private fun trackRoi(yPlane: ByteArray, width: Int, height: Int) {
        val roi = currentRoi ?: return
        val lastFrame = lastFrameData ?: return
        
        if (width != lastFrameWidth || height != lastFrameHeight) {
            // Frame size changed, need re-detection
            currentRoi = null
            return
        }
        
        try {
            // Simple motion estimation: search small neighborhood
            val searchRadius = 5 // pixels
            var bestOffset = Pair(0, 0)
            var bestScore = Double.MAX_VALUE
            
            for (dy in -searchRadius..searchRadius step 2) {
                for (dx in -searchRadius..searchRadius step 2) {
                    val shiftedRoi = Rect(
                        roi.left + dx,
                        roi.top + dy,
                        roi.right + dx,
                        roi.bottom + dy
                    )
                    
                    // Skip if out of bounds
                    if (shiftedRoi.left < 0 || shiftedRoi.top < 0 ||
                        shiftedRoi.right >= width || shiftedRoi.bottom >= height) {
                        continue
                    }
                    
                    // Compute SSD (Sum of Squared Differences)
                    val score = computeSSD(lastFrame, yPlane, roi, shiftedRoi, width)
                    
                    if (score < bestScore) {
                        bestScore = score
                        bestOffset = Pair(dx, dy)
                    }
                }
            }
            
            // Apply best offset with smoothing
            if (bestScore < Double.MAX_VALUE) {
                val trackedRoi = Rect(
                    roi.left + bestOffset.first,
                    roi.top + bestOffset.second,
                    roi.right + bestOffset.first,
                    roi.bottom + bestOffset.second
                )
                
                currentRoi = smoothRoi(roi, trackedRoi)
                consecutiveLostFrames = 0
            } else {
                consecutiveLostFrames++
            }
            
            // Store current frame for next tracking
            storeLastFrame(yPlane, width, height)
            
        } catch (e: Exception) {
            Log.e(tag, "Error tracking ROI", e)
            consecutiveLostFrames++
        }
    }
    
    /**
     * Compute Sum of Squared Differences between two ROIs.
     */
    private fun computeSSD(
        frame1: ByteArray,
        frame2: ByteArray,
        roi1: Rect,
        roi2: Rect,
        width: Int
    ): Double {
        var ssd = 0.0
        var count = 0
        
        val height1 = roi1.height()
        val height2 = roi2.height()
        val minHeight = min(height1, height2)
        
        for (y in 0 until minHeight step 2) { // Sample every other pixel
            for (x in 0 until roi1.width() step 2) {
                val idx1 = (roi1.top + y) * width + (roi1.left + x)
                val idx2 = (roi2.top + y) * width + (roi2.left + x)
                
                if (idx1 >= 0 && idx1 < frame1.size && idx2 >= 0 && idx2 < frame2.size) {
                    val diff = (frame1[idx1].toInt() and 0xFF) - (frame2[idx2].toInt() and 0xFF)
                    ssd += diff * diff
                    count++
                }
            }
        }
        
        return if (count > 0) ssd / count else Double.MAX_VALUE
    }
    
    /**
     * Smooth ROI using exponential moving average.
     */
    private fun smoothRoi(oldRoi: Rect, newRoi: Rect): Rect {
        val alpha = smoothingAlpha
        
        return Rect(
            (oldRoi.left * (1 - alpha) + newRoi.left * alpha).toInt(),
            (oldRoi.top * (1 - alpha) + newRoi.top * alpha).toInt(),
            (oldRoi.right * (1 - alpha) + newRoi.right * alpha).toInt(),
            (oldRoi.bottom * (1 - alpha) + newRoi.bottom * alpha).toInt()
        )
    }
    
    /**
     * Handle lost face.
     */
    private fun handleLostFace() {
        consecutiveLostFrames++
        
        if (consecutiveLostFrames > maxLostFrames) {
            currentRoi = null
            smoothedRoi = null
            lastDetectedFace = null
        }
    }
    
    /**
     * Update ROI state for UI.
     */
    private fun updateRoiState() {
        val roi = currentRoi
        
        val state = when {
            roi == null -> RoiState.LOST
            consecutiveLostFrames > 0 -> RoiState.RE_ACQUIRING
            else -> RoiState.STABLE
        }
        
        val faceRoi = if (roi != null) {
            FaceRoi(
                rect = roi,
                state = state,
                confidence = 1.0f - (consecutiveLostFrames.toFloat() / maxLostFrames)
            )
        } else {
            null
        }
        
        _roiState.value = faceRoi
    }

    private fun storeLastFrame(data: ByteArray, width: Int, height: Int) {
        if (lastFrameData == null || lastFrameData!!.size != data.size) {
            lastFrameData = ByteArray(data.size)
        }
        System.arraycopy(data, 0, lastFrameData!!, 0, data.size)
        lastFrameWidth = width
        lastFrameHeight = height
    }
    
    /**
     * Reset tracker.
     */
    fun reset() {
        frameCount = 0
        currentRoi = null
        smoothedRoi = null
        lastDetectedFace = null
        lastFrameData = null
        consecutiveLostFrames = 0
        _roiState.value = null
    }
    
    /**
     * Release resources.
     */
    fun release() {
        detector.close()
        reset()
    }
}

