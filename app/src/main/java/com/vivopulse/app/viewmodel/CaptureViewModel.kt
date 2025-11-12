package com.vivopulse.app.viewmodel

import android.app.Application
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.vivopulse.app.util.ErrorHandler
import com.vivopulse.feature.capture.DualCameraController
import com.vivopulse.feature.capture.RecordingResult
import com.vivopulse.feature.capture.model.Frame
import com.vivopulse.feature.capture.roi.FaceRoi
import com.vivopulse.feature.processing.timestamp.DriftMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    
    fun setRecordingResult(result: RecordingResult) {
        _lastRecordingResult.value = result
    }
    
    fun clearRecordingResult() {
        _lastRecordingResult.value = null
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
    }

    fun getCameraController(): DualCameraController = cameraController

    fun startRecording() {
        if (_isRecording.value) return

        recordingStartTime = System.currentTimeMillis()
        _recordingDuration.value = 0L
        _isRecording.value = true
        driftMonitor.reset()
        cameraController.startRecording()
    }

    fun stopRecording() {
        if (!_isRecording.value) return

        _isRecording.value = false
        val result = cameraController.stopRecording()
        _lastRecordingResult.value = result
        _recordingDuration.value = 0L
        
        // Share recording result with other ViewModels
        SharedRecordingState.setRecordingResult(result)
    }

    fun toggleTorch() {
        val newState = !_torchEnabled.value
        _torchEnabled.value = newState
        cameraController.setTorchEnabled(newState)
        torchEnabledAt = if (newState) System.currentTimeMillis() else null
    }

    fun isConcurrentCameraSupported(): Boolean {
        return cameraController.isConcurrentCameraSupported()
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.release()
    }
}

