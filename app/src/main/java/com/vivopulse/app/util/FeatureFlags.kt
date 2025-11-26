package com.vivopulse.app.util

import com.vivopulse.app.BuildConfig

/**
 * Feature flags for VivoPulse.
 * Controls experimental and debug features.
 */
object FeatureFlags {
    
    private var simulatedModeEnabled: Boolean = false
    private var walkingModeEnabled: Boolean = false
    
    const val ENABLE_TF_EXPORT = true // Enable TF export for validation phase
    
    /**
     * Check if walking mode is enabled.
     */
    fun isWalkingModeEnabled(): Boolean = walkingModeEnabled
    
    /**
     * Set walking mode.
     */
    fun setWalkingModeEnabled(enabled: Boolean) {
        walkingModeEnabled = enabled
    }
    
    /**
     * Check if simulated mode is enabled.
     * Simulated mode generates synthetic PPG signals for testing without cameras.
     * 
     * @return true if simulated mode is enabled
     */
    fun isSimulatedModeEnabled(): Boolean {
        return simulatedModeEnabled && BuildConfig.ENABLE_SIMULATED_MODE
    }
    
    /**
     * Enable or disable simulated mode.
     * Only available in debug builds.
     * 
     * @param enabled true to enable simulated mode
     */
    fun setSimulatedModeEnabled(enabled: Boolean) {
        if (BuildConfig.ENABLE_SIMULATED_MODE) {
            simulatedModeEnabled = enabled
        }
    }
    
    /**
     * Check if the app is running in debug mode.
     * @return true if debug build
     */
    fun isDebugBuild(): Boolean {
        return BuildConfig.DEBUG
    }
    
    /**
     * Check if device whitelist enforcement is enabled.
     * Can be disabled for testing on unsupported devices.
     * 
     * @return true if whitelist should be enforced
     */
    fun enforceDeviceWhitelist(): Boolean {
        // In debug builds, we can optionally disable whitelist enforcement
        return !BuildConfig.DEBUG || !simulatedModeEnabled
    }
}


