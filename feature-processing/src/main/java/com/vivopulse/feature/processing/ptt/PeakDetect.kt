package com.vivopulse.feature.processing.ptt

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Adaptive peak detector for PPG signals.
 * 
 * P0.2 Research upgrade: Uses MAD-based robust threshold + prominence filtering
 * instead of fragile mean+k×std.
 * 
 * Algorithm:
 * 1. Compute MAD-based threshold: median + 2.0 × 1.4826 × MAD
 * 2. Find local maxima above threshold with minimum distance ≥350ms
 * 3. Filter by prominence: reject peaks with prominence < 30% of median prominence
 */
object PeakDetect {
    
    const val MIN_RR_MS = 350.0  // Minimum R-R interval (≥170 bpm max physiological)
    const val MAX_RR_MS = 2000.0 // Maximum R-R interval (≥30 bpm min physiological)
    
    /**
     * Detect peaks in PPG signal with MAD-based adaptive thresholding
     * and prominence-based filtering.
     * 
     * @param signal Input signal (filtered, normalized)
     * @param fsHz Sample rate in Hz
     * @param madFactor Multiplier for MAD threshold (default 2.0)
     * @return PeakDetectResult with peak indices and times
     */
    fun detectPeaks(
        signal: DoubleArray,
        fsHz: Double,
        @Suppress("UNUSED_PARAMETER") thresholdFactor: Double = 0.3 // kept for API compat
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
        
        // ── P0.2: MAD-based robust threshold ──
        // MAD is resistant to outliers (unlike std), preventing a single
        // large artifact from raising the threshold and hiding real peaks.
        val sorted = signal.sorted()
        val n = sorted.size
        val median = if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0 else sorted[n / 2]
        val mad = sorted.map { abs(it - median) }.sorted().let { s ->
            if (s.size % 2 == 0) (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 else s[s.size / 2]
        }
        // 1.4826 converts MAD to std-equivalent for Gaussian data
        // Factor of 0.5 puts threshold at ~69th percentile of signal
        // (above noise floor, below peaks). Prominence filter handles the rest.
        val madThreshold = median + 0.5 * 1.4826 * mad
        
        // Fallback: if MAD is zero (constant signal), use mean + tiny epsilon
        val threshold = if (mad > 1e-12) madThreshold else {
            val mean = signal.average()
            mean + 0.01 * (signal.maxOrNull()!! - signal.minOrNull()!!)
        }
        
        // Minimum distance between peaks (samples)
        val minDistanceSamples = (MIN_RR_MS / 1000.0 * fsHz).toInt()
        
        // ── Phase 1: Find candidate peaks (local maxima above threshold) ──
        val candidates = mutableListOf<Int>()
        var lastPeakIndex = -minDistanceSamples
        
        for (i in 1 until signal.size - 1) {
            if (signal[i] > signal[i - 1] &&
                signal[i] > signal[i + 1] &&
                signal[i] >= threshold &&
                (i - lastPeakIndex) >= minDistanceSamples) {
                
                candidates.add(i)
                lastPeakIndex = i
            }
        }
        
        if (candidates.isEmpty()) {
            return PeakDetectResult(
                indices = intArrayOf(),
                timesMs = doubleArrayOf(),
                rrIntervalsMs = doubleArrayOf(),
                isValid = false,
                message = "No peaks detected (MAD threshold=${String.format("%.4f", threshold)})"
            )
        }
        
        // ── Phase 2: Prominence-based filtering ──
        // Prominence = peak height - max(left trough, right trough)
        // Reject peaks with prominence < 30% of median prominence.
        // This removes noise spikes that pass the threshold but are not real beats.
        val prominences = candidates.map { idx ->
            val leftMin = (maxOf(0, idx - minDistanceSamples) until idx)
                .minOfOrNull { signal[it] } ?: signal[idx]
            val rightMin = ((idx + 1)..minOf(signal.size - 1, idx + minDistanceSamples))
                .minOfOrNull { signal[it] } ?: signal[idx]
            signal[idx] - maxOf(leftMin, rightMin)
        }
        
        val medianProminence = prominences.sorted()[prominences.size / 2]
        val prominenceThreshold = 0.30 * medianProminence
        
        val peaks = mutableListOf<Int>()
        var rejectedCount = 0
        for (i in candidates.indices) {
            if (prominences[i] >= prominenceThreshold) {
                peaks.add(candidates[i])
            } else {
                rejectedCount++
            }
        }
        
        android.util.Log.d("PeakDetect", "PEAK_DETECT | candidates=${candidates.size} " +
              "accepted=${peaks.size} rejected=$rejectedCount " +
              "madThreshold=${String.format("%.4f", threshold)} " +
              "medianProm=${String.format("%.4f", medianProminence)} " +
              "promThreshold=${String.format("%.4f", prominenceThreshold)}")
        
        if (peaks.isEmpty()) {
            return PeakDetectResult(
                indices = intArrayOf(),
                timesMs = doubleArrayOf(),
                rrIntervalsMs = doubleArrayOf(),
                isValid = false,
                message = "All peaks rejected by prominence filter"
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
            message = "Detected ${peaks.size} peaks (MAD+prominence)"
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
