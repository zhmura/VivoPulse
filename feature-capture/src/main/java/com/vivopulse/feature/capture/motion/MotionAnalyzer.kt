package com.vivopulse.feature.capture.motion

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Motion analyzer for optical flow-based motion detection.
 * 
 * Tracks motion magnitude to determine when signal windows should be masked
 * due to excessive movement that corrupts PPG signals.
 */
class MotionAnalyzer {
    
    companion object {
        const val LOW_MOTION_THRESHOLD_PX = 2.0    // <2 pixels = stable
        const val MODERATE_MOTION_THRESHOLD_PX = 5.0  // 2-5 pixels = acceptable
        const val HIGH_MOTION_THRESHOLD_PX = 10.0  // >10 pixels = mask window
    }
    
    private val flowHistory = mutableListOf<FlowMeasurement>()
    private val maxHistorySize = 300 // 10 seconds at 30 fps
    
    /**
     * Add optical flow measurement.
     * 
     * @param timestampMs Timestamp in milliseconds
     * @param flowMagnitude Flow magnitude in pixels
     */
    fun addMeasurement(timestampMs: Long, flowMagnitude: Double) {
        flowHistory.add(FlowMeasurement(timestampMs, flowMagnitude))
        
        // Keep history size manageable
        while (flowHistory.size > maxHistorySize) {
            flowHistory.removeAt(0)
        }
    }
    
    /**
     * Get current motion level.
     */
    fun getCurrentMotionLevel(): MotionLevel {
        if (flowHistory.isEmpty()) return MotionLevel.UNKNOWN
        
        val recentFlow = flowHistory.takeLast(10).map { it.magnitude }.average()
        
        return when {
            recentFlow < LOW_MOTION_THRESHOLD_PX -> MotionLevel.STABLE
            recentFlow < MODERATE_MOTION_THRESHOLD_PX -> MotionLevel.LOW
            recentFlow < HIGH_MOTION_THRESHOLD_PX -> MotionLevel.MODERATE
            else -> MotionLevel.HIGH
        }
    }
    
    /**
     * Compute motion windows that should be masked.
     * 
     * @param windowSizeMs Window size in milliseconds (default 1000ms)
     * @param maskThreshold Motion threshold for masking (pixels)
     * @return List of time windows (start, end) that should be masked
     */
    fun computeMaskedWindows(
        windowSizeMs: Long = 1000,
        maskThreshold: Double = HIGH_MOTION_THRESHOLD_PX
    ): List<TimeWindow> {
        if (flowHistory.isEmpty()) return emptyList()
        
        val maskedWindows = mutableListOf<TimeWindow>()
        
        val startTime = flowHistory.first().timestampMs
        val endTime = flowHistory.last().timestampMs
        
        var currentTime = startTime
        while (currentTime <= endTime) {
            val windowEnd = currentTime + windowSizeMs
            
            // Get measurements in this window
            val windowMeasurements = flowHistory.filter { 
                it.timestampMs >= currentTime && it.timestampMs < windowEnd
            }
            
            if (windowMeasurements.isNotEmpty()) {
                val avgFlow = windowMeasurements.map { it.magnitude }.average()
                
                if (avgFlow >= maskThreshold) {
                    maskedWindows.add(TimeWindow(currentTime, windowEnd, avgFlow))
                }
            }
            
            currentTime = windowEnd
        }
        
        return maskedWindows
    }
    
    /**
     * Get motion statistics for entire capture.
     */
    fun getStatistics(): MotionStatistics {
        if (flowHistory.isEmpty()) {
            return MotionStatistics(
                avgFlowMagnitude = 0.0,
                maxFlowMagnitude = 0.0,
                motionPercentage = 0.0,
                stablePercentage = 0.0
            )
        }
        
        val magnitudes = flowHistory.map { it.magnitude }
        val avg = magnitudes.average()
        val max = magnitudes.maxOrNull() ?: 0.0
        
        val highMotionCount = magnitudes.count { it >= HIGH_MOTION_THRESHOLD_PX }
        val stableCount = magnitudes.count { it < LOW_MOTION_THRESHOLD_PX }
        
        val total = magnitudes.size.toDouble()
        
        return MotionStatistics(
            avgFlowMagnitude = avg,
            maxFlowMagnitude = max,
            motionPercentage = (highMotionCount / total) * 100.0,
            stablePercentage = (stableCount / total) * 100.0
        )
    }
    
    /**
     * Reset analyzer.
     */
    fun reset() {
        flowHistory.clear()
    }
}

/**
 * Optical flow measurement.
 */
data class FlowMeasurement(
    val timestampMs: Long,
    val magnitude: Double
)

/**
 * Time window to be masked.
 */
data class TimeWindow(
    val startMs: Long,
    val endMs: Long,
    val avgMotion: Double
)

/**
 * Motion level enum.
 */
enum class MotionLevel {
    UNKNOWN,
    STABLE,      // <2 pixels
    LOW,         // 2-5 pixels
    MODERATE,    // 5-10 pixels
    HIGH         // >10 pixels
}

/**
 * Motion statistics.
 */
data class MotionStatistics(
    val avgFlowMagnitude: Double,
    val maxFlowMagnitude: Double,
    val motionPercentage: Double,    // % of frames with high motion
    val stablePercentage: Double     // % of frames with stable ROI
)

