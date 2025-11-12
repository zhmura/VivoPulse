package com.vivopulse.feature.capture.device

import android.os.Build

/**
 * Device whitelist for dual-camera concurrent capture.
 * 
 * Only whitelisted devices are guaranteed to support stable concurrent camera access
 * with the required frame rates and minimal drift.
 */
object DeviceWhitelist {
    
    /**
     * Whitelisted device models.
     * Format: "MANUFACTURER:MODEL"
     */
    private val WHITELISTED_DEVICES = setOf(
        // Google Pixel
        "Google:Pixel 7",
        "Google:Pixel 7 Pro",
        "Google:Pixel 7a",
        "Google:Pixel 8",
        "Google:Pixel 8 Pro",
        "Google:Pixel 8a",
        "Google:Pixel 9",
        "Google:Pixel 9 Pro",
        "Google:Pixel 9 Pro XL",
        
        // Samsung Galaxy S
        "samsung:SM-S901B",  // S22
        "samsung:SM-S906B",  // S22+
        "samsung:SM-S908B",  // S22 Ultra
        "samsung:SM-S911B",  // S23
        "samsung:SM-S916B",  // S23+
        "samsung:SM-S918B",  // S23 Ultra
        "samsung:SM-S921B",  // S24
        "samsung:SM-S926B",  // S24+
        "samsung:SM-S928B"   // S24 Ultra
    )
    
    /**
     * Check if current device is whitelisted.
     */
    fun isDeviceWhitelisted(): Boolean {
        val deviceKey = "${Build.MANUFACTURER}:${Build.MODEL}"
        return WHITELISTED_DEVICES.contains(deviceKey)
    }
    
    /**
     * Get current device identifier.
     */
    fun getCurrentDeviceId(): String {
        return "${Build.MANUFACTURER}:${Build.MODEL}"
    }
    
    /**
     * Get device category for analytics.
     */
    fun getDeviceCategory(): DeviceCategory {
        return when {
            isDeviceWhitelisted() -> DeviceCategory.WHITELISTED
            Build.MANUFACTURER.equals("Google", ignoreCase = true) -> DeviceCategory.PIXEL_OTHER
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) -> DeviceCategory.SAMSUNG_OTHER
            else -> DeviceCategory.UNSUPPORTED
        }
    }
    
    /**
     * Get warning message for non-whitelisted devices.
     */
    fun getWarningMessage(): String? {
        if (isDeviceWhitelisted()) return null
        
        return when (getDeviceCategory()) {
            DeviceCategory.PIXEL_OTHER -> 
                "This Pixel model hasn't been fully tested. Results may vary."
            DeviceCategory.SAMSUNG_OTHER -> 
                "This Samsung model hasn't been fully tested. Results may vary."
            DeviceCategory.UNSUPPORTED -> 
                "This device (${getCurrentDeviceId()}) hasn't been tested. Dual-camera capture may not work reliably."
            DeviceCategory.WHITELISTED -> null
        }
    }
}

/**
 * Device category for telemetry.
 */
enum class DeviceCategory {
    WHITELISTED,
    PIXEL_OTHER,
    SAMSUNG_OTHER,
    UNSUPPORTED
}

