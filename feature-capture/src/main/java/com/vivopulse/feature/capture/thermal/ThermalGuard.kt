package com.vivopulse.feature.capture.thermal

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thermal guard to protect device from overheating during extended capture.
 * 
 * Monitors thermal state and throttles/stops capture if device gets too hot.
 */
class ThermalGuard(private val context: Context) {
    
    private val tag = "ThermalGuard"
    
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    
    private val _thermalState = MutableStateFlow(ThermalState.NORMAL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()
    
    private val _throttleRecommended = MutableStateFlow(false)
    val throttleRecommended: StateFlow<Boolean> = _throttleRecommended.asStateFlow()
    
    private val _stopRecommended = MutableStateFlow(false)
    val stopRecommended: StateFlow<Boolean> = _stopRecommended.asStateFlow()
    
    private var thermalStatusListener: PowerManager.OnThermalStatusChangedListener? = null
    
    /**
     * Start monitoring thermal state.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun startMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(tag, "Thermal monitoring not available on API < 29")
            return
        }
        
        thermalStatusListener = PowerManager.OnThermalStatusChangedListener { status ->
            updateThermalState(status)
        }
        
        powerManager.addThermalStatusListener(thermalStatusListener!!)
        
        // Get initial state
        updateThermalState(powerManager.currentThermalStatus)
        
        Log.d(tag, "Thermal monitoring started")
    }
    
    /**
     * Stop monitoring thermal state.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun stopMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        
        thermalStatusListener?.let {
            powerManager.removeThermalStatusListener(it)
        }
        thermalStatusListener = null
        
        Log.d(tag, "Thermal monitoring stopped")
    }
    
    /**
     * Update thermal state based on PowerManager status.
     */
    private fun updateThermalState(status: Int) {
        val state = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalState.NORMAL
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.SHUTDOWN
            else -> ThermalState.NORMAL
        }
        
        _thermalState.value = state
        
        // Update recommendations
        _throttleRecommended.value = status >= PowerManager.THERMAL_STATUS_MODERATE
        _stopRecommended.value = status >= PowerManager.THERMAL_STATUS_SEVERE
        
        Log.d(tag, "Thermal state: $state (status=$status)")
        
        if (_stopRecommended.value) {
            Log.w(tag, "STOP RECOMMENDED: Device thermal state is $state")
        } else if (_throttleRecommended.value) {
            Log.w(tag, "THROTTLE RECOMMENDED: Device thermal state is $state")
        }
    }
    
    /**
     * Get current thermal state (for API < 29, always NORMAL).
     */
    fun getCurrentState(): ThermalState {
        return _thermalState.value
    }
    
    /**
     * Check if throttling is recommended.
     */
    fun shouldThrottle(): Boolean {
        return _throttleRecommended.value
    }
    
    /**
     * Check if capture should be stopped.
     */
    fun shouldStop(): Boolean {
        return _stopRecommended.value
    }
}

/**
 * Thermal state enum.
 */
enum class ThermalState {
    NORMAL,      // No throttling needed
    LIGHT,       // Light throttling recommended
    MODERATE,    // Moderate throttling needed
    SEVERE,      // Severe throttling or stop recommended
    CRITICAL,    // Critical - stop immediately
    EMERGENCY,   // Emergency - shut down
    SHUTDOWN     // Shutdown imminent
}

