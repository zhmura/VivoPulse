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
        
        // Compute adaptive threshold (current method)
        val mean = signal.average()
        val std = sqrt(signal.map { (it - mean).pow(2) }.average())
        val threshold = mean + thresholdFactor * std
        
        // P3-C DIAGNOSTIC: Compute MAD-based threshold for comparison
        val sorted = signal.sorted()
        val median = sorted[sorted.size / 2]
        val mad = sorted.map { kotlin.math.abs(it - median) }.sorted()[sorted.size / 2]
        val madThreshold = median + 2.0 * 1.4826 * mad  // 1.4826 = MAD→std conversion factor
        android.util.Log.i("PeakDetect", "PEAK_DIAG | mean+0.3*std=${"%.4f".format(threshold)} | " +
              "MAD-based=${"%.4f".format(madThreshold)} | delta=${"%.4f".format(madThreshold - threshold)} | " +
              "mean=${"%.4f".format(mean)} std=${"%.4f".format(std)} median=${"%.4f".format(median)} mad=${"%.4f".format(mad)}")
        
        // Minimum distance between peaks (samples)
        val minDistanceSamples = (MIN_RR_MS / 1000.0 * fsHz).toInt()
        
        val peaks = mutableListOf<Int>()
        var lastPeakIndex = -minDistanceSamples
        
        // Find local maxima above threshold (current method)
        for (i in 1 until signal.size - 1) {
            if (signal[i] > signal[i - 1] &&
                signal[i] > signal[i + 1] &&
                signal[i] > threshold &&
                (i - lastPeakIndex) >= minDistanceSamples) {
                
                peaks.add(i)
                lastPeakIndex = i
            }
        }
        
        // P3-C DIAGNOSTIC: Count how many peaks MAD threshold would detect
        val madPeaks = mutableListOf<Int>()
        var madLastPeak = -minDistanceSamples
        for (i in 1 until signal.size - 1) {
            if (signal[i] > signal[i - 1] &&
                signal[i] > signal[i + 1] &&
                signal[i] > madThreshold &&
                (i - madLastPeak) >= minDistanceSamples) {
                madPeaks.add(i)
                madLastPeak = i
            }
        }
        
        // P3-C DIAGNOSTIC: Compute prominence for detected peaks
        if (peaks.isNotEmpty()) {
            val prominences = peaks.map { idx ->
                val leftMin = (maxOf(0, idx - minDistanceSamples) until idx).minOfOrNull { signal[it] } ?: signal[idx]
                val rightMin = ((idx + 1)..minOf(signal.size - 1, idx + minDistanceSamples)).minOfOrNull { signal[it] } ?: signal[idx]
                signal[idx] - maxOf(leftMin, rightMin)
            }
            val medianProm = prominences.sorted()[prominences.size / 2]
            val lowPromCount = prominences.count { it < 0.3 * medianProm }
            android.util.Log.i("PeakDetect", "PEAK_DIAG | currentPeaks=${peaks.size} madPeaks=${madPeaks.size} | " +
                  "prominences: median=${"%.4f".format(medianProm)} lowPromCount=$lowPromCount (${if (lowPromCount > 0) "would reject $lowPromCount" else "all good"})")
        } else {
            android.util.Log.i("PeakDetect", "PEAK_DIAG | currentPeaks=0 madPeaks=${madPeaks.size}")
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

