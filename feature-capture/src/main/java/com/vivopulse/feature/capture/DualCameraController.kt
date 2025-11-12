package com.vivopulse.feature.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.vivopulse.feature.capture.model.Frame
import com.vivopulse.feature.capture.model.Source
import com.vivopulse.feature.capture.model.SessionStats
import com.vivopulse.feature.capture.roi.FaceRoi
import com.vivopulse.feature.capture.roi.FaceRoiTracker
import com.vivopulse.feature.capture.util.FpsTracker
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import android.graphics.Rect

/**
 * Controller for dual camera capture (front and back cameras simultaneously).
 * 
 * Manages concurrent camera access, frame streaming, and recording sessions.
 */
@ExperimentalCamera2Interop
class DualCameraController(
    private val context: Context
) {
    private val tag = "DualCameraController"
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var frontCamera: Camera? = null
    private var backCamera: Camera? = null
    
    private val executor: Executor = ContextCompat.getMainExecutor(context)
    
    // Frame streams
    private val _frontFrames = MutableSharedFlow<Frame>(
        replay = 0,
        extraBufferCapacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _backFrames = MutableSharedFlow<Frame>(
        replay = 0,
        extraBufferCapacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    val frontFrames: SharedFlow<Frame> = _frontFrames.asSharedFlow()
    val backFrames: SharedFlow<Frame> = _backFrames.asSharedFlow()
    
    // Merged frame stream
    val allFrames: Flow<Frame> = merge(frontFrames, backFrames)
    
    // FPS tracking
    private val frontFpsTracker = FpsTracker(Source.FACE)
    private val backFpsTracker = FpsTracker(Source.FINGER)
    
    // Timestamp tracking for drift monitoring
    private val _frontTimestamps = MutableSharedFlow<Long>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _backTimestamps = MutableSharedFlow<Long>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    val frontTimestamps: SharedFlow<Long> = _frontTimestamps.asSharedFlow()
    val backTimestamps: SharedFlow<Long> = _backTimestamps.asSharedFlow()
    
    // Face ROI tracking
    private val faceRoiTracker = FaceRoiTracker(detectionInterval = 5)
    val faceRoi: StateFlow<FaceRoi?> = faceRoiTracker.roiState
    
    // Status banner for UI (safe mode, errors)
    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()
    
    // Live luma streams for real-time waveform overlays
    private val _faceWave = MutableSharedFlow<Double>(
        replay = 0,
        extraBufferCapacity = 60,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val faceWave: SharedFlow<Double> = _faceWave.asSharedFlow()
    
    private val _fingerWave = MutableSharedFlow<Double>(
        replay = 0,
        extraBufferCapacity = 60,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val fingerWave: SharedFlow<Double> = _fingerWave.asSharedFlow()
    
    // Recording state
    private var isRecording = false
    private var recordingStartTime = 0L
    private val recordedFrames = mutableListOf<Frame>()
    private val maxRecordedFrames = 3600 // ~60s at 30fps per camera
    
    // Torch state
    private var torchEnabled = false
    
    /**
     * Check if device supports concurrent camera operation.
     */
    fun isConcurrentCameraSupported(): Boolean {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            // API 30+ has concurrent camera support check
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val concurrentCameraIds = cameraManager.concurrentCameraIds
                concurrentCameraIds.any { it.size >= 2 }
            } else {
                // Fallback: assume supported, will handle sequentially if needed
                true
            }
        } catch (e: Exception) {
            Log.w(tag, "Error checking concurrent camera support", e)
            true // Assume supported
        }
    }
    
    /**
     * Initialize camera provider.
     */
    suspend fun initialize() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        cameraProvider = providerFuture.get()
        Log.d(tag, "Camera provider initialized. Concurrent support: ${isConcurrentCameraSupported()}")
    }
    
    /**
     * Start dual camera preview and capture.
     */
    @SuppressLint("RestrictedApi")
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        frontPreviewView: PreviewView,
        backPreviewView: PreviewView
    ) {
        val provider = cameraProvider ?: run {
            Log.e(tag, "Camera provider not initialized")
            return
        }
        
        // Unbind all use cases before rebinding
        provider.unbindAll()
        
        try {
            val concurrentSupported = isConcurrentCameraSupported()
            if (!concurrentSupported) {
                _statusBanner.value = "Safe Mode: concurrent cameras not supported. Using sequential preview."
            } else {
                _statusBanner.value = null
            }
            
            // Front camera (FACE)
            val frontPreview = Preview.Builder()
                .setTargetResolution(Size(720, 1280))
                .build()
                .also {
                    it.setSurfaceProvider(frontPreviewView.surfaceProvider)
                }
            
            val frontImageAnalysis = createImageAnalysis(Source.FACE)
            
            frontCamera = try {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    frontPreview,
                    frontImageAnalysis
                )
            } catch (e: Exception) {
                Log.w(tag, "Front camera bind failed at 720p, retrying lower resolution", e)
                // Retry with a lower resolution
                val lowPreview = Preview.Builder()
                    .setTargetResolution(Size(640, 480))
                    .build()
                    .also { it.setSurfaceProvider(frontPreviewView.surfaceProvider) }
                val lowAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build().also { it.setAnalyzer(executor) { img -> processFrame(img, Source.FACE) } }
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    lowPreview,
                    lowAnalysis
                )
            }
            
            Log.d(tag, "Front camera bound successfully")
            
            // Back camera (FINGER) with torch support
            val backPreview = Preview.Builder()
                .setTargetResolution(Size(720, 1280))
                .build()
                .also {
                    it.setSurfaceProvider(backPreviewView.surfaceProvider)
                }
            
            val backImageAnalysis = createImageAnalysis(Source.FINGER)
            
            backCamera = try {
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    backPreview,
                    backImageAnalysis
                )
            } catch (e: Exception) {
                Log.w(tag, "Back camera bind failed at 720p, attempting fallback", e)
                if (!concurrentSupported) {
                    // Sequential fallback: unbind front and bind only back at lower res
                    provider.unbindAll()
                    val lowPreview = Preview.Builder()
                        .setTargetResolution(Size(640, 480))
                        .build()
                        .also { it.setSurfaceProvider(backPreviewView.surfaceProvider) }
                    val lowAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build().also { it.setAnalyzer(executor) { img -> processFrame(img, Source.FINGER) } }
                    _statusBanner.value = "Safe Mode: showing finger camera only (sequential)."
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        lowPreview,
                        lowAnalysis
                    )
                } else {
                    // Retry back camera at lower resolution while keeping front if present
                    val lowPreview = Preview.Builder()
                        .setTargetResolution(Size(640, 480))
                        .build()
                        .also { it.setSurfaceProvider(backPreviewView.surfaceProvider) }
                    val lowAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build().also { it.setAnalyzer(executor) { img -> processFrame(img, Source.FINGER) } }
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        lowPreview,
                        lowAnalysis
                    )
                }
            }
            
            Log.d(tag, "Back camera bound successfully")
            
            // Apply torch state if enabled
            if (torchEnabled) {
                backCamera?.cameraControl?.enableTorch(true)
            }
            
        } catch (e: Exception) {
            Log.e(tag, "Error starting cameras", e)
            _statusBanner.value = "Camera error. Tap to retry."
        }
    }
    
    /**
     * Create ImageAnalysis use case for frame capture.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun createImageAnalysis(source: Source): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setTargetResolution(Size(720, 1280))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(executor) { imageProxy ->
                    processFrame(imageProxy, source)
                }
            }
    }
    
    /**
     * Process incoming camera frame.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(imageProxy: ImageProxy, source: Source) {
        try {
            val image = imageProxy.image
            if (image == null) {
                imageProxy.close()
                return
            }
            
            val tracker = if (source == Source.FACE) frontFpsTracker else backFpsTracker
            tracker.onFrameReceived(image.timestamp)
            
            // Emit timestamps for drift monitoring
            if (source == Source.FACE) {
                _frontTimestamps.tryEmit(image.timestamp)
            } else {
                _backTimestamps.tryEmit(image.timestamp)
            }
            
            // Extract Y plane for processing
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer.duplicate() // Duplicate to preserve position
            val rowStride = yPlane.rowStride
            
            // Extract luma based on source
            var faceLuma: Double? = null
            var fingerLuma: Double? = null
            
            if (source == Source.FACE) {
                // Process face ROI
                try {
                    val yData = ByteArray(yBuffer.remaining())
                    yBuffer.get(yData)
                    
                    faceRoiTracker.processFrame(
                        yPlane = yData,
                        width = image.width,
                        height = image.height,
                        rotation = 0
                    )
                    
                    // Extract luma from ROI if available
                    val currentRoi = faceRoi.value
                    if (currentRoi != null && currentRoi.isValid()) {
                        yBuffer.rewind()
                        faceLuma = LumaExtractor.extractAverageLuma(
                            yBuffer,
                            currentRoi.rect,
                            rowStride,
                            image.width,
                            image.height
                        )
                        faceLuma?.let { _faceWave.tryEmit(it) }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error processing face ROI", e)
                }
            } else {
                // Extract luma from finger (center region)
                try {
                    fingerLuma = LumaExtractor.extractCenterRegionLuma(
                        yBuffer,
                        rowStride,
                        image.width,
                        image.height
                    )
                    fingerLuma?.let { _fingerWave.tryEmit(it) }
                } catch (e: Exception) {
                    Log.e(tag, "Error extracting finger luma", e)
                }
            }
            
            // Do not store full YUV planes to avoid high memory usage; luma metrics suffice for processing
            val planes = emptyList<ByteBuffer>()
            
            val frame = Frame(
                source = source,
                timestampNs = image.timestamp,
                width = image.width,
                height = image.height,
                yuvPlanes = planes,
                faceLuma = faceLuma,
                fingerLuma = fingerLuma
            )
            
            // Emit frame to flow
            val flowEmitted = if (source == Source.FACE) {
                _frontFrames.tryEmit(frame)
            } else {
                _backFrames.tryEmit(frame)
            }
            
            if (!flowEmitted) {
                tracker.onFrameDropped()
                Log.w(tag, "Frame dropped for ${source.name}, buffer full")
            }
            
            // Store frame if recording
            if (isRecording && recordedFrames.size < maxRecordedFrames) {
                synchronized(recordedFrames) {
                    recordedFrames.add(frame.deepCopy())
                }
            }
            
        } catch (e: Exception) {
            Log.e(tag, "Error processing frame from ${source.name}", e)
        } finally {
            imageProxy.close()
        }
    }
    
    /**
     * Start recording session.
     */
    fun startRecording() {
        if (isRecording) {
            Log.w(tag, "Recording already in progress")
            return
        }
        
        synchronized(recordedFrames) {
            recordedFrames.clear()
        }
        frontFpsTracker.reset()
        backFpsTracker.reset()
        
        recordingStartTime = System.currentTimeMillis()
        isRecording = true
        
        Log.d(tag, "Recording started")
    }
    
    /**
     * Stop recording session and return captured frames.
     */
    fun stopRecording(): RecordingResult {
        if (!isRecording) {
            Log.w(tag, "No recording in progress")
            return RecordingResult(emptyList(), SessionStats())
        }
        
        isRecording = false
        val durationMs = System.currentTimeMillis() - recordingStartTime
        
        val frames = synchronized(recordedFrames) {
            recordedFrames.toList()
        }
        
        val (frontReceived, frontDropped, frontFps) = frontFpsTracker.getStats()
        val (backReceived, backDropped, backFps) = backFpsTracker.getStats()
        
        val stats = SessionStats(
            durationMs = durationMs,
            faceStats = com.vivopulse.feature.capture.model.CameraStats(
                source = Source.FACE,
                framesReceived = frontReceived,
                framesDropped = frontDropped,
                averageFps = frontFps
            ),
            fingerStats = com.vivopulse.feature.capture.model.CameraStats(
                source = Source.FINGER,
                framesReceived = backReceived,
                framesDropped = backDropped,
                averageFps = backFps
            )
        )
        
        Log.d(tag, "Recording stopped: ${frames.size} frames captured, duration: ${durationMs}ms")
        Log.d(tag, "Face camera: $frontReceived frames, $frontDropped dropped, ${String.format("%.1f", frontFps)} fps")
        Log.d(tag, "Finger camera: $backReceived frames, $backDropped dropped, ${String.format("%.1f", backFps)} fps")
        
        return RecordingResult(frames, stats)
    }
    
    /**
     * Toggle torch (flashlight) for back camera.
     */
    fun setTorchEnabled(enabled: Boolean) {
        torchEnabled = enabled
        backCamera?.cameraControl?.enableTorch(enabled)
        Log.d(tag, "Torch ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Get current recording state.
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * Get current FPS for both cameras.
     */
    fun getCurrentFps(): Pair<Float, Float> {
        return Pair(frontFpsTracker.getCurrentFps(), backFpsTracker.getCurrentFps())
    }
    
    /**
     * Release camera resources.
     */
    fun release() {
        cameraProvider?.unbindAll()
        frontCamera = null
        backCamera = null
        torchEnabled = false
        isRecording = false
        synchronized(recordedFrames) {
            recordedFrames.clear()
        }
        faceRoiTracker.release()
        Log.d(tag, "Camera resources released")
    }
}

/**
 * Result of a recording session.
 */
data class RecordingResult(
    val frames: List<Frame>,
    val stats: SessionStats
)

