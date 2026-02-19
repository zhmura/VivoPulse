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
import com.vivopulse.signal.PulseLog
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
    
    // AE Diagnostics: Map timestamp -> TotalCaptureResult
    private val captureResults = java.util.concurrent.ConcurrentHashMap<Long, android.hardware.camera2.TotalCaptureResult>()
    
    private val faceRoiTracker = FaceRoiTracker(detectionInterval = 5)
    val faceRoi: StateFlow<FaceRoi?> = faceRoiTracker.roiState
    
    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()
    
    // Gap F: GoodSync progress — accumulated stable capture seconds
    private val _goodSyncSeconds = MutableStateFlow(0.0)
    val goodSyncSeconds: StateFlow<Double> = _goodSyncSeconds.asStateFlow()
    private var stableFrameCount = 0
    private var lastEffectiveFps = 30.0

    /** Current blocking reason preventing stable data collection (null = stable). */
    private val _goodSyncBlocker = MutableStateFlow<String?>(null)
    val goodSyncBlocker: StateFlow<String?> = _goodSyncBlocker.asStateFlow()
    
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
    
    /** Whether camera FPS supports PTT (≥25Hz). False if fell back to [15,30] HR-only mode. */
    private val _pttCapable = MutableStateFlow(true)
    val pttCapable: StateFlow<Boolean> = _pttCapable.asStateFlow()
    
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
    private var frontExposureSettled = true  // true = AE converged; false = timeout-forced
    private var backExposureSettled = true

    // P0-A: Stability-based AE lock — replace fixed 30-frame threshold
    // Rolling luma windows per camera, lock when settled
    private val frontLumaSamples = ArrayDeque<Double>()
    private val backLumaSamples = ArrayDeque<Double>()

    // Gap A: Post-lock AE drift monitoring — rolling EV window
    // EV = ln(exposureTime * ISO), monitor drift after lock
    private val frontPostLockEv = ArrayDeque<Double>()
    private val backPostLockEv = ArrayDeque<Double>()
    private var frontAeDrifted = false
    private var backAeDrifted = false

    // Gap B: Metadata-based photometric gate — frame-to-frame EV step detection
    private var lastFingerEv: Double? = null
    private var fingerExposureStepDetected = false

    private companion object {
        const val AE_STABILITY_WINDOW = 15       // ~500ms rolling window @ 30fps
        const val AE_MIN_SAMPLES = 10            // ~330ms minimum before evaluating
        const val AE_LUMA_CHANGE_EPSILON = 1.5   // Max mean shift between halves (~0.6% of 0-255)
        const val AE_LUMA_VARIANCE_DELTA = 5.0   // Max variance within window
        const val AE_MAX_WAIT_FRAMES = 90        // 3s hard timeout @ 30fps
        // Gap A: Post-lock drift thresholds
        const val POST_LOCK_EV_WINDOW = 30       // ~1s rolling window @ 30fps
        const val POST_LOCK_DRIFT_THRESHOLD = 0.02  // 2% relative drift triggers instability
        // Gap B: Exposure step thresholds
        const val EXPOSURE_STEP_THRESHOLD = 0.03    // 3% EV step → PTT hard-off
        const val CLIPPING_HARD_GATE_PCT = 15.0     // 15% clipping → PTT hard-off
    }

    /**
     * Result of AE lock evaluation.
     */
    enum class AeLockResult { NOT_READY, SETTLED, TIMEOUT }

    /**
     * Evaluate whether AE has converged based on luma stability.
     * Returns SETTLED when the rolling luma window shows settled mean + low variance,
     * TIMEOUT when the hard timeout (3s) is reached, or NOT_READY otherwise.
     */
    private fun shouldLockExposure(luma: Double?, tracker: FpsTracker, samples: ArrayDeque<Double>): AeLockResult {
        luma ?: return AeLockResult.NOT_READY
        samples.addLast(luma)
        if (samples.size > AE_STABILITY_WINDOW) samples.removeFirst()

        // Hard timeout fallback (same as old 30-frame but extended to 3s)
        if (tracker.totalFrames > AE_MAX_WAIT_FRAMES) {
            Log.w(PulseLog.MEASURE, "AE_LOCK | TIMEOUT at ${tracker.totalFrames} frames — exposure may not have converged")
            return AeLockResult.TIMEOUT
        }

        // Need enough samples for meaningful stability check
        if (samples.size < AE_MIN_SAMPLES) return AeLockResult.NOT_READY

        // Check stability: compare mean of first half vs second half
        val half = samples.size / 2
        val firstHalf = samples.take(half).average()
        val secondHalf = samples.drop(half).average()
        val meanChange = kotlin.math.abs(firstHalf - secondHalf)

        // Check variance within window
        val mean = samples.average()
        val variance = samples.map { (it - mean) * (it - mean) }.average()

        val settled = meanChange < AE_LUMA_CHANGE_EPSILON && variance < AE_LUMA_VARIANCE_DELTA
        if (settled) {
            Log.d(PulseLog.MEASURE, "AE_LOCK | luma settled (change=%.2f, var=%.2f) at frame ${tracker.totalFrames}".format(meanChange, variance))
        }
        return if (settled) AeLockResult.SETTLED else AeLockResult.NOT_READY
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun lockExposure(source: Source) {
        val camera = if (source == Source.FACE) frontCamera else backCamera
        if (camera == null) return

        try {
            val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(camera.cameraControl)
            // Lock AE + AWB always
            val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)

            // Gap C: Conditional AF lock based on device capability
            if (deviceCapabilities?.supportsManualFocus == true) {
                // Manual focus: lock AF off and set explicit focus distance
                val focusDistance = if (source == Source.FACE) 0.0f else 10.0f // Infinity for face, near/macro for finger
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                Log.i(PulseLog.MEASURE, "3A_LOCK | ${source.name} | manual AF | focusDist=$focusDistance")
            } else {
                // Fallback: trigger one-shot AF then let it settle
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_AUTO)
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, android.hardware.camera2.CameraMetadata.CONTROL_AF_TRIGGER_START)
                Log.w(PulseLog.MEASURE, "3A_LOCK | ${source.name} | no manual focus — best-effort AF_AUTO")
            }

            camera2Control.addCaptureRequestOptions(builder.build())
            AppLogger.log(tag, "3A locked for ${source.name} after calibration")
            
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
        
        Log.i(PulseLog.HW, "CAMERA_INIT | device=${deviceCapabilities?.deviceInfo} | concurrent=${deviceCapabilities?.hasConcurrentSupport} | manualFocus=${deviceCapabilities?.supportsManualFocus} | mode=${_cameraMode.value}")
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
        
        Log.i(PulseLog.HW, "CAMERA_START | mode=${_cameraMode.value} | seqPrimary=$sequentialPrimary | targetIds=${deviceCapabilities?.concurrentPair}")
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
        frontExposureSettled = true
        backExposureSettled = true
        frontLumaSamples.clear()
        backLumaSamples.clear()
        captureResults.clear()
        
        try {
            when (_cameraMode.value) {
                com.vivopulse.feature.capture.camera.CameraMode.CONCURRENT -> {
                    _statusBanner.value = "Searching — Place finger on lens"
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
            
            // Propagate PTT capability from binding helper
            _pttCapable.value = bindingHelper.pttCapable
            if (!bindingHelper.pttCapable) {
                Log.w(PulseLog.HW, "CAMERA_BIND | HR-only mode (PTT disabled, FPS < 25Hz)")
            }
            
            if (torchEnabled && backCamera != null) {
                backCamera?.cameraControl?.enableTorch(true)
            }
            
            Log.i(PulseLog.HW, "CAMERA_BIND | OK | mode=${_cameraMode.value} | pttCapable=${_pttCapable.value}")
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
                Log.w(PulseLog.HW, "CAMERA_FALLBACK | reduced resolution (index $currentResolutionIndex)")
            }
            _cameraMode.value == com.vivopulse.feature.capture.camera.CameraMode.CONCURRENT || 
            _cameraMode.value == com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_REDUCED -> {
                _cameraMode.value = com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_ANALYSIS_ONLY
                currentResolutionIndex = 0
                Log.w(PulseLog.HW, "CAMERA_FALLBACK | analysis-only mode")
            }
            _cameraMode.value == com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_ANALYSIS_ONLY -> {
                _cameraMode.value = com.vivopulse.feature.capture.camera.CameraMode.SAFE_MODE_SEQUENTIAL
                currentResolutionIndex = 0
                Log.w(PulseLog.HW, "CAMERA_FALLBACK | sequential mode")
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

            // P1-B DIAGNOSTIC: Log Image.timestamp per source for clock domain analysis
            // On-device: compare this with CaptureResult.SENSOR_TIMESTAMP when Camera2 capture callback is added
            if (tracker.totalFrames <= 60 || tracker.totalFrames % 300 == 0) {
                Log.d(PulseLog.FRAME, "CLOCK_DIAG | src=${source.name} | frame=${tracker.totalFrames} | imageTs=${image.timestamp} | elapsedNs=${if (tracker.totalFrames > 1) image.timestamp - (if (source == Source.FACE) _frontTimestamps.replayCache.lastOrNull() ?: image.timestamp else _backTimestamps.replayCache.lastOrNull() ?: image.timestamp) else 0}")
            }

            if (source == Source.FACE) {
                _frontTimestamps.tryEmit(image.timestamp)
            } else {
                _backTimestamps.tryEmit(image.timestamp)
            }

            val yPlane = image.planes[0]
            val rowStride = yPlane.rowStride


            var faceLuma: Double? = null
            var fingerLuma: Double? = null
            var fingerVChannel: Double? = null // For signal (Red-difference)
            var faceRgb: RgbData? = null
            var fingerRgb: RgbData? = null
            var faceMotionRms: Double? = null
            var fingerSaturationPct: Double? = null
            var clippingPct: Double? = null

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
                    
                    // P0-B: Use green-proxy instead of full RGB for face rPPG signal
                    faceLuma = RgbExtractor.extractAverageGreenProxy(imageProxy, roiToUse)
                    // Full RGB only for recording/debug (not every frame)
                    if (isRecording || frontFpsTracker.totalFrames % 30 == 0) {
                        faceRgb = RgbExtractor.extractAverageRgb(imageProxy, roiToUse)
                    }
                    
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

                    // P0-B: Use luma-only for finger PPG (red dominates with torch)
                    fingerLuma = RgbExtractor.extractAverageLuma(imageProxy, roi)
                    // V-Channel optimization (from drift): cleaner red signal
                    fingerVChannel = RgbExtractor.extractAverageVChannel(imageProxy, roi)
                    
                    // Full RGB only for recording/debug (not every frame)
                    if (isRecording || backFpsTracker.totalFrames % 30 == 0) {
                        fingerRgb = RgbExtractor.extractAverageRgb(imageProxy, roi)
                    }


                    val satBuffer = yPlane.buffer.duplicate().apply { position(0) }
                    fingerSaturationPct = computeSaturationPct(satBuffer, roi, rowStride)
                    
                    val clipBuffer = yPlane.buffer.duplicate().apply { position(0) }
                    // Clipping detection: low < 5, high > 250
                    clippingPct = computeClippingPct(clipBuffer, roi, rowStride)
                    
                    // Directional clipping feedback
                    if (clippingPct > 0.1 && fingerFrameCounter % 30 == 0) {
                         // Determine dominant clipping direction
                         val highClipBuffer = yPlane.buffer.duplicate().apply { position(0) }
                         val highPct = computeSaturationPct(highClipBuffer, roi, rowStride) // pixels >= 250
                         if (highPct > 0.05) {
                             _statusBanner.value = "Too bright — reduce pressure slightly"
                         } else {
                             _statusBanner.value = "Too dark — press fingertip firmly"
                         }
                    }
                    
                    // Finger-cover detection: low variance + reasonable luma = covered
                    val coverBuffer = yPlane.buffer.duplicate().apply { position(0) }
                    val roiVariance = com.vivopulse.feature.capture.roi.FingerRoiDetector.computeRoiVariance(coverBuffer, roi, rowStride, image.width, image.height)
                    val isCovered = (fingerLuma ?: 0.0) > 30.0 && roiVariance < 800.0
                    if (!isCovered && fingerFrameCounter % 30 == 0) {
                        _statusBanner.value = "Cover lens fully with your fingertip"
                    }

                    // Use V-Channel for signal if available, else Luma
                    (fingerVChannel ?: fingerLuma)?.let { 
                        _fingerWave.tryEmit(it)
                        Log.v(tag, "Finger signal (V): $it")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error processing finger channel", e)
                }
            }


            // Retrieve CaptureResult metadata (AE/ISO)
            // Note: Since ImageAnalysis may drop frames, captureResults can grow.
            // Safety check: if map gets too big, clear it to prevent OOM.
            if (captureResults.size > 50) {
                captureResults.clear()
            }
            val result = captureResults.remove(image.timestamp)

            // P0-A Hardening: Block PTT analysis until AE is locked, settled, and stable
            // Gap A+B: Also block if post-lock drift detected or exposure step detected
            val outputReady = if (source == Source.FACE) {
                frontExposureLocked && frontExposureSettled && !frontAeDrifted
            } else {
                backExposureLocked && backExposureSettled && !backAeDrifted && !fingerExposureStepDetected
            }
            
            if (!outputReady) {
                // 3-state status feedback (E)
                val isLocked = if (source == Source.FACE) frontExposureLocked else backExposureLocked
                if (source == Source.FINGER && fingerFrameCounter % 30 == 0) {
                    _statusBanner.value = if (isLocked) {
                        // Locked but not settled (timeout case)
                        "Unstable light — try adjusting position"
                    } else {
                        "Stabilizing..."
                    }
                }
            } else if (_statusBanner.value?.startsWith("Stabil") == true ||
                       _statusBanner.value?.startsWith("Searching") == true ||
                       _statusBanner.value?.startsWith("Unstable") == true) {
                // Transition to locked state — clear calibration messages
                _statusBanner.value = null
                Log.i(PulseLog.MEASURE, "3A_SETTLED | ${source.name} — Measuring")
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
                faceRoiRect = if (source == Source.FACE) faceRoi.value?.rect else null,
                exposureTimeNs = result?.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME),
                sensitivity = result?.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY),
                frameDurationNs = result?.get(android.hardware.camera2.CaptureResult.SENSOR_FRAME_DURATION),
                aeState = result?.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE),
                awbState = result?.get(android.hardware.camera2.CaptureResult.CONTROL_AWB_STATE),
                clippingPct = clippingPct
            )
            
            // AE DIAGNOSTICS (Step 1 & 6) for FINGER camera
            if (source == Source.FINGER && frame.exposureTimeNs != null) {
                // Log every 30th frame or if steps detected (TODO: step detection)
                // For now, log if AE state is searching/converged transitions, or periodically
                val aeState = frame.aeState
                val aeStateStr = when (aeState) {
                    android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
                    android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
                    android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
                    android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_LOCKED -> "LOCKED"
                    android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQ"
                    android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
                    else -> "UNKNOWN($aeState)"
                }
                
                // Only log interesting events or periodic sample
                if (aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_SEARCHING || 
                    aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    (fingerFrameCounter % 30 == 0)) {
                        
                    Log.d(PulseLog.FRAME, "AE_DIAG | FINGER | state=$aeStateStr | " +
                          "exp=${frame.exposureTimeNs}ns | iso=${frame.sensitivity} | " +
                          "dur=${frame.frameDurationNs}ns | meanY=${String.format("%.1f", fingerLuma ?: 0.0)}")
                }
            }
            
            // Check for AE Lock (stability-based, P0-A)
            if (source == Source.FACE && !frontExposureLocked) {
                val lockResult = shouldLockExposure(faceLuma, frontFpsTracker, frontLumaSamples)
                if (lockResult != AeLockResult.NOT_READY) {
                    lockExposure(Source.FACE)
                    frontExposureLocked = true
                    frontExposureSettled = (lockResult == AeLockResult.SETTLED)
                    if (!frontExposureSettled) {
                        Log.w(PulseLog.FRAME, "AE_TIMEOUT_DIAG | Face exposure locked via TIMEOUT — SNR may be degraded")
                    }
                }
            } else if (source == Source.FINGER && !backExposureLocked) {
                val lockResult = shouldLockExposure(fingerLuma, backFpsTracker, backLumaSamples)
                if (lockResult != AeLockResult.NOT_READY) {
                    lockExposure(Source.FINGER)
                    backExposureLocked = true
                    backExposureSettled = (lockResult == AeLockResult.SETTLED)
                    if (!backExposureSettled) {
                        Log.w(PulseLog.FRAME, "AE_TIMEOUT_DIAG | Finger exposure locked via TIMEOUT — SNR may be degraded")
                    }
                }
            }

            // === Gap A: Post-lock AE drift monitoring ===
            // After lock, continuously track EV = ln(exp * iso) for drift detection
            if ((source == Source.FACE && frontExposureLocked) ||
                (source == Source.FINGER && backExposureLocked)) {
                val expNs = frame.exposureTimeNs
                val iso = frame.sensitivity
                if (expNs != null && iso != null && expNs > 0 && iso > 0) {
                    val ev = kotlin.math.ln((expNs.toDouble() * iso.toDouble()))
                    val evWindow = if (source == Source.FACE) frontPostLockEv else backPostLockEv
                    evWindow.addLast(ev)
                    if (evWindow.size > POST_LOCK_EV_WINDOW) evWindow.removeFirst()

                    if (evWindow.size >= POST_LOCK_EV_WINDOW) {
                        val evMean = evWindow.average()
                        val evDrift = (evWindow.max() - evWindow.min()) / kotlin.math.abs(evMean)
                        if (evDrift > POST_LOCK_DRIFT_THRESHOLD) {
                            if (source == Source.FACE && !frontAeDrifted) {
                                frontAeDrifted = true
                                Log.w(PulseLog.FRAME, "AE_DRIFT | FACE | drift=${"%.1f".format(evDrift * 100)}% — blocking PTT")
                                _statusBanner.value = "Unstable light — try adjusting position"
                            } else if (source == Source.FINGER && !backAeDrifted) {
                                backAeDrifted = true
                                Log.w(PulseLog.FRAME, "AE_DRIFT | FINGER | drift=${"%.1f".format(evDrift * 100)}% — blocking PTT")
                                _statusBanner.value = "Unstable light — try adjusting position"
                            }
                        }
                    }

                    // === Gap B: Metadata-based photometric gate (finger only) ===
                    if (source == Source.FINGER) {
                        val prevEv = lastFingerEv
                        lastFingerEv = ev
                        if (prevEv != null && kotlin.math.abs(prevEv) > 0.0) {
                            val stepPct = kotlin.math.abs(ev - prevEv) / kotlin.math.abs(prevEv)
                            val hardClip = (clippingPct ?: 0.0) > CLIPPING_HARD_GATE_PCT
                            if (stepPct > EXPOSURE_STEP_THRESHOLD || hardClip) {
                                if (!fingerExposureStepDetected) {
                                    fingerExposureStepDetected = true
                                    val reason = if (hardClip) "clipping=${"%.0f".format(clippingPct)}%" else "step=${"%.1f".format(stepPct * 100)}%"
                                    Log.w(PulseLog.FRAME, "PHOTO_GATE | FINGER | $reason — PTT hard-off")
                                    _statusBanner.value = "Brightness changed — restarting..."
                                }
                            }
                        }
                    }
                }
            }

            // === Gap F: GoodSync progress tracking ===
            // Count stable frames where all conditions for quality capture are met
            if (source == Source.FINGER) {
                val isStable = outputReady &&
                    !frontAeDrifted && !backAeDrifted &&
                    !fingerExposureStepDetected &&
                    (clippingPct ?: 0.0) <= CLIPPING_HARD_GATE_PCT &&
                    imuRmsG < 0.1

                if (isStable) {
                    stableFrameCount++
                    _goodSyncBlocker.value = null
                } else {
                    stableFrameCount = 0
                    // Identify blocking reason for UX
                    _goodSyncBlocker.value = when {
                        !backExposureLocked -> "Finger exposure"
                        !frontExposureLocked -> "Face exposure"
                        backAeDrifted || frontAeDrifted -> "Lighting"
                        fingerExposureStepDetected -> "Brightness change"
                        (clippingPct ?: 0.0) > CLIPPING_HARD_GATE_PCT -> "Clipping"
                        imuRmsG >= 0.1 -> "Motion"
                        else -> "Stabilizing"
                    }
                }
                _goodSyncSeconds.value = stableFrameCount / lastEffectiveFps
            }

            val flowEmitted = if (outputReady) {
                if (source == Source.FACE) {
                    _frontFrames.tryEmit(frame)
                } else {
                    _backFrames.tryEmit(frame)
                }
            } else {
                // Log why frames are being dropped (PTT=0 diagnostic)
                if (tracker.totalFrames % 60 == 0) {
                    val reason = when {
                        source == Source.FACE && !frontExposureLocked -> "AE_NOT_LOCKED"
                        source == Source.FACE && !frontExposureSettled -> "AE_NOT_SETTLED"
                        source == Source.FACE && frontAeDrifted -> "AE_DRIFTED"
                        source == Source.FINGER && !backExposureLocked -> "AE_NOT_LOCKED"
                        source == Source.FINGER && !backExposureSettled -> "AE_NOT_SETTLED"
                        source == Source.FINGER && backAeDrifted -> "AE_DRIFTED"
                        source == Source.FINGER && fingerExposureStepDetected -> "PHOTO_GATE"
                        else -> "UNKNOWN"
                    }
                    Log.d(PulseLog.MEASURE, "OUTPUT_BLOCKED | ${source.name} | reason=$reason | frame=${tracker.totalFrames}")
                }
                false
            }

            if (!flowEmitted && outputReady) {
                tracker.onFrameDropped()
                Log.w(PulseLog.FRAME, "BUFFER_FULL | ${source.name} | frame=${tracker.totalFrames}")
            } else if (!flowEmitted) {
                tracker.onFrameDropped()
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
                    fingerMeanLuma = fingerVChannel ?: fingerLuma, // Use V-Channel for signal
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
        
        // Fix 2: Reset GoodSync counter per recording session (was accumulating across camera lifecycle)
        stableFrameCount = 0
        _goodSyncSeconds.value = 0.0
        _goodSyncBlocker.value = "Stabilizing"
        
        recordingStartTime = System.currentTimeMillis()
        isRecording = true
        
        Log.i(PulseLog.SESSION, "SESSION_START | mode=${_cameraMode.value} | seqPrimary=$sequentialPrimary | torch=$torchEnabled")
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
        
        Log.i(PulseLog.SESSION, "SESSION_STOP | frames=${frames.size} | duration=${durationMs}ms | " +
            "face=$frontReceived/${frontDropped}drop/${String.format("%.1f", frontFps)}fps | " +
            "finger=$backReceived/${backDropped}drop/${String.format("%.1f", backFps)}fps | " +
            "aeDriftFace=$frontAeDrifted | aeDriftFinger=$backAeDrifted | " +
            "photoGate=$fingerExposureStepDetected | " +
            "goodSync=${"%.1f".format(_goodSyncSeconds.value)}s | blocker=${_goodSyncBlocker.value ?: "none"}")
        
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
    
    private fun computeClippingPct(
        buffer: ByteBuffer,
        roi: Rect,
        rowStride: Int,
        lowThreshold: Int = 5,
        highThreshold: Int = 250,
        sampleStep: Int = 2
    ): Double {
        if (roi.isEmpty) return 0.0
        var clipped = 0
        var total = 0
        
        var y = roi.top
        while (y < roi.bottom) {
            val base = y * rowStride
            var x = roi.left
            while (x < roi.right) {
                val value = buffer.get(base + x).toInt() and 0xFF
                if (value <= lowThreshold || value >= highThreshold) {
                    clipped++
                }
                total++
                x += sampleStep
            }
            y += sampleStep
        }
        
        return if (total == 0) 0.0 else clipped.toDouble() / total.toDouble()
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
