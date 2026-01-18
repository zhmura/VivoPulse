package com.vivopulse.app.manager

import com.vivopulse.feature.capture.RecordingResult
import com.vivopulse.feature.processing.ProcessedSeries
import com.vivopulse.signal.ProcessedSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * centralized manager for active measurement session state.
 * Replaces ad-hoc SharedRecordingState communication.
 */
@Singleton
class SessionManager @Inject constructor() {

    private val _lastRecordingResult = MutableStateFlow<RecordingResult?>(null)
    val lastRecordingResult: StateFlow<RecordingResult?> = _lastRecordingResult.asStateFlow()

    private val _lastProcessedSignals = MutableStateFlow<List<ProcessedSignal>>(emptyList())
    val lastProcessedSignals: StateFlow<List<ProcessedSignal>> = _lastProcessedSignals.asStateFlow()
    
    // Hold the final processing result for the session
    private val _sessionResult = MutableStateFlow<ProcessedSeries?>(null)
    val sessionResult: StateFlow<ProcessedSeries?> = _sessionResult.asStateFlow()

    /**
     * Start a new session. Clears previous data.
     */
    fun startNewSession() {
        _lastRecordingResult.value = null
        _lastProcessedSignals.value = emptyList()
        _sessionResult.value = null
    }

    /**
     * Complete the recording phase.
     */
    fun onRecordingComplete(result: RecordingResult, signals: List<ProcessedSignal>) {
        _lastRecordingResult.value = result
        _lastProcessedSignals.value = signals
    }
    
    /**
     * Save the processed result for this session.
     */
    fun onProcessingComplete(result: ProcessedSeries) {
        _sessionResult.value = result
    }

    /**
     * Clear all session data.
     */
    fun clearSession() {
        _lastRecordingResult.value = null
        _lastProcessedSignals.value = emptyList()
        _sessionResult.value = null
    }
}
