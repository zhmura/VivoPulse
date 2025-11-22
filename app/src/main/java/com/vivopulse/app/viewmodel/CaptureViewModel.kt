package com.vivopulse.app.viewmodel

import android.app.Application
import android.os.Debug
import android.util.Log
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vivopulse.app.reporting.QualityIndicatorReporter
import com.vivopulse.app.util.ErrorHandler
import com.vivopulse.feature.capture.DualCameraController
import com.vivopulse.feature.capture.camera.CameraMode
import com.vivopulse.feature.capture.camera.SequentialPrimary
import com.vivopulse.feature.capture.RecordingResult
import com.vivopulse.feature.capture.model.Frame
import com.vivopulse.feature.capture.model.Source
import com.vivopulse.feature.capture.roi.FaceRoi
import com.vivopulse.feature.processing.realtime.QualityStatus
import com.vivopulse.feature.processing.realtime.RealTimeQualityEngine
import com.vivopulse.feature.processing.realtime.RealTimeQualityState
import com.vivopulse.feature.processing.timestamp.DriftMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Shared recording result across ViewModels.
 * 
 * This is a simple way to pass data between screens.
 * In production, consider using a Repository pattern or Navigation arguments.
 */
object SharedRecordingState {
    private val _lastRecordingResult = MutableStateFlow<RecordingResult?>(null)
    val lastRecordingResult: StateFlow<RecordingResult?> = _lastRecordingResult.asStateFlow()
    
    private val _lastProcessedSignals = MutableStateFlow<List<com.vivopulse.signal.ProcessedSignal>?>(null)
    val lastProcessedSignals: StateFlow<List<com.vivopulse.signal.ProcessedSignal>?> = _lastProcessedSignals.asStateFlow()
    
    fun setRecordingResult(result: RecordingResult, signals: List<com.vivopulse.signal.ProcessedSignal>) {
        Log.d(
            "SharedRecordingState",
            "setRecordingResult(): frames=${result.frames.size}, faceFrames=${result.frames.count { it.source == Source.FACE }}, fingerFrames=${result.frames.count { it.source == Source.FINGER }}, signals=${signals.size}"
        )
        _lastRecordingResult.value = result
        _lastProcessedSignals.value = signals
    }
    
    fun clearRecordingResult() {
        _lastRecordingResult.value = null
        _lastProcessedSignals.value = null
    }
}

/**
 * ViewModel for camera capture screen.
 */
