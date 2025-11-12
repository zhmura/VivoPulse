package com.vivopulse.feature.processing.timestamp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors timestamp drift between dual camera streams.
 */
class DriftMonitor {
    private val frontTimestamps = mutableListOf<Long>()
    private val backTimestamps = mutableListOf<Long>()
    
    private val _driftMsPerSecond = MutableStateFlow(0.0)
    val driftMsPerSecond: StateFlow<Double> = _driftMsPerSecond.asStateFlow()
    
    private val _isValid = MutableStateFlow(false)
    val isValid: StateFlow<Boolean> = _isValid.asStateFlow()
    
    private val maxTimestamps = 300 // Keep last 10 seconds at 30fps
    
    /**
     * Add a timestamp from the front camera.
     */
    @Synchronized
    fun addFrontTimestamp(timestampNs: Long) {
        frontTimestamps.add(timestampNs)
        if (frontTimestamps.size > maxTimestamps) {
            frontTimestamps.removeAt(0)
        }
        updateDrift()
    }
    
    /**
     * Add a timestamp from the back camera.
     */
    @Synchronized
    fun addBackTimestamp(timestampNs: Long) {
        backTimestamps.add(timestampNs)
        if (backTimestamps.size > maxTimestamps) {
            backTimestamps.removeAt(0)
        }
        updateDrift()
    }
    
    /**
     * Reset all timestamps.
     */
    @Synchronized
    fun reset() {
        frontTimestamps.clear()
        backTimestamps.clear()
        _driftMsPerSecond.value = 0.0
        _isValid.value = false
    }
    
    /**
     * Update drift calculation.
     */
    private fun updateDrift() {
        if (frontTimestamps.size < 30 || backTimestamps.size < 30) {
            _isValid.value = false
            return
        }
        
        val result = TimestampSync.computeDrift(
            frontTimestamps,
            backTimestamps,
            windowSizeMs = 5000
        )
        
        _driftMsPerSecond.value = result.driftMsPerSecond
        _isValid.value = result.isValid
    }
}

