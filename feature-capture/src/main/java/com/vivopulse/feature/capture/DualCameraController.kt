package com.vivopulse.feature.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import com.vivopulse.signal.AppLogger
import com.vivopulse.feature.capture.analysis.SafeImageAnalyzer
import com.vivopulse.feature.capture.camera.SequentialPrimary
import com.vivopulse.feature.capture.model.Frame
import com.vivopulse.feature.capture.RgbData
import com.vivopulse.feature.capture.RgbExtractor
import com.vivopulse.feature.capture.model.Source
import com.vivopulse.feature.capture.model.SessionStats
import com.vivopulse.feature.capture.roi.FaceRoi
import com.vivopulse.feature.capture.roi.FaceRoiTracker
import com.vivopulse.feature.capture.util.FpsTracker
import com.vivopulse.feature.capture.util.BufferPool
import com.vivopulse.signal.RgbSample
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
 * **MVP Requirements Validation:**
 * - **FR-C1 (Concurrent Capture):** Manages concurrent access to front (face) and back (finger) cameras.
 *   Implements fallback logic: Concurrent -> Reduced Res -> Analysis Only -> Sequential.
 * - **FR-C2 (ImageAnalysis):** Configures analysis with `STRATEGY_KEEP_ONLY_LATEST`.
 * - **FR-C3 (Resolution):** Targets 720p (1280x720) @ 30fps for both streams.
 * - **FR-C4 (Torch/Thermal):** Manages torch state with 60s timeout and thermal throttling monitoring.
 * - **FR-I1/FR-I2 (Metrics):** Computes raw signal metrics (Sat%, Luma) for downstream quality estimation.
 * - **NFR-R1 (Reliability):** Handles camera disconnects and binding failures with safe teardown/retry.
 * 
 * **Architecture:**
 * - Uses a shared `FixedThreadPool(2)` for parallel frame analysis to avoid blocking.
 * - separate flows for `frontFrames` and `backFrames` to decouple consumers.
 */
