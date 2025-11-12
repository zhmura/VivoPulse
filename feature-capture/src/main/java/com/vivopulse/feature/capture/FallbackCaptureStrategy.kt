package com.vivopulse.feature.capture

import android.content.Context
import android.util.Log
import android.util.Size
import com.vivopulse.feature.capture.device.CaptureMode

/**
 * Graceful fallback strategy for camera configuration failures.
 * 
 * Tries in order:
 * 1. Full concurrent @ preferred resolution (1280x720 or higher)
 * 2. Concurrent @ downgraded resolution (640x480)
 * 3. Sequential capture (face first, then finger)
 */
class FallbackCaptureStrategy(private val context: Context) {
    
    private val tag = "FallbackStrategy"
    
    /**
     * Fallback level enum.
     */
    enum class FallbackLevel {
        NONE,              // Original configuration
        DOWNGRADED_RES,    // Lower resolution
        SEQUENTIAL,        // Sequential capture
        FAILED             // All fallbacks exhausted
    }
    
    private var currentLevel = FallbackLevel.NONE
    
    /**
     * Get next fallback configuration.
     * 
     * @return Pair<CaptureMode, Size?> or null if no more fallbacks
     */
    fun getNextFallback(): FallbackConfig? {
        currentLevel = when (currentLevel) {
            FallbackLevel.NONE -> FallbackLevel.DOWNGRADED_RES
            FallbackLevel.DOWNGRADED_RES -> FallbackLevel.SEQUENTIAL
            FallbackLevel.SEQUENTIAL -> FallbackLevel.FAILED
            FallbackLevel.FAILED -> return null
        }
        
        Log.w(tag, "Applying fallback: $currentLevel")
        
        return when (currentLevel) {
            FallbackLevel.NONE -> FallbackConfig(
                mode = CaptureMode.CONCURRENT,
                resolution = Size(1280, 720),
                message = "Full concurrent mode"
            )
            FallbackLevel.DOWNGRADED_RES -> FallbackConfig(
                mode = CaptureMode.DOWNGRADED_RES,
                resolution = Size(640, 480),
                message = "Concurrent mode with reduced resolution"
            )
            FallbackLevel.SEQUENTIAL -> FallbackConfig(
                mode = CaptureMode.SEQUENTIAL,
                resolution = null,
                message = "Sequential capture mode (safe mode)"
            )
            FallbackLevel.FAILED -> null
        }
    }
    
    /**
     * Reset to initial state.
     */
    fun reset() {
        currentLevel = FallbackLevel.NONE
    }
    
    /**
     * Get current fallback level.
     */
    fun getCurrentLevel(): FallbackLevel = currentLevel
}

/**
 * Fallback configuration.
 */
data class FallbackConfig(
    val mode: CaptureMode,
    val resolution: Size?,
    val message: String
)



