package com.vivopulse.app.util

import android.os.Build

/**
 * Device whitelist utility for VivoPulse.
 * Only specific devices are supported for optimal dual-camera capture and signal processing.
 */
object DeviceWhitelist {
    
    private val SUPPORTED_DEVICES = setOf(
        // Google Pixel devices
        "Pixel 6" to "Google",
        "Pixel 6 Pro" to "Google",
        "Pixel 6a" to "Google",
        "Pixel 7" to "Google",
        "Pixel 7 Pro" to "Google",
        "Pixel 7a" to "Google",
        "Pixel 8" to "Google",
        "Pixel 8 Pro" to "Google",
        "Pixel 8a" to "Google",
        
        // Samsung Galaxy S22 series
        "SM-S901" to "samsung", // S22
        "SM-S906" to "samsung", // S22+
        "SM-S908" to "samsung", // S22 Ultra
        
        // Samsung Galaxy S23 series
        "SM-S911" to "samsung", // S23
        "SM-S916" to "samsung", // S23+
        "SM-S918" to "samsung", // S23 Ultra
        
        // Samsung Galaxy S24 series
        "SM-S921" to "samsung", // S24
        "SM-S926" to "samsung", // S24+
        "SM-S928" to "samsung"  // S24 Ultra
    )
    
    /**
     * Check if the current device is supported.
     * @return true if the device is in the whitelist, false otherwise
     */
    fun isDeviceSupported(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL
        
        return SUPPORTED_DEVICES.any { (deviceModel, deviceManufacturer) ->
            manufacturer.contains(deviceManufacturer.lowercase()) && 
            model.contains(deviceModel, ignoreCase = true)
        }
    }
    
    /**
     * Get the current device information as a string.
     * @return Device manufacturer and model
     */
    fun getDeviceInfo(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }
    
    /**
     * Get a list of all supported device names for display purposes.
     * @return List of supported device names
     */
    fun getSupportedDevicesList(): List<String> {
        return listOf(
            "Google Pixel 6, 6 Pro, 6a",
            "Google Pixel 7, 7 Pro, 7a",
            "Google Pixel 8, 8 Pro, 8a",
            "Samsung Galaxy S22, S22+, S22 Ultra",
            "Samsung Galaxy S23, S23+, S23 Ultra",
            "Samsung Galaxy S24, S24+, S24 Ultra"
        )
    }
}


