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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.vivopulse.feature.capture.analysis.SafeImageAnalyzer
import com.vivopulse.feature.capture.camera.SequentialPrimary
import com.vivopulse.feature.capture.model.Frame
import com.vivopulse.feature.capture.model.Source
import com.vivopulse.feature.capture.model.SessionStats
import com.vivopulse.feature.capture.roi.FaceRoi
import com.vivopulse.feature.capture.roi.FaceRoiTracker
import com.vivopulse.feature.capture.util.FpsTracker
import com.vivopulse.feature.capture.util.BufferPool
import com.vivopulse.signal.SignalSample
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt
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
    
    // Use a background executor for image analysis to avoid blocking the main thread
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VivoPulseAnalyzer").apply { priority = Thread.NORM_PRIORITY }
    }
    
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
    
    // Lightweight signal samples for real-time quality analysis
    private val _signalSamples = MutableSharedFlow<SignalSample>(
        replay = 0,
        extraBufferCapacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val signalSamples: SharedFlow<SignalSample> = _signalSamples.asSharedFlow()
    
    // Device capabilities and camera mode
    private val deviceProbe = com.vivopulse.feature.capture.camera.DeviceProbe(context)
    private var deviceCapabilities: com.vivopulse.feature.capture.camera.DeviceCapabilities? = null
    private val _cameraMode = MutableStateFlow<com.vivopulse.feature.capture.camera.CameraMode>(
        com.vivopulse.feature.capture.camera.CameraMode.CONCURRENT
    )
    val cameraMode: StateFlow<com.vivopulse.feature.capture.camera.CameraMode> = _cameraMode.asStateFlow()
    
    private val _sequentialPrimary = MutableStateFlow(SequentialPrimary.FINGER)
    val sequentialPrimary: StateFlow<SequentialPrimary> = _sequentialPrimary.asStateFlow()
    
    // Retry state for session failures
    private var retryCount = 0
    private val maxRetries = 3
    private var currentResolutionIndex = 0
    private val resolutionFallbacks = listOf(
        Size(720, 1280),
        Size(640, 480),
        Size(480, 640)
    )
    
    // Recording state
    private var isRecording = false
    private var recordingStartTime = 0L
    private val recordedFrames = mutableListOf<Frame>()
    private val maxRecordedFrames = 3600 // ~60s at 30fps per camera
    private var faceFrameBuffer: ByteArray? = null
    private var facePatchBuffer: ByteArray? = null
    private var previousFacePatch: ByteArray? = null
    private var fingerRoiRect: Rect? = null
    private var fingerFrameCounter = 0
    
    // Buffer pool for memory efficiency (eliminates per-frame allocations)
    private val yPlaneBufferPool = BufferPool(
        bufferSize = 720 * 1280, // Max expected Y plane size
        poolSize = 10
    )
    
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

    private var thermalListener: android.os.PowerManager.OnThermalStatusChangedListener? = null

    @SuppressLint("NewApi") // Guarded by SDK check
    private fun setupThermalMonitoring() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            thermalListener = android.os.PowerManager.OnThermalStatusChangedListener { status ->
                when (status) {
                    android.os.PowerManager.THERMAL_STATUS_MODERATE -> {
                        Log.w(tag, "Thermal status: MODERATE. Reducing processing.")
                        _statusBanner.value = "Device is warm. Adjusting performance."
                    }
                    android.os.PowerManager.THERMAL_STATUS_SEVERE,
                    android.os.PowerManager.THERMAL_STATUS_CRITICAL,
                    android.os.PowerManager.THERMAL_STATUS_EMERGENCY,
                    android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                        Log.e(tag, "Thermal status: CRITICAL ($status). Disabling torch and stopping recording.")
                        _statusBanner.value = "Device overheating! Stopping capture for safety."
                        if (torchEnabled) {
                            setTorchEnabled(false)
                        }
                        if (isRecording) {
                            stopRecording()
                        }
                    }
                    else -> {
                        // Normal or Light status, clear warning if it was thermal-related
                        if (_statusBanner.value?.contains("Device") == true) {
                            _statusBanner.value = null
                        }
                    }
                }
            }
            powerManager.addThermalStatusListener(analyzerExecutor, thermalListener!!)
        }
    }
    
    /**
     * Initialize camera provider and probe device capabilities.
     */
    suspend fun initialize() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        cameraProvider = providerFuture.get()
        
        // Probe device capabilities
        deviceCapabilities = deviceProbe.probe()
        _cameraMode.value = deviceCapabilities?.recommendedMode 
            ?: com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_SEQUENTIAL
        
        setupThermalMonitoring()
        
        Log.d(tag, "Camera provider initialized")
        Log.d(tag, "Device: ${deviceCapabilities?.deviceInfo}")
        Log.d(tag, "Concurrent support: ${deviceCapabilities?.hasConcurrentSupport}")
        Log.d(tag, "Recommended mode: ${_cameraMode.value}")
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
            _statusBanner.value = "Camera initialization failed. Restart app."
            return
        }
        
        Log.d(tag, "Starting camera with mode: ${_cameraMode.value}, sequential primary: $sequentialPrimary")
        
        // Unbind all use cases before rebinding
        provider.unbindAll()
        fingerFrameCounter = 0
        fingerRoiRect = null
        
        try {
            // Update status banner based on camera mode
            when (_cameraMode.value) {
                com.vivopulse.feature.capture.camera.CameraMode.CONCURRENT -> {
                    _statusBanner.value = null // Clear banner for normal operation
                }
                com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_SEQUENTIAL -> {
                    _statusBanner.value = "Safe Mode: Sequential camera operation"
                }
                com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_REDUCED -> {
                    _statusBanner.value = "Safe Mode: Reduced resolution"
                }
            }
            
            // Attempt to start cameras with current configuration
            startCamerasWithFallback(lifecycleOwner, frontPreviewView, backPreviewView, provider)
            
        } catch (e: Exception) {
            Log.e(tag, "Error starting cameras", e)
            handleCameraStartFailure(lifecycleOwner, frontPreviewView, backPreviewView)
        }
    }
    
    
    /**
     * Start cameras with progressive fallback logic.
     */
    @SuppressLint("RestrictedApi")
    private fun startCamerasWithFallback(
        lifecycleOwner: LifecycleOwner,
        frontPreviewView: PreviewView,
        backPreviewView: PreviewView,
        provider: ProcessCameraProvider
    ) {
        val bindingHelper = com.vivopulse.feature.capture.camera.CameraBindingHelper(
            tag = tag,
            executor = analyzerExecutor,
            processFrame = ::processFrame
        )
        
        val result = bindingHelper.bindCamerasWithFallback(
            provider = provider,
            lifecycleOwner = lifecycleOwner,
            frontPreviewView = frontPreviewView,
            backPreviewView = backPreviewView,
            currentMode = _cameraMode.value,
            sequentialPrimary = _sequentialPrimary.value,
            resolutionIndex = currentResolutionIndex
        )
        
        if (result != null) {
            frontCamera = result.first
            backCamera = result.second
            
            // Apply torch if enabled
            if (torchEnabled && backCamera != null) {
                backCamera?.cameraControl?.enableTorch(true)
            }
            
            Log.d(tag, "Cameras started successfully in mode ${_cameraMode.value}")
        } else {
            // Binding failed, trigger fallback handling
            throw Exception("Camera binding failed at resolution index $currentResolutionIndex")
        }
    }
    
    /**
     * Handle camera start failure with progressive fallback.
     */
    @SuppressLint("RestrictedApi")
    private fun handleCameraStartFailure(
        lifecycleOwner: LifecycleOwner,
        frontPreviewView: PreviewView,
        backPreviewView: PreviewView
    ) {
        retryCount++
        
        if (retryCount > maxRetries) {
            _statusBanner.value = "Camera error. Please restart the app."
            Log.e(tag, "Max retries exceeded, giving up")
            return
        }
        
        // Progressive fallback strategy
        when {
            // Try next resolution in current mode
            currentResolutionIndex < resolutionFallbacks.size - 1 -> {
                currentResolutionIndex++
                _cameraMode.value = com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_REDUCED
                Log.w(tag, "Retrying with reduced resolution (index $currentResolutionIndex)")
            }
            // Switch to sequential mode
            _cameraMode.value != com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_SEQUENTIAL -> {
                _cameraMode.value = com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_SEQUENTIAL
                currentResolutionIndex = 0 // Reset resolution
                Log.w(tag, "Switching to sequential mode")
            }
            // Already in sequential mode, try next resolution
            else -> {
                currentResolutionIndex++
                if (currentResolutionIndex >= resolutionFallbacks.size) {
                    _statusBanner.value = "Camera initialization failed. Please restart."
                    return
                }
                Log.w(tag, "Sequential mode: trying resolution index $currentResolutionIndex")
            }
        }
        
        // Retry with new configuration
        try {
            startCamera(lifecycleOwner, frontPreviewView, backPreviewView)
        } catch (e: Exception) {
            Log.e(tag, "Retry failed", e)
            _statusBanner.value = "Camera error. Tap to retry."
        }
    }
    
    /**
     * Create ImageAnalysis use case for frame capture.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("UnsafeOptInUsageError")
    private fun createImageAnalysis(source: Source): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setTargetResolution(Size(720, 1280))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analyzerExecutor, SafeImageAnalyzer { imageProxy ->
                    processFrame(imageProxy, source)
                })
            }
    }
    
    /**
     * Process incoming camera frame.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(imageProxy: ImageProxy, source: Source) {
        try {
            val image = imageProxy.image ?: return

            val tracker = if (source == Source.FACE) frontFpsTracker else backFpsTracker
            tracker.onFrameReceived(image.timestamp)

            if (source == Source.FACE) {
                _frontTimestamps.tryEmit(image.timestamp)
            } else {
                _backTimestamps.tryEmit(image.timestamp)
            }

            val yPlane = image.planes[0]
            val rowStride = yPlane.rowStride

            var faceLuma: Double? = null
            var fingerLuma: Double? = null
            var faceMotionRms: Double? = null
            var fingerSaturationPct: Double? = null

            if (source == Source.FACE) {
                try {
                    val planeCopy = yPlane.buffer.duplicate().apply { position(0) }
                    val planeSize = planeCopy.remaining()
                    val frameBytes = yPlaneBufferPool.acquire()
                    planeCopy.get(frameBytes, 0, minOf(planeSize, frameBytes.size))

                    faceRoiTracker.processFrame(
                        yPlane = frameBytes,
                        width = image.width,
                        height = image.height,
                        rotation = 0
                    )

                    val currentRoi = faceRoi.value?.rect
                    if (currentRoi != null && !currentRoi.isEmpty) {
                        val roiBuffer = yPlane.buffer.duplicate().apply { position(0) }
                        faceLuma = LumaExtractor.extractAverageLuma(
                            roiBuffer,
                            currentRoi,
                            rowStride,
                            image.width,
                            image.height
                        )
                        val motionBuffer = yPlane.buffer.duplicate().apply { position(0) }
                        faceMotionRms = computeFaceMotionRms(motionBuffer, rowStride, currentRoi)
                        faceLuma.let { _faceWave.tryEmit(it) }
                    }
                    
                    // Return buffer to pool
                    yPlaneBufferPool.release(frameBytes)
                } catch (e: Exception) {
                    Log.e(tag, "Error processing face channel", e)
                }
            } else {
                try {
                    fingerFrameCounter++
                    val roi = if (fingerRoiRect == null || fingerFrameCounter % 30 == 0) {
                        val roiBuffer = yPlane.buffer.duplicate().apply { position(0) }
                        com.vivopulse.feature.capture.roi.FingerRoiDetector.detectOptimalFingerRoi(
                            roiBuffer,
                            rowStride,
                            image.width,
                            image.height
                        ).also { fingerRoiRect = it }
                    } else {
                        fingerRoiRect!!
                    }

                    val lumaBuffer = yPlane.buffer.duplicate().apply { position(0) }
                    fingerLuma = LumaExtractor.extractAverageLuma(
                        lumaBuffer,
                        roi,
                        rowStride,
                        image.width,
                        image.height
                    )

                    val satBuffer = yPlane.buffer.duplicate().apply { position(0) }
                    fingerSaturationPct = computeSaturationPct(satBuffer, roi, rowStride)

                    fingerLuma.let { _fingerWave.tryEmit(it) }
                } catch (e: Exception) {
                    Log.e(tag, "Error processing finger channel", e)
                }
            }

            val frame = Frame(
                source = source,
                timestampNs = image.timestamp,
                width = image.width,
                height = image.height,
                yuvPlanes = emptyList(),
                faceLuma = faceLuma,
                fingerLuma = fingerLuma
            )

            val flowEmitted = if (source == Source.FACE) {
                _frontFrames.tryEmit(frame)
            } else {
                _backFrames.tryEmit(frame)
            }

            if (!flowEmitted) {
                tracker.onFrameDropped()
                Log.w(tag, "Frame dropped for ${source.name}, buffer full")
            }

            if (isRecording && recordedFrames.size < maxRecordedFrames) {
                synchronized(recordedFrames) {
                    recordedFrames.add(frame.deepCopy())
                    if (recordedFrames.size % 30 == 0) {
                        Log.d(tag, "Recording: ${recordedFrames.size} frames captured")
                    }
                }
            }

            _signalSamples.tryEmit(
                SignalSample(
                    timestampNs = image.timestamp,
                    faceMeanLuma = faceLuma,
                    fingerMeanLuma = fingerLuma,
                    faceMotionRmsPx = faceMotionRms,
                    fingerSaturationPct = fingerSaturationPct,
                    torchEnabled = torchEnabled
                )
            )
        } catch (e: Exception) {
            Log.e(tag, "Error processing frame from ${source.name}", e)
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
     * Configure which channel should run first when only one camera can operate.
     */
    fun setSequentialPrimary(primary: SequentialPrimary) {
        if (_sequentialPrimary.value == primary) return
        _sequentialPrimary.value = primary
        if (primary == SequentialPrimary.FACE && torchEnabled) {
            setTorchEnabled(false)
        }
        if (_cameraMode.value == com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_SEQUENTIAL) {
            _statusBanner.value = if (primary == SequentialPrimary.FACE) {
                "Sequential mode: capturing face first"
            } else {
                "Sequential mode: capturing finger first"
            }
        }
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
    
    private fun ensureFaceBuffer(requiredSize: Int): ByteArray {
        val existing = faceFrameBuffer
        if (existing == null || existing.size < requiredSize) {
            faceFrameBuffer = ByteArray(requiredSize)
        }
        return faceFrameBuffer!!
    }
    
    private fun computeFaceMotionRms(
        buffer: ByteBuffer,
        rowStride: Int,
        roi: Rect,
        step: Int = 4
    ): Double? {
        if (roi.isEmpty) return null
        val width = roi.width().coerceAtLeast(1)
        val height = roi.height().coerceAtLeast(1)
        val sampledWidth = (width / step).coerceAtLeast(1)
        val sampledHeight = (height / step).coerceAtLeast(1)
        val sampleCount = sampledWidth * sampledHeight
        
        if (sampleCount <= 0) return null
        if (facePatchBuffer == null || facePatchBuffer!!.size < sampleCount) {
            facePatchBuffer = ByteArray(sampleCount)
        }
        
        val currentPatch = facePatchBuffer!!
        var index = 0
        var y = roi.top
        while (y < roi.bottom) {
            val base = y * rowStride
            var x = roi.left
            while (x < roi.right) {
                val value = buffer.get(base + x).toInt() and 0xFF
                currentPatch[index++] = value.toByte()
                x += step
            }
            y += step
        }
        
        val previous = previousFacePatch
        val motion = if (previous != null && previous.size == index) {
            var sum = 0.0
            for (i in 0 until index) {
                val diff = (currentPatch[i].toInt() and 0xFF) - (previous[i].toInt() and 0xFF)
                sum += diff * diff
            }
            kotlin.math.sqrt(sum / index) / 10.0
        } else null
        
        if (previous == null || previous.size != index) {
            previousFacePatch = ByteArray(index)
        }
        System.arraycopy(currentPatch, 0, previousFacePatch!!, 0, index)
        return motion
    }
    
    private fun computeSaturationPct(
        buffer: ByteBuffer,
        roi: Rect,
        rowStride: Int,
        threshold: Int = 250,
        sampleStep: Int = 2
    ): Double {
        if (roi.isEmpty) return 0.0
        var saturated = 0
        var total = 0
        
        var y = roi.top
        while (y < roi.bottom) {
            val base = y * rowStride
            var x = roi.left
            while (x < roi.right) {
                val value = buffer.get(base + x).toInt() and 0xFF
                if (value >= threshold) {
                    saturated++
                }
                total++
                x += sampleStep
            }
            y += sampleStep
        }
        
        return if (total == 0) 0.0 else saturated.toDouble() / total.toDouble()
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
        yPlaneBufferPool.clear()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && thermalListener != null) {
             val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
             powerManager.removeThermalStatusListener(thermalListener!!)
        }
        analyzerExecutor.shutdown()
        Log.d(tag, "Camera resources released. Pool stats: ${yPlaneBufferPool.getStats()}")
    }
}

/**
 * Result of a recording session.
 */
data class RecordingResult(
    val frames: List<Frame>,
    val stats: SessionStats
)


