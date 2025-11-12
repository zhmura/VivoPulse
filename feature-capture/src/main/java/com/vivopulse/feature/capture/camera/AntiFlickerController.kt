package com.vivopulse.feature.capture.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.ImageCapture

/**
 * Anti-flicker controller to minimize banding from artificial lighting.
 * 
 * Detects regional power frequency and sets camera anti-banding mode.
 */
class AntiFlickerController {
    
    private val tag = "AntiFlickerController"
    
    /**
     * Anti-flicker mode.
     */
    enum class FlickerMode {
        HZ_50,    // 50 Hz (Europe, Asia, Africa, Australia)
        HZ_60,    // 60 Hz (North America, parts of South America, Japan, Taiwan)
        AUTO      // Auto-detect based on locale
    }
    
    /**
     * Get recommended flicker mode based on locale.
     */
    fun getRecommendedMode(): FlickerMode {
        val locale = java.util.Locale.getDefault()
        val country = locale.country
        
        // 60 Hz countries
        val hz60Countries = setOf(
            "US", "CA", "MX", // North America
            "BR", "CO", "VE", // South America (partial)
            "JP", "TW", "KR", "PH" // Asia (partial)
        )
        
        return if (hz60Countries.contains(country)) {
            FlickerMode.HZ_60
        } else {
            FlickerMode.HZ_50
        }
    }
    
    /**
     * Apply anti-flicker mode to camera builder.
     */
    fun applyAntiFlicker(
        builder: ImageCapture.Builder,
        mode: FlickerMode = FlickerMode.AUTO
    ) {
        val effectiveMode = if (mode == FlickerMode.AUTO) {
            getRecommendedMode()
        } else {
            mode
        }
        
        val antibandingMode = when (effectiveMode) {
            FlickerMode.HZ_50 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
            FlickerMode.HZ_60 -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
            FlickerMode.AUTO -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        }
        
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                antibandingMode
            )
        
        Log.d(tag, "Applied anti-flicker mode: $effectiveMode -> $antibandingMode")
    }
    
    /**
     * Check if device supports anti-banding modes.
     */
    fun getAvailableModes(characteristics: CameraCharacteristics): Set<Int> {
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
        return modes?.toSet() ?: emptySet()
    }
}

