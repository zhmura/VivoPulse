package com.vivopulse.feature.processing.ptt

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Adaptive peak detector for PPG signals.
 * 
 * Enforces physiological constraints (min RR ≥350ms) and uses adaptive thresholding.
 */
object PeakDetect {
    
    const val MIN_RR_MS = 350.0  // Minimum R-R interval (≥170 bpm max physiological)
    const val MAX_RR_MS = 2000.0 // Maximum R-R interval (≥30 bpm min physiological)
    
    /**
     * Detect peaks in PPG signal with adaptive thresholding.
     * 
     * Algorithm:
     * 1. Compute adaptive threshold = mean + k*std (k ≈ 0.3-0.5)
     * 2. Find local maxima above threshold
     * 3. Enforce minimum distance constraint (≥350ms between peaks)
     * 
     * @param signal Input signal (filtered, normalized)
     * @param fsHz Sample rate in Hz
     * @param thresholdFactor Threshold multiplier (default 0.3)
     * @return PeakDetectResult with peak indices and times
     */
    fun detectPeaks(
        signal: DoubleArray,
        fsHz: Double,
        thresholdFactor: Double = 0.3
    ): PeakDetectResult {
        if (signal.size < 3) {
            return PeakDetectResult(
                indices = intArrayOf(),
                timesMs = doubleArrayOf(),
                rrIntervalsMs = doubleArrayOf(),
                isValid = false,
                message = "Signal too short"
            )
        }
        
        // Compute adaptive threshold
        val mean = signal.average()
        val std = sqrt(signal.map { (it - mean).pow(2) }.average())
        val threshold = mean + thresholdFactor * std
        
        // Minimum distance between peaks (samples)
        val minDistanceSamples = (MIN_RR_MS / 1000.0 * fsHz).toInt()
        
        val peaks = mutableListOf<Int>()
        var lastPeakIndex = -minDistanceSamples
        
        // Find local maxima above threshold
        for (i in 1 until signal.size - 1) {
            if (signal[i] > signal[i - 1] &&
                signal[i] > signal[i + 1] &&
                signal[i] > threshold &&
                (i - lastPeakIndex) >= minDistanceSamples) {
                
                peaks.add(i)
                lastPeakIndex = i
            }
        }
        
        if (peaks.isEmpty()) {
            return PeakDetectResult(
                indices = intArrayOf(),
                timesMs = doubleArrayOf(),
                rrIntervalsMs = doubleArrayOf(),
                isValid = false,
                message = "No peaks detected"
            )
        }
        
        // Compute peak times (ms)
        val timesMs = peaks.map { it * 1000.0 / fsHz }.toDoubleArray()
        
        // Compute RR intervals (ms)
        val rrIntervals = mutableListOf<Double>()
        for (i in 1 until peaks.size) {
            val rr = (peaks[i] - peaks[i - 1]) * 1000.0 / fsHz
            
            // Validate RR interval
            if (rr >= MIN_RR_MS && rr <= MAX_RR_MS) {
                rrIntervals.add(rr)
            }
        }
        
        return PeakDetectResult(
            indices = peaks.toIntArray(),
            timesMs = timesMs,
            rrIntervalsMs = rrIntervals.toDoubleArray(),
            isValid = peaks.size >= 3, // Need at least 3 peaks
            message = "Detected ${peaks.size} peaks"
        )
    }
    
    /**
     * Validate peak quality.
     * 
     * @param result Peak detection result
     * @return Quality score 0-100
     */
    fun assessPeakQuality(result: PeakDetectResult): Double {
        if (!result.isValid || result.rrIntervalsMs.isEmpty()) {
            return 0.0
        }
        
        // Regularity score (lower CV = higher score)
        val mean = result.rrIntervalsMs.average()
        val variance = result.rrIntervalsMs.map { (it - mean).pow(2) }.average()
        val std = sqrt(variance)
        val cv = if (mean > 0) std / mean else 1.0
        
        // Convert CV to quality score
        // CV 0.05 (5%) = excellent = 95
        // CV 0.10 (10%) = good = 80
        // CV 0.20 (20%) = fair = 60
        // CV > 0.40 (40%) = poor = 0
        val quality = maxOf(0.0, 100.0 * (1.0 - cv / 0.4))
        
        return quality
    }
}

/**
 * Peak detection result.
 */
data class PeakDetectResult(
    val indices: IntArray,          // Peak indices in signal
    val timesMs: DoubleArray,       // Peak times in milliseconds
    val rrIntervalsMs: DoubleArray, // R-R intervals in milliseconds
    val isValid: Boolean,
    val message: String = ""
) {
    /**
     * Get peak count.
     */
    fun getPeakCount(): Int = indices.size
    
    /**
     * Get mean RR interval.
     */
    fun getMeanRRMs(): Double {
        return if (rrIntervalsMs.isNotEmpty()) {
            rrIntervalsMs.average()
        } else {
            0.0
        }
    }
}