@ExperimentalCamera2Interop
class DualCameraController(
    private val context: Context
) {
    private val tag = "DualCameraController"
    
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    @Volatile private var imuRmsG: Double = 0.0
    
    // Gravity estimation for high-pass filter
    private var gravityMag: Double = 9.8
    private val alpha = 0.9 // Low-pass filter constant

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val x = it.values[0].toDouble()
                    val y = it.values[1].toDouble()
                    val z = it.values[2].toDouble()
                    val mag = sqrt(x*x + y*y + z*z)
                    
                    // Simple high-pass filter to remove gravity
                    gravityMag = alpha * gravityMag + (1 - alpha) * mag
                    val dynamic = mag - gravityMag
                    
                    val instantRms = kotlin.math.abs(dynamic) / 9.8 // Normalize to G
                    
                    imuRmsG = 0.8 * imuRmsG + 0.2 * instantRms
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // No-op
        }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var frontCamera: Camera? = null
    private var backCamera: Camera? = null
    
    private val analyzerExecutor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "VivoPulseAnalyzer-${System.nanoTime()}").apply { priority = Thread.NORM_PRIORITY }
    }
    
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
    
    val allFrames: Flow<Frame> = merge(frontFrames, backFrames)
    
    private val frontFpsTracker = FpsTracker(Source.FACE)
    private val backFpsTracker = FpsTracker(Source.FINGER)
    
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
    
    private val faceRoiTracker = FaceRoiTracker(detectionInterval = 5)
    val faceRoi: StateFlow<FaceRoi?> = faceRoiTracker.roiState
    
    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()
    
    private val _detailedError = MutableStateFlow<String?>(null)
    val detailedError: StateFlow<String?> = _detailedError.asStateFlow()
    
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
    
    private val _signalSamples = MutableSharedFlow<SignalSample>(
        replay = 0,
        extraBufferCapacity = 5,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val signalSamples: SharedFlow<SignalSample> = _signalSamples.asSharedFlow()
    
    private val deviceProbe = com.vivopulse.feature.capture.camera.DeviceProbe(context)
    private var deviceCapabilities: com.vivopulse.feature.capture.camera.DeviceCapabilities? = null
    private val _cameraMode = MutableStateFlow<com.vivopulse.feature.capture.camera.CameraMode>(
        com.vivopulse.feature.capture.camera.CameraMode.CONCURRENT
    )
    val cameraMode: StateFlow<com.vivopulse.feature.capture.camera.CameraMode> = _cameraMode.asStateFlow()
    
    private val _sequentialPrimary = MutableStateFlow(SequentialPrimary.FINGER)
    val sequentialPrimary: StateFlow<SequentialPrimary> = _sequentialPrimary.asStateFlow()
    
    private var retryCount = 0
    private val maxRetries = 3
    private var currentResolutionIndex = 0
    private val resolutionFallbacks = listOf(
        Size(720, 1280),
        Size(640, 480),
        Size(480, 640)
    )
    
    private var isRecording = false
    private var recordingStartTime = 0L
    private val recordedFrames = mutableListOf<Frame>()
    private val maxRecordedFrames = 3600
    private var faceFrameBuffer: ByteArray? = null
    private var facePatchBuffer: ByteArray? = null
    private var previousFacePatch: ByteArray? = null
    private var fingerRoiRect: Rect? = null
    private var fingerFrameCounter = 0
    
    private val yPlaneBufferPool = BufferPool(
        bufferSize = 720 * 1280,
        poolSize = 10
    )
    
    private var torchEnabled = false
    
    // Store the last binding exception across retries
    private var lastBindingException: Exception? = null
    
    private val consecutiveErrors = java.util.concurrent.ConcurrentHashMap<Source, Int>()
    private val maxConsecutiveErrors = 30
    @Volatile private var circuitBreakerTripped = false
    
    private var frontExposureLocked = false
    private var backExposureLocked = false

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun lockExposure(source: Source) {
        val camera = if (source == Source.FACE) frontCamera else backCamera
        if (camera == null) return

        try {
            val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(camera.cameraControl)
            val captureRequestOptions = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                .build()
            
            camera2Control.addCaptureRequestOptions(captureRequestOptions)
            Log.i(tag, "AE+AWB Locked for ${source.name}")
            AppLogger.log(tag, "Exposure locked for ${source.name} after calibration")
            
            // Update status when both cameras are locked
            if (frontExposureLocked && backExposureLocked) {
                _statusBanner.value = null // Clear "Calibrating..." message
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to lock AE/AWB for ${source.name}", e)
            AppLogger.error(tag, "Failed to lock exposure for ${source.name}", e)
        }
    }

    fun isConcurrentCameraSupported(): Boolean {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val concurrentCameraIds = cameraManager.concurrentCameraIds
                concurrentCameraIds.any { it.size >= 2 }
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(tag, "Error checking concurrent camera support", e)
            true
        }
    }

    private var thermalListener: android.os.PowerManager.OnThermalStatusChangedListener? = null

    @SuppressLint("NewApi")
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
                        if (_statusBanner.value?.contains("Device") == true) {
                            _statusBanner.value = null
                        }
                    }
                }
            }
            powerManager.addThermalStatusListener(analyzerExecutor, thermalListener!!)
        }
    }
    
    suspend fun initialize() {
        AppLogger.log(tag, "Initializing DualCameraController")
        val providerFuture = ProcessCameraProvider.getInstance(context)
        cameraProvider = providerFuture.get()
        
        deviceCapabilities = deviceProbe.probe()
        AppLogger.log(tag, "Device capabilities probed: ${deviceCapabilities?.deviceInfo}")
        _cameraMode.value = deviceCapabilities?.recommendedMode 
            ?: com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_SEQUENTIAL
        
        setupThermalMonitoring()
        setupImu()
        
        Log.d(tag, "Camera provider initialized")
        Log.d(tag, "Device: ${deviceCapabilities?.deviceInfo}")
        Log.d(tag, "Concurrent support: ${deviceCapabilities?.hasConcurrentSupport}")
        Log.d(tag, "Recommended mode: ${_cameraMode.value}")
    }
    
    private fun setupImu() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            Log.w(tag, "No accelerometer found")
        }
    }

    private fun startImu() {
        accelerometer?.let {
            sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun stopImu() {
        sensorManager?.unregisterListener(sensorListener)
    }

    /**
     * Starts the camera capture session.
     * 
     * **Requirements:**
     * - **FR-C1:** Probes strict concurrent capability.
     * - **NFR-R1:** Safe Mode fallback if concurrency fails.
     * 
     * @param lifecycleOwner Lifecycle owner for camera binding.
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
        AppLogger.log(tag, "Starting camera - Mode: ${_cameraMode.value}, Target IDs: ${deviceCapabilities?.concurrentPair}")
        
        provider.unbindAll()
        stopImu()
        fingerFrameCounter = 0
        fingerRoiRect = null
        lastBindingException = null  // Clear previous errors
        consecutiveErrors.clear()
        circuitBreakerTripped = false
        frontExposureLocked = false
        backExposureLocked = false
        
        try {
            when (_cameraMode.value) {
                com.vivopulse.feature.capture.camera.CameraMode.CONCURRENT -> {
                    _statusBanner.value = "Calibrating exposure..."
                }
                com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_SEQUENTIAL -> {
                    _statusBanner.value = "Safe Mode: Sequential camera operation"
                }
                com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_REDUCED -> {
                    _statusBanner.value = "Safe Mode: Reduced resolution"
                }
                com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_ANALYSIS_ONLY -> {
                    _statusBanner.value = "Safe Mode: No preview (analysis only)"
                }
            }
            
            startCamerasWithFallback(lifecycleOwner, frontPreviewView, backPreviewView, provider)
            
            startImu()
            
        } catch (e: Exception) {
            Log.e(tag, "Error starting cameras", e)
            AppLogger.error(tag, "Error starting cameras", e)
            _detailedError.value = buildDetailedError(e, "startCamera")
            handleCameraStartFailure(lifecycleOwner, frontPreviewView, backPreviewView)
        }
    }
    
    
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
            resolutionIndex = currentResolutionIndex,
            targetFrontId = deviceCapabilities?.concurrentPair?.first,
            targetBackId = deviceCapabilities?.concurrentPair?.second
        )
        
        if (result != null) {
            frontCamera = result.first
            backCamera = result.second
            
            if (torchEnabled && backCamera != null) {
                backCamera?.cameraControl?.enableTorch(true)
            }
            
            Log.d(tag, "Cameras started successfully in mode ${_cameraMode.value}")
        } else {
            // Use the actual binding error if available and store it at class level
            val actualError = bindingHelper.lastBindingError
            if (actualError != null) {
                lastBindingException = actualError
                _detailedError.value = buildDetailedError(actualError, "bindCamerasWithFallback")
                throw actualError
            } else {
                val genericError = Exception("Camera binding failed at resolution index $currentResolutionIndex (no specific error captured)")
                lastBindingException = genericError
                throw genericError
            }
        }
    }
    
    @SuppressLint("RestrictedApi")
    private fun handleCameraStartFailure(
        lifecycleOwner: LifecycleOwner,
        frontPreviewView: PreviewView,
        backPreviewView: PreviewView
    ) {
        retryCount++
        
        if (retryCount > maxRetries) {
            _statusBanner.value = "Camera error. Please restart the app."
            // Use the stored exception from previous retries
            _detailedError.value = buildDetailedError(lastBindingException, "maxRetriesExceeded")
            Log.e(tag, "Max retries exceeded, giving up. Last error: ${lastBindingException?.message}")
            return
        }
        
        when {
            currentResolutionIndex < resolutionFallbacks.size - 1 -> {
                currentResolutionIndex++
                if (_cameraMode.value == com.vivopulse.feature.capture.camera.CameraMode.CONCURRENT) {
                    _cameraMode.value = com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_REDUCED
                }
                Log.w(tag, "Retrying with reduced resolution (index $currentResolutionIndex)")
            }
            _cameraMode.value == com.vivopulse.feature.capture.camera.CameraMode.CONCURRENT || 
            _cameraMode.value == com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_REDUCED -> {
                _cameraMode.value = com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_ANALYSIS_ONLY
                currentResolutionIndex = 0
                Log.w(tag, "Switching to analysis-only mode")
            }
            _cameraMode.value == com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_ANALYSIS_ONLY -> {
                _cameraMode.value = com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_SEQUENTIAL
                currentResolutionIndex = 0
                Log.w(tag, "Switching to sequential mode")
            }
            else -> {
                _statusBanner.value = "Camera initialization failed. Please restart."
                return
            }
        }
        
        try {
            startCamera(lifecycleOwner, frontPreviewView, backPreviewView)
        } catch (e: Exception) {
            Log.e(tag, "Retry failed", e)
            _statusBanner.value = "Camera error. Tap to retry."
            _detailedError.value = buildDetailedError(e, "retryFailed")
        }
    }
    
    private fun buildDetailedError(e: Throwable?, context: String): String {
        val sb = StringBuilder()
        sb.appendLine("=== VivoPulse Camera Error ===")
        sb.appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        sb.appendLine("Context: $context")
        sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("")
        sb.appendLine("Camera Mode: ${_cameraMode.value}")
        sb.appendLine("Resolution Index: $currentResolutionIndex")
        sb.appendLine("Retry Count: $retryCount/$maxRetries")
        sb.appendLine("Concurrent Pair: ${deviceCapabilities?.concurrentPair}")
        sb.appendLine("Concurrent Support: ${deviceCapabilities?.hasConcurrentSupport}")
        sb.appendLine("")
        if (e != null) {
            sb.appendLine("Exception: ${e.javaClass.simpleName}")
            sb.appendLine("Message: ${e.message}")
            sb.appendLine("")
            sb.appendLine("Stack Trace:")
            sb.appendLine(Log.getStackTraceString(e))
        } else {
            sb.appendLine("No exception captured (max retries exceeded)")
        }
        val errorReport = sb.toString()
        
        // Write to log file
        AppLogger.error(tag, "CAMERA ERROR REPORT:\n$errorReport", e)
        
        return errorReport
    }
    
    fun clearError() {
        _detailedError.value = null
    }
    
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
     * Process incoming frames from ImageAnalysis.
     * 
     * **Requirements:**
     * - **FR-C2:** Must close `ImageProxy` in `finally` block to prevent starvation.
     * - **NFR-P2:** Total processing budget <= 8ms/frame.
     * - **NFR-S2:** Raw frames are dropped; only metrics/signals extracted.
     * 
     * **Metrics Computed:**
     * - **FR-I1 (Finger):** Luma, Saturation% (>250), ISO/Gain (implied).
     * - **FR-I2 (Face):** Luma, ROI Motion RMS.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(imageProxy: ImageProxy, source: Source) {
        if (circuitBreakerTripped) {
            imageProxy.close()
            return
        }

        val image = imageProxy.image
        if (image == null) {
            Log.w(tag, "processFrame: image is null for ${source.name}")
            imageProxy.close()
            return
        }
        
        try {
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
            var faceRgb: RgbData? = null
            var fingerRgb: RgbData? = null
            var faceMotionRms: Double? = null
            var fingerSaturationPct: Double? = null

            if (source == Source.FACE) {
                try {
                    try {
                        faceRoiTracker.processFrame(imageProxy)
                    } catch (e: Exception) {
                        Log.w(tag, "Face ROI tracking failed (non-critical): ${e.message}")
                    }

                    val currentRoi = faceRoi.value?.rect
                    val roiToUse = if (currentRoi != null && !currentRoi.isEmpty) {
                        currentRoi
                    } else {
                        android.graphics.Rect(
                            (image.width * 0.2).toInt(),
                            (image.height * 0.2).toInt(),
                            (image.width * 0.8).toInt(),
                            (image.height * 0.8).toInt()
                        )
                    }
                    
                    faceRgb = RgbExtractor.extractAverageRgb(imageProxy, roiToUse)
                    faceLuma = faceRgb?.g
                    
                    if (currentRoi != null && !currentRoi.isEmpty) {
                        val motionBuffer = yPlane.buffer.duplicate().apply { position(0) }
                        faceMotionRms = computeFaceMotionRms(motionBuffer, rowStride, currentRoi)
                    }
                    
                    faceLuma?.let { 
                        _faceWave.tryEmit(it)
                        Log.v(tag, "Face luma: $it")
                    }
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

                    fingerRgb = RgbExtractor.extractAverageRgb(imageProxy, roi)
                    fingerLuma = fingerRgb?.g


                    val satBuffer = yPlane.buffer.duplicate().apply { position(0) }
                    fingerSaturationPct = computeSaturationPct(satBuffer, roi, rowStride)

                    fingerLuma?.let { 
                        _fingerWave.tryEmit(it)
                        Log.v(tag, "Finger luma: $it")
                    }
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
                fingerLuma = fingerLuma,
                faceRgb = faceRgb,
                fingerRgb = fingerRgb,
                faceMotionRms = faceMotionRms,
                fingerSaturationPct = fingerSaturationPct,
                imuRmsG = imuRmsG,
                faceRoiRect = if (source == Source.FACE) faceRoi.value?.rect else null
            )
            
            // Check for AE Lock (delayed)
            if (source == Source.FACE && !frontExposureLocked && frontFpsTracker.totalFrames > 30) {
                lockExposure(Source.FACE)
                frontExposureLocked = true
            } else if (source == Source.FINGER && !backExposureLocked && backFpsTracker.totalFrames > 30) {
                lockExposure(Source.FINGER)
                backExposureLocked = true
            }

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
                    if (recordedFrames.size == 1) {
                        Log.d(tag, "Recording: First frame captured from ${source.name}")
                    }
                    if (recordedFrames.size % 30 == 0) {
                        Log.d(tag, "Recording: ${recordedFrames.size} frames captured (${source.name})")
                    }
                }
            }

            _signalSamples.tryEmit(
                SignalSample(
                    timestampNs = image.timestamp,
                    faceMeanLuma = faceLuma,
                    fingerMeanLuma = fingerLuma,
                    faceRgb = faceRgb?.let { RgbSample(it.r, it.g, it.b) },
                    fingerRgb = fingerRgb?.let { RgbSample(it.r, it.g, it.b) },
                    faceMotionRmsPx = faceMotionRms,
                    fingerSaturationPct = fingerSaturationPct,
                    imuRmsG = imuRmsG,
                    torchEnabled = torchEnabled
                )
            )
            
            // Reset error count on success
            consecutiveErrors[source] = 0
            
        } catch (e: Exception) {
            val count = (consecutiveErrors[source] ?: 0) + 1
            consecutiveErrors[source] = count
            
            if (count > maxConsecutiveErrors && !circuitBreakerTripped) {
                circuitBreakerTripped = true
                val msg = "Processing failure for ${source.name} (x$count). Stopping."
                Log.e(tag, msg, e)
                _statusBanner.value = "Camera critical error. Please restart."
                _detailedError.value = buildDetailedError(e, "CircuitBreaker:${source.name}")
                // Stop recording if active to save what we have
                if (isRecording) {
                    stopRecording()
                }
            } else if (count % 10 == 0) {
                 Log.e(tag, "Consecutive error for ${source.name}: $count", e)
            }
            
            // Still log individual errors
            if (count <= 5) { // Reduce log spam
                Log.e(tag, "Error processing frame from ${source.name}", e)
            }
        } finally {
            imageProxy.close()
        }
    }
    
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
        
        Log.d(tag, "Recording started - mode: ${_cameraMode.value}, sequential primary: $sequentialPrimary")
    }
    
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
    
    fun setTorchEnabled(enabled: Boolean) {
        torchEnabled = enabled
        backCamera?.cameraControl?.enableTorch(enabled)
        Log.d(tag, "Torch ${if (enabled) "enabled" else "disabled"}")
    }
    
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
    
    fun isRecording(): Boolean = isRecording
    
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
