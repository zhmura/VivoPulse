package com.vivopulse.feature.processing.wave

import com.vivopulse.feature.processing.ProcessedSeries
import com.vivopulse.feature.processing.ptt.PeakDetect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Beat-level vascular wave feature extraction.
 *
 * Computes simple shape-based features on the finger signal:
 * - riseTimeMs: from "foot" (fraction from pre-peak minimum) to systolic peak
 * - peakTimeMs: from beat start (previous peak) to systolic peak
 * - dicroticHint: presence of secondary downturn/upturn after peak
 * - reflectionRatio: area late-systolic / early-systolic (simple surrogate)
 *
 * Aggregates features across valid beats into a session-level profile.
 *
 * Literature alignment:
 * - Uses standard PPG contour notions (foot-to-peak rise, late vs early systolic areas).
 * - ReflectionRatio here is a reflection-like surrogate and NOT AIx; labels and UI avoid that claim.
 *
 * Safety:
 * - Heuristic thresholds; experimental features intended for relative trends and research, not diagnosis.
 */
object WaveFeatures {
    data class BeatFeatures(
        val riseTimeMs: Double?,
        val peakTimeMs: Double?,
        val reflectionRatio: Double?,
        val dicroticHint: Boolean
    )

    data class VascularWaveProfile(
        val meanRiseTimeMs: Double?,
        val meanPeakTimeMs: Double?,
        val meanReflectionRatio: Double?,
        val dicroticPresenceScore: Double? // 0..1
    )

    /**
     * Compute session-level vascular wave profile from processed signals.
     *
     * Uses finger channel as primary. Requires valid peaks on both channels
     * to ensure physiologic beat segmentation (even though features are
     * computed on finger).
     */
    fun computeProfile(series: ProcessedSeries): VascularWaveProfile {
        if (!series.isValid || series.fingerSignal.isEmpty() || series.faceSignal.isEmpty()) {
            return VascularWaveProfile(null, null, null, null)
        }
        val fs = series.sampleRateHz
        val fingerPeaks = PeakDetect.detectPeaks(series.fingerSignal, fs)
        val facePeaks = PeakDetect.detectPeaks(series.faceSignal, fs)
        if (!fingerPeaks.isValid || !facePeaks.isValid || fingerPeaks.indices.size < 3) {
            return VascularWaveProfile(null, null, null, null)
        }

        val beats = computeBeatFeatures(
            signal = series.fingerSignal,
            peakIndices = fingerPeaks.indices,
            fsHz = fs
        )

        val riseTimes = beats.mapNotNull { it.riseTimeMs }
        val peakTimes = beats.mapNotNull { it.peakTimeMs }
        val reflRatios = beats.mapNotNull { it.reflectionRatio }.filter { it.isFinite() && it > 0.0 }
        val dicroticCount = beats.count { it.dicroticHint }
        val totalBeatCount = beats.size

        return VascularWaveProfile(
            meanRiseTimeMs = riseTimes.takeIf { it.isNotEmpty() }?.average(),
            meanPeakTimeMs = peakTimes.takeIf { it.isNotEmpty() }?.average(),
            meanReflectionRatio = reflRatios.takeIf { it.isNotEmpty() }?.average(),
            dicroticPresenceScore = if (totalBeatCount > 0) dicroticCount.toDouble() / totalBeatCount else null
        )
    }

    private fun computeBeatFeatures(
        signal: DoubleArray,
        peakIndices: IntArray,
        fsHz: Double
    ): List<BeatFeatures> {
        val features = mutableListOf<BeatFeatures>()
        if (peakIndices.size < 2) return features

        // Iterate beats defined by consecutive peaks
        for (i in 1 until peakIndices.size) {
            val prevPeak = peakIndices[i - 1]
            val peak = peakIndices[i]
            if (peak <= prevPeak + 2) continue
            val beatStart = prevPeak
            val beatEnd = peak

            // Find pre-peak minimum between start and peak (pre-systolic trough)
            var minIdx = beatStart
            var minVal = signal[minIdx]
            for (k in beatStart until peak) {
                if (signal[k] < minVal) {
                    minVal = signal[k]
                    minIdx = k
                }
            }

            // Define "foot" as first crossing of a fixed fraction from min to peak
            val peakVal = signal[peak]
            val footFrac = 0.10 // 10% of the rise from min to peak
            val footThreshold = minVal + footFrac * (peakVal - minVal)
            var footIdx = minIdx
            for (k in minIdx..peak) {
                if (signal[k] >= footThreshold) {
                    footIdx = k
                    break
                }
            }

            val riseTimeMs = if (footIdx < peak) ((peak - footIdx) * 1000.0 / fsHz) else null
            val peakTimeMs = ((peak - beatStart) * 1000.0 / fsHz)

            // Dicrotic hint: search for min->max pattern after peak within 80-350 ms
            val windowStart = min(signal.size - 1, (peak + (0.08 * fsHz)).toInt())
            val windowEnd = min(signal.size - 1, (peak + (0.35 * fsHz)).toInt())
            var foundDicrotic = false
            if (windowEnd > windowStart + 2) {
                var localMinIdx = windowStart
                var localMinVal = signal[localMinIdx]
                for (k in windowStart..windowEnd) {
                    if (signal[k] < localMinVal) {
                        localMinVal = signal[k]
                        localMinIdx = k
                    }
                }
                // After local minimum, look for a rebound (local maximum)
                var localMaxVal = localMinVal
                for (k in localMinIdx..windowEnd) {
                    if (signal[k] > localMaxVal) {
                        localMaxVal = signal[k]
                    }
                }
                val peakAmplitude = max(1e-9, peakVal - minVal)
                val rebound = localMaxVal - localMinVal
                // Heuristic: rebound at least 5% of peak amplitude indicates dicrotic activity
                if (rebound / peakAmplitude >= 0.05) {
                    foundDicrotic = true
                }
            }

            // Reflection ratio: late-systolic area / early-systolic area (above min baseline)
            val earlyArea = trapezoidAreaAboveBaseline(signal, footIdx, peak, minVal)
            // Use next peak as end of late-systolic if available, else bounded window
            val nextPeak = if (i + 1 < peakIndices.size) peakIndices[i + 1] else min(signal.size - 1, peak + (0.6 * fsHz).toInt())
            val lateArea = trapezoidAreaAboveBaseline(signal, peak, nextPeak, minVal)
            val reflectionRatio = if (earlyArea > 1e-6) lateArea / earlyArea else null

            features.add(
                BeatFeatures(
                    riseTimeMs = riseTimeMs,
                    peakTimeMs = peakTimeMs,
                    reflectionRatio = reflectionRatio,
                    dicroticHint = foundDicrotic
                )
            )
        }
        return features
    }

    private fun trapezoidAreaAboveBaseline(
        signal: DoubleArray,
        startIdx: Int,
        endIdx: Int,
        baseline: Double
    ): Double {
        val a = max(0, min(startIdx, signal.lastIndex))
        val b = max(0, min(endIdx, signal.lastIndex))
        if (b <= a) return 0.0
        var area = 0.0
        var prev = signal[a] - baseline
        for (i in (a + 1)..b) {
            val curr = signal[i] - baseline
            area += (prev + curr) * 0.5
            prev = curr
        }
        return area
    }
}


