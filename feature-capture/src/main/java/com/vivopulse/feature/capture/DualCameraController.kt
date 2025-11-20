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
    
    // Use a background executor for image analysis to avoid blocking the main thread
    private val executor: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()
    
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
    
    // Raw frame stream for processing pipeline
    private val _rawFrameFlow = MutableSharedFlow<com.vivopulse.signal.RawFrameData>(
        replay = 0,
        extraBufferCapacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val rawFrameFlow: SharedFlow<com.vivopulse.signal.RawFrameData> = _rawFrameFlow.asSharedFlow()
    
    // Device capabilities and camera mode
    private val deviceProbe = com.vivopulse.feature.capture.camera.DeviceProbe(context)
    private var deviceCapabilities: com.vivopulse.feature.capture.camera.DeviceCapabilities? = null
    private val _cameraMode = MutableStateFlow<com.vivopulse.feature.capture.camera.CameraMode>(
        com.vivopulse.feature.capture.camera.CameraMode.CONCURRENT
    )
    val cameraMode: StateFlow<com.vivopulse.feature.capture.camera.CameraMode> = _cameraMode.asStateFlow()
    
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
            powerManager.addThermalStatusListener(executor, thermalListener!!)
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
            return
        }
        
        // Unbind all use cases before rebinding
        provider.unbindAll()
        
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
            executor = executor,
            processFrame = ::processFrame
        )
        
        val result = bindingHelper.bindCamerasWithFallback(
            provider = provider,
            lifecycleOwner = lifecycleOwner,
            frontPreviewView = frontPreviewView,
            backPreviewView = backPreviewView,
            currentMode = _cameraMode.value,
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
            
            // Prepare RawFrameData components
            var rawYPlane: ByteArray? = null
            var faceRoiData: com.vivopulse.signal.Roi? = null
            var fingerRoiData: com.vivopulse.signal.Roi? = null
            
            if (source == Source.FACE) {
                // Process face ROI
                try {
                    val yData = ByteArray(yBuffer.remaining())
                    yBuffer.get(yData)
                    rawYPlane = yData // Keep reference for RawFrameData
                    
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
                        
                        // Map to shared Roi
                        faceRoiData = com.vivopulse.signal.Roi(
                            currentRoi.rect.left,
                            currentRoi.rect.top,
                            currentRoi.rect.right,
                            currentRoi.rect.bottom
                        )
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error processing face ROI", e)
                }
            } else {
                // Extract luma from finger (center region)
                try {
                    // For finger, we also need the Y data for RawFrameData
                    val yData = ByteArray(yBuffer.remaining())
                    yBuffer.get(yData)
                    rawYPlane = yData
                    
                    // Calculate Finger ROI
                    val fRoi = com.vivopulse.feature.capture.roi.FingerRoiDetector.detectOptimalFingerRoi(
                        yBuffer, // Note: this might advance position if not careful, but detectOptimalFingerRoi takes ByteBuffer
                        rowStride,
                        image.width,
                        image.height
                    )
                    
                    fingerRoiData = com.vivopulse.signal.Roi(
                        fRoi.left, fRoi.top, fRoi.right, fRoi.bottom
                    )
                    
                    // We can use the calculated ROI for extraction too, or keep using LumaExtractor's center logic
                    // LumaExtractor.extractCenterRegionLuma uses a fixed center.
                    // Ideally we sync them. For now, let's stick to LumaExtractor for the wave, 
                    // but pass the detected ROI to the pipeline.
                    yBuffer.rewind()
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
            
            // Emit RawFrameData if we have the plane
            if (rawYPlane != null) {
                val rawFrame = com.vivopulse.signal.RawFrameData(
                    timestampNs = image.timestamp,
                    width = image.width,
                    height = image.height,
                    yPlane = rawYPlane,
                    faceRoi = faceRoiData,
                    fingerRoi = fingerRoiData
                )
                _rawFrameFlow.tryEmit(rawFrame)
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && thermalListener != null) {
             val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
             powerManager.removeThermalStatusListener(thermalListener!!)
        }
        executor.shutdown()
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

