package com.vivopulse.feature.processing.correlation

import kotlin.math.max
import kotlin.math.min

/**
 * Adaptive correlation window selector.
 * 
 * Selects correlation window size (10-20s) based on:
 * - Motion level (more motion = shorter window)
 * - Signal SNR (higher SNR = can use longer window)
 */
class AdaptiveCorrelationWindow {
    
    companion object {
        const val MIN_WINDOW_S = 10.0
        const val MAX_WINDOW_S = 20.0
        const val DEFAULT_WINDOW_S = 15.0
    }
    
    /**
     * Compute adaptive window size.
     * 
     * @param motionPercentage Percentage of frames with high motion (0-100)
     * @param snrDb Signal SNR in dB
     * @param availableDurationS Available signal duration
     * @return Window size in seconds
     */
    fun computeWindowSize(
        motionPercentage: Double,
        snrDb: Double,
        availableDurationS: Double
    ): Double {
        // Start with default
        var windowSize = DEFAULT_WINDOW_S
        
        // Adjust for motion (more motion = shorter window to avoid bias)
        // 0-10% motion: no change
        // 10-30% motion: reduce by 0-5s
        // >30% motion: use minimum
        when {
            motionPercentage > 30 -> windowSize = MIN_WINDOW_S
            motionPercentage > 10 -> {
                val reduction = (motionPercentage - 10) / 4.0 // 0-5s reduction
                windowSize -= reduction
            }
        }
        
        // Adjust for SNR (higher SNR = can use longer window)
        // SNR > 15 dB: increase by 2s
        // SNR 10-15 dB: no change
        // SNR < 10 dB: decrease by 2s
        when {
            snrDb > 15 -> windowSize += 2.0
            snrDb < 10 -> windowSize -= 2.0
        }
        
        // Constrain to min/max and available duration
        windowSize = max(MIN_WINDOW_S, min(MAX_WINDOW_S, windowSize))
        windowSize = min(windowSize, availableDurationS)
        
        return windowSize
    }
    
    /**
     * Compute recommended number of overlapping windows.
     * 
     * @param totalDurationS Total signal duration
     * @param windowSizeS Window size
     * @param overlapPercent Overlap percentage (0-75)
     * @return Number of windows
     */
    fun computeWindowCount(
        totalDurationS: Double,
        windowSizeS: Double,
        overlapPercent: Double = 50.0
    ): Int {
        val step = windowSizeS * (1.0 - overlapPercent / 100.0)
        val count = ((totalDurationS - windowSizeS) / step).toInt() + 1
        return max(1, count)
    }
    
    /**
     * Generate window specifications.
     * 
     * @param totalDurationS Total signal duration
     * @param windowSizeS Window size
     * @param overlapPercent Overlap percentage
     * @return List of window specifications (start time, end time)
     */
    fun generateWindows(
        totalDurationS: Double,
        windowSizeS: Double,
        overlapPercent: Double = 50.0
    ): List<WindowSpec> {
        val windows = mutableListOf<WindowSpec>()
        val step = windowSizeS * (1.0 - overlapPercent / 100.0)
        
        var startTime = 0.0
        while (startTime + windowSizeS <= totalDurationS) {
            windows.add(
                WindowSpec(
                    startTimeS = startTime,
                    endTimeS = startTime + windowSizeS,
                    durationS = windowSizeS
                )
            )
            startTime += step
        }
        
        // Add final window if there's remaining time
        if (windows.isEmpty() || startTime < totalDurationS) {
            val remainingDuration = totalDurationS - startTime
            if (remainingDuration >= MIN_WINDOW_S) {
                windows.add(
                    WindowSpec(
                        startTimeS = startTime,
                        endTimeS = totalDurationS,
                        durationS = remainingDuration
                    )
                )
            }
        }
        
        return windows
    }
}

/**
 * Window specification.
 */
data class WindowSpec(
    val startTimeS: Double,
    val endTimeS: Double,
    val durationS: Double
)

