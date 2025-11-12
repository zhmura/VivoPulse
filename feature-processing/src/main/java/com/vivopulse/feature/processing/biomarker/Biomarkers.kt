package com.vivopulse.feature.processing.biomarker

import com.vivopulse.feature.processing.ProcessedSeries
import com.vivopulse.feature.processing.QualityReport
import com.vivopulse.feature.processing.ptt.PeakDetect
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

data class BiomarkerPanel(
    val hrBpm: Double,
    val rmssdMs: Double?,
    val sdnnMs: Double?,
    val respiratoryModulationDetected: Boolean
)

object BiomarkerComputer {
    /**
     * Compute minimal biomarker panel.
     * Requires processed series and quality gating for HRV and respiration hint.
     */
    fun compute(
        series: ProcessedSeries,
        quality: QualityReport,
        hrBpm: Double
    ): BiomarkerPanel {
        val peaks = PeakDetect.detectPeaks(series.fingerSignal, series.sampleRateHz)
        val rr = peaks.rrIntervalsMs.toList()
        val enoughBeats = rr.size >= 10
        val highQuality = quality.combinedScore >= 70.0 && quality.pttConfidence >= 60.0

        val rmssd = if (highQuality && enoughBeats) computeRmssd(rr) else null
        val sdnn = if (highQuality && enoughBeats) computeSdnn(rr) else null
        val respDetected = if (highQuality && rr.size >= 6) detectRespiratoryModulation(series, peaks) else false

        return BiomarkerPanel(
            hrBpm = hrBpm,
            rmssdMs = rmssd,
            sdnnMs = sdnn,
            respiratoryModulationDetected = respDetected
        )
    }

    private fun computeRmssd(rr: List<Double>): Double? {
        if (rr.size < 3) return null
        val diffs = rr.zip(rr.drop(1)) { a, b -> b - a }
        if (diffs.isEmpty()) return null
        val meanSq = diffs.map { it * it }.average()
        return sqrt(meanSq)
    }

    private fun computeSdnn(rr: List<Double>): Double? {
        if (rr.isEmpty()) return null
        val mean = rr.average()
        val variance = rr.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }

    /**
     * Respiratory modulation hint:
     * - Build a per-beat amplitude series (peak - pre-peak min)
     * - Detrend and compute autocorrelation
     * - If normalized autocorr has a clear peak for 0.1–0.3 Hz (3.3–10s), flag true
     */
    private fun detectRespiratoryModulation(
        series: ProcessedSeries,
        peakResult: com.vivopulse.feature.processing.ptt.PeakDetectResult
    ): Boolean {
        val peaks = peakResult.indices
        if (peaks.size < 6) return false
        val sig = series.fingerSignal
        val fs = series.sampleRateHz

        val amplitudes = ArrayList<Double>()
        val timesMs = ArrayList<Double>()
        for (i in 1 until peaks.size) {
            val prev = peaks[i - 1]
            val p = peaks[i]
            // pre-peak min
            var minVal = sig[prev]
            for (k in prev until p) {
                if (sig[k] < minVal) minVal = sig[k]
            }
            val amp = sig[p] - minVal
            amplitudes.add(amp)
            timesMs.add(p * 1000.0 / fs)
        }
        if (amplitudes.size < 5) return false
        // Detrend by subtracting mean
        val mean = amplitudes.average()
        val x = amplitudes.map { it - mean }
        // Autocorrelation
        val ac = autocorr(x)
        if (ac.isEmpty()) return false
        // Find lag range corresponding to 0.1–0.3 Hz in beat domain.
        // Approximate sampling interval as median beat interval in samples of the amplitude series (= 1 per beat).
        // So lag in beats (k) corresponds to period of k beats. Convert to seconds using mean RR.
        val rrMeanS = (peakResult.rrIntervalsMs.takeIf { it.isNotEmpty() }?.average() ?: 1000.0) / 1000.0
        // period bounds in beats
        val periodMinBeats = (0.1).let { freq -> (1.0 / freq) / rrMeanS } // ~10s / rrMeanS
        val periodMaxBeats = (0.3).let { freq -> (1.0 / freq) / rrMeanS } // ~3.3s / rrMeanS
        val kMin = periodMaxBeats.toInt().coerceAtLeast(1)
        val kMax = periodMinBeats.toInt().coerceAtLeast(kMin + 1)
        var peak = 0.0
        for (k in kMin..kMax.coerceAtMost(ac.lastIndex)) {
            if (ac[k] > peak) peak = ac[k]
        }
        return peak >= 0.3 // heuristic threshold
    }

    private fun autocorr(x: List<Double>): List<Double> {
        val n = x.size
        if (n < 3) return emptyList()
        val denom = x.sumOf { it * it }.takeIf { it > 1e-9 } ?: return emptyList()
        val ac = MutableList(n) { 0.0 }
        for (lag in 0 until n) {
            var sum = 0.0
            for (i in 0 until (n - lag)) {
                sum += x[i] * x[i + lag]
            }
            ac[lag] = (sum / (n - lag)) / (denom / n)
        }
        // Normalize to 1 at lag 0
        val zero = ac[0].takeIf { abs(it) > 1e-9 } ?: 1.0
        return ac.map { it / zero }
    }
}