@ExperimentalCamera2Interop
@HiltViewModel
class CaptureViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: android.content.Context
) : AndroidViewModel(application) {

    private val tag = "CaptureViewModel"
    private val cameraController = DualCameraController(application)
    private val driftMonitor = DriftMonitor()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _torchEnabled = MutableStateFlow(false)
    val torchEnabled: StateFlow<Boolean> = _torchEnabled.asStateFlow()
    private var torchEnabledAt: Long? = null
    private val maxTorchMs = 60_000L

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    private val _fps = MutableStateFlow(Pair(0f, 0f))
    val fps: StateFlow<Pair<Float, Float>> = _fps.asStateFlow()

    private val _drift = MutableStateFlow(0.0)
    val drift: StateFlow<Double> = _drift.asStateFlow()
    
    private val _isDriftValid = MutableStateFlow(false)
    val isDriftValid: StateFlow<Boolean> = _isDriftValid.asStateFlow()
    
    // Face ROI
    val faceRoi: StateFlow<FaceRoi?> = cameraController.faceRoi
    
    private val qualityEngine = RealTimeQualityEngine()
    private val _qualityState = MutableStateFlow<RealTimeQualityState?>(null)
    val qualityState: StateFlow<RealTimeQualityState?> = _qualityState.asStateFlow()
    private val qualityReporter = QualityIndicatorReporter(File(application.filesDir, "quality_reports"))
    private val tipsLog = mutableListOf<String>()
    private val tipTimestampFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val recordedSignals = mutableListOf<com.vivopulse.signal.ProcessedSignal>()
    
    // Status banner for smart coach tips
    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()
    val cameraMode: StateFlow<CameraMode> = cameraController.cameraMode
    val sequentialPrimary: StateFlow<SequentialPrimary> = cameraController.sequentialPrimary

    private val _lastRecordingResult = MutableStateFlow<RecordingResult?>(null)
    val lastRecordingResult: StateFlow<RecordingResult?> = _lastRecordingResult.asStateFlow()

    private var recordingStartTime = 0L

    init {
        viewModelScope.launch {
            try {
                cameraController.initialize()
            } catch (e: Exception) {
                Log.e(tag, "Failed to initialize camera", e)
                ErrorHandler.handleCameraError(context, e)
            }
        }

        // Update FPS and drift periodically
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                _fps.value = cameraController.getCurrentFps()

                if (_isRecording.value) {
                    _recordingDuration.value = System.currentTimeMillis() - recordingStartTime
                }
                
                // Torch policy: auto-disable after maxTorchMs
                val t0 = torchEnabledAt
                if (t0 != null && _torchEnabled.value) {
                    val elapsed = System.currentTimeMillis() - t0
                    if (elapsed >= maxTorchMs) {
                        _torchEnabled.value = false
                        cameraController.setTorchEnabled(false)
                        Log.w(tag, "Torch auto-disabled after ${maxTorchMs / 1000}s for safety")
                    }
                }
                
                // Smart Coach Tips
                // In a real app, we'd pull these stats from the processing pipeline.
                // For now, we'll simulate the check or use placeholders if we can't access the pipeline directly here.
                // Assuming cameraController has access to some stats or we add them.
                
                // Let's add a placeholder for the coach logic
                // updateSmartCoachTips() // Removed as it's now driven by pipeline
            }
        }
        
        // Collect timestamps and update drift monitor
        viewModelScope.launch {
            cameraController.frontTimestamps.collect { timestamp ->
                driftMonitor.addFrontTimestamp(timestamp)
            }
        }
        
        viewModelScope.launch {
            cameraController.backTimestamps.collect { timestamp ->
                driftMonitor.addBackTimestamp(timestamp)
            }
        }
        
        // Collect drift information from monitor
        viewModelScope.launch {
            driftMonitor.driftMsPerSecond.collect { drift ->
                _drift.value = drift
            }
        }
        
        viewModelScope.launch {
            driftMonitor.isValid.collect { valid ->
                _isDriftValid.value = valid
            }
        }
        
        observeSignalSamples()
        startMemoryGuard()
    }

    private fun observeSignalSamples() {
        viewModelScope.launch(Dispatchers.Default) {
            cameraController.signalSamples.collect { sample ->
                val state = qualityEngine.addSample(sample) ?: return@collect
                _qualityState.value = state
                qualityReporter.record(state)
                state.tip?.let {
                    Log.d(tag, "Quality tip: $it")
                    tipsLog.add("${tipTimestampFormat.format(Date())} – $it")
                }
                if (_isRecording.value) {
                    recordedSignals.add(state.toProcessedSignal())
                }
            }
        }
    }

    private fun startMemoryGuard() {
        viewModelScope.launch(Dispatchers.Default) {
            var lastAlloc = Debug.getNativeHeapAllocatedSize()
            var lastGc = Debug.getRuntimeStats()["art.gc.gc-count"]?.toLongOrNull() ?: 0L
            while (isActive) {
                delay(1000)
                val currentAlloc = Debug.getNativeHeapAllocatedSize()
                val runtimeStats = Debug.getRuntimeStats()
                val currentGc = runtimeStats["art.gc.gc-count"]?.toLongOrNull() ?: lastGc

                if (currentAlloc - lastAlloc > 20L * 1024 * 1024) {
                    Log.w(tag, "Memory spike detected: +${(currentAlloc - lastAlloc) / (1024 * 1024)} MB")
                }
                if (currentGc > lastGc) {
                    Log.d(tag, "GC count increased by ${currentGc - lastGc}")
                }
                lastAlloc = currentAlloc
                lastGc = currentGc
            }
        }
    }

    fun getCameraController(): DualCameraController = cameraController

    fun startRecording() {
        if (_isRecording.value) return

        recordingStartTime = System.currentTimeMillis()
        _recordingDuration.value = 0L
        _isRecording.value = true
        driftMonitor.reset()
        recordedSignals.clear()
        tipsLog.clear()
        qualityReporter.reset()
        cameraController.startRecording()
    }

    fun stopRecording() {
        if (!_isRecording.value) return

        _isRecording.value = false
        val result = cameraController.stopRecording()
        Log.d(tag, "stopRecording(): frames=${result.frames.size}, durationMs=${result.stats.durationMs}")
        Log.d(
            tag,
            "stopRecording(): faceReceived=${result.stats.faceStats.framesReceived}, fingerReceived=${result.stats.fingerStats.framesReceived}"
        )
        if (result.frames.isEmpty()) {
            Log.w(tag, "stopRecording(): recording result is empty – no frames captured")
        }
        if (recordedSignals.isEmpty()) {
            Log.w(tag, "stopRecording(): recordedSignals buffer empty during session")
        }
        _lastRecordingResult.value = result
        _recordingDuration.value = 0L
        
        // Share recording result with other ViewModels
        SharedRecordingState.setRecordingResult(result, recordedSignals.toList())
        qualityReporter.writeReport(tipsLog.toList())
        tipsLog.clear()
    }

    fun toggleTorch() {
        val newState = !_torchEnabled.value
        _torchEnabled.value = newState
        cameraController.setTorchEnabled(newState)
        torchEnabledAt = if (newState) System.currentTimeMillis() else null
    }
    
    fun setSequentialPrimary(primary: SequentialPrimary) {
        if (primary == SequentialPrimary.FACE && _torchEnabled.value) {
            _torchEnabled.value = false
            torchEnabledAt = null
        }
        cameraController.setSequentialPrimary(primary)
    }

    fun isConcurrentCameraSupported(): Boolean {
        return cameraController.isConcurrentCameraSupported()
    }
    
    private fun RealTimeQualityState.toProcessedSignal(): com.vivopulse.signal.ProcessedSignal {
        val hrCandidates = listOfNotNull(face.hrEstimateBpm, finger.hrEstimateBpm)
        val heartRate = if (hrCandidates.isNotEmpty()) hrCandidates.average() else 0.0
        val snrValues = listOfNotNull(face.snrDb, finger.snrDb)
        val snrAverage = if (snrValues.isNotEmpty()) snrValues.average() else 0.0
        val processedData = floatArrayOf(
            face.sparkline.lastOrNull()?.toFloat() ?: 0f,
            finger.sparkline.lastOrNull()?.toFloat() ?: 0f
        )
        return com.vivopulse.signal.ProcessedSignal(
            heartRate = heartRate.toFloat(),
            signalQuality = averageQuality(face.status, finger.status),
            timestamp = updatedAtMs,
            processedData = processedData,
            faceMotionRms = face.motionRmsPx ?: 0.0,
            fingerSaturationPct = finger.saturationPct ?: 0.0,
            snrDb = snrAverage,
            faceSqi = statusToScore(face.status),
            fingerSqi = statusToScore(finger.status),
            goodSync = hrAgreementDeltaBpm?.let { it <= 5.0 } ?: false,
            consensusPtt = null
        )
    }

    private fun averageQuality(vararg statuses: QualityStatus): Float {
        if (statuses.isEmpty()) return 0f
        val score = statuses.map {
            when (it) {
                QualityStatus.GREEN -> 1.0
                QualityStatus.YELLOW -> 0.6
                QualityStatus.RED -> 0.2
            }
        }.average()
        return score.toFloat()
    }

    private fun statusToScore(status: QualityStatus): Int {
        return when (status) {
            QualityStatus.GREEN -> 90
            QualityStatus.YELLOW -> 60
            QualityStatus.RED -> 20
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        cameraController.release()
    }
}

