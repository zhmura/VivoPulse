package com.vivopulse.feature.processing.motion

import kotlin.math.abs

/**
 * Motion mask processor for correlation and peak detection.
 * 
 * Masks windows with excessive motion to prevent corruption of PTT estimates.
 */
class MotionMaskProcessor {
    
    /**
     * Apply motion mask to signal.
     * 
     * Sets masked samples to NaN so they're excluded from correlation/peak detection.
     * 
     * @param signal Input signal
     * @param timeMs Time array (milliseconds) aligned with signal
     * @param maskedWindows List of time windows to mask
     * @return Masked signal (NaN in masked regions)
     */
    fun applyMask(
        signal: DoubleArray,
        timeMs: DoubleArray,
        maskedWindows: List<TimeWindow>
    ): DoubleArray {
        if (signal.size != timeMs.size) {
            throw IllegalArgumentException("Signal and time arrays must have same length")
        }
        
        val masked = signal.copyOf()
        
        for (window in maskedWindows) {
            for (i in masked.indices) {
                if (timeMs[i] >= window.startMs && timeMs[i] < window.endMs) {
                    masked[i] = Double.NaN
                }
            }
        }
        
        return masked
    }
    
    /**
     * Compute percentage of signal that is masked.
     */
    fun computeMaskedPercentage(maskedSignal: DoubleArray): Double {
        val maskedCount = maskedSignal.count { it.isNaN() }
        return (maskedCount.toDouble() / maskedSignal.size) * 100.0
    }
    
    /**
     * Extract valid (non-masked) segments for processing.
     * 
     * Returns list of continuous valid segments.
     */
    fun extractValidSegments(
        signal: DoubleArray,
        timeMs: DoubleArray,
        minSegmentLengthS: Double = 5.0
    ): List<SignalSegment> {
        if (signal.size != timeMs.size) return emptyList()
        
        val segments = mutableListOf<SignalSegment>()
        var segmentStart = -1
        
        for (i in signal.indices) {
            if (!signal[i].isNaN()) {
                if (segmentStart == -1) {
                    segmentStart = i
                }
            } else {
                if (segmentStart != -1) {
                    // End of segment
                    val duration = (timeMs[i - 1] - timeMs[segmentStart]) / 1000.0
                    if (duration >= minSegmentLengthS) {
                        segments.add(
                            SignalSegment(
                                startIdx = segmentStart,
                                endIdx = i - 1,
                                durationS = duration
                            )
                        )
                    }
                    segmentStart = -1
                }
            }
        }
        
        // Handle last segment
        if (segmentStart != -1) {
            val duration = (timeMs.last() - timeMs[segmentStart]) / 1000.0
            if (duration >= minSegmentLengthS) {
                segments.add(
                    SignalSegment(
                        startIdx = segmentStart,
                        endIdx = signal.size - 1,
                        durationS = duration
                    )
                )
            }
        }
        
        return segments
    }
    
    /**
     * Compute correlation with motion masking.
     * 
     * Uses only non-masked samples for correlation.
     */
    fun computeMaskedCorrelation(
        signal1: DoubleArray,
        signal2: DoubleArray
    ): Double {
        if (signal1.size != signal2.size) return 0.0
        
        // Extract valid pairs
        val validPairs = signal1.indices.filter { 
            !signal1[it].isNaN() && !signal2[it].isNaN()
        }.map { i -> Pair(signal1[i], signal2[i]) }
        
        if (validPairs.size < 10) return 0.0 // Need minimum samples
        
        // Compute Pearson correlation
        val x = validPairs.map { it.first }
        val y = validPairs.map { it.second }
        
        val meanX = x.average()
        val meanY = y.average()
        
        var sumXY = 0.0
        var sumXX = 0.0
        var sumYY = 0.0
        
        for (i in x.indices) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            sumXY += dx * dy
            sumXX += dx * dx
            sumYY += dy * dy
        }
        
        return if (sumXX > 1e-10 && sumYY > 1e-10) {
            sumXY / kotlin.math.sqrt(sumXX * sumYY)
        } else {
            0.0
        }
    }
}

/**
 * Signal segment (continuous valid region).
 */
data class SignalSegment(
    val startIdx: Int,
    val endIdx: Int,
    val durationS: Double
)

/**
 * Time window to be masked.
 */
data class TimeWindow(
    val startMs: Long,
    val endMs: Long,
    val avgMotion: Double
)

