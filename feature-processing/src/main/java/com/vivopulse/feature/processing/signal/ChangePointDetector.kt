package com.vivopulse.feature.processing.signal

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Gap E: Detects baseline change-points (pressure slips, AE steps) in PPG signals.
 *
 * These step-like events ring severely after bandpass filtering, producing
 * false peaks. This utility detects them on a low-passed version of the signal
 * and replaces the affected spans with linear interpolation.
 *
 * **Algorithm:**
 * 1. Low-pass the signal with a moving average (~0.3 Hz effective cutoff)
 * 2. Compute first-order differences of the low-passed signal
 * 3. Z-score the differences — spikes indicate step events
 * 4. Mark indices where |z-score| > threshold as change-points
 * 5. For each change-point, blank ±spanSamples with linear interpolation
 */
object ChangePointDetector {

    data class Result(
        /** Cleaned signal with step-spans interpolated out */
        val cleaned: DoubleArray,
        /** Indices where change-points were detected */
        val changePointIndices: List<Int>
    )

    /**
     * Detect and repair step-like baseline changes.
     *
     * @param signal     Raw or detrended signal (before bandpass)
     * @param sampleRate Sample rate in Hz
     * @param cutoffHz   Low-pass cutoff for baseline extraction (default 0.3 Hz)
     * @param threshold  Z-score threshold for change-point detection (default 2.0)
     * @param spanSeconds Seconds to blank around each change-point (default 0.5)
     * @return [Result] with cleaned signal and detected change-point indices
     */
    fun detectAndRepair(
        signal: DoubleArray,
        sampleRate: Double,
        cutoffHz: Double = 0.3,
        threshold: Double = 2.0,
        spanSeconds: Double = 0.5
    ): Result {
        if (signal.size < 10) return Result(signal.copyOf(), emptyList())

        // 1. Low-pass with simple moving average (window ≈ 1/cutoffHz samples)
        val windowSize = maxOf(3, (sampleRate / cutoffHz).toInt().let { if (it % 2 == 0) it + 1 else it })
        val lowPassed = movingAverage(signal, windowSize)

        // 2. First-order differences of baseline
        val diffs = DoubleArray(lowPassed.size - 1) { i -> lowPassed[i + 1] - lowPassed[i] }
        if (diffs.isEmpty()) return Result(signal.copyOf(), emptyList())

        // 3. Z-score the differences
        val mean = diffs.average()
        val std = sqrt(diffs.map { (it - mean) * (it - mean) }.average())
        if (std < 1e-10) return Result(signal.copyOf(), emptyList()) // Flat signal

        // 4. Find change-points where |z-score| exceeds threshold
        val changePoints = mutableListOf<Int>()
        for (i in diffs.indices) {
            val zscore = abs((diffs[i] - mean) / std)
            if (zscore > threshold) {
                // Avoid duplicates within the same span
                if (changePoints.isEmpty() || (i - changePoints.last()) > (sampleRate * spanSeconds).toInt()) {
                    changePoints.add(i + 1) // +1 because diff[i] corresponds to transition at i+1
                }
            }
        }

        if (changePoints.isEmpty()) return Result(signal.copyOf(), changePoints)

        // 5. Linear-interpolate the spans around each change-point
        val spanSamples = maxOf(1, (sampleRate * spanSeconds).toInt())
        val cleaned = signal.copyOf()

        for (cp in changePoints) {
            val start = maxOf(0, cp - spanSamples)
            val end = minOf(signal.size - 1, cp + spanSamples)
            if (start >= end) continue

            // Get boundary values for linear interpolation
            val startVal = cleaned[start]
            val endVal = cleaned[end]
            val span = end - start

            for (i in start..end) {
                val t = (i - start).toDouble() / span.toDouble()
                cleaned[i] = startVal + t * (endVal - startVal)
            }
        }

        return Result(cleaned, changePoints)
    }

    /**
     * Simple moving average low-pass filter.
     */
    private fun movingAverage(signal: DoubleArray, windowSize: Int): DoubleArray {
        val half = windowSize / 2
        return DoubleArray(signal.size) { i ->
            val start = maxOf(0, i - half)
            val end = minOf(signal.size - 1, i + half)
            var sum = 0.0
            for (j in start..end) sum += signal[j]
            sum / (end - start + 1)
        }
    }
}
