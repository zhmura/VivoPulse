package com.vivopulse.feature.processing.tests

import com.vivopulse.feature.processing.ptt.*
import com.vivopulse.signal.SavitzkyGolay
import com.vivopulse.signal.WelchEstimator
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * P1 Research Upgrade Tests
 *
 * Validates:
 * 1. Welch PSD + coherence for correlated signals
 * 2. Savitzky-Golay derivative preserves slope timing
 * 3. CSP delay estimator recovers known delay
 * 4. ITM foot detection finds correct onset times
 * 5. Same-data fusion does not claim independent precision gains
 */
class P1ResearchUpgradeTests {

    // ═══════════════════════════════════════════════════════════════
    // WelchEstimator Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `Welch coherence is high for identical signals`() {
        val fs = 100.0
        val n = 1000
        val signal = DoubleArray(n) { sin(2.0 * PI * 1.5 * it / fs) }

        val result = WelchEstimator.crossSpectral(signal, signal, fs, segmentLength = 128)

        assertTrue("Should have segments", result.nSegments > 0)
        // Coherence of signal with itself should be 1.0 at signal frequency
        val hrBin = result.freqs.indexOfFirst { it >= 1.3 && it <= 1.7 }
        assertTrue("Coherence at HR should be high (≥0.90), got ${result.coherence[hrBin]}",
            result.coherence[hrBin] >= 0.90)
    }

    @Test
    fun `Welch detects dominant frequency`() {
        val fs = 100.0
        val n = 2000  // 20 seconds for better resolution
        val targetHz = 1.2
        val signal = DoubleArray(n) { sin(2.0 * PI * targetHz * it / fs) }

        val (freqs, psd) = WelchEstimator.psd(signal, fs, segmentLength = 256)
        val domFreq = WelchEstimator.dominantFrequency(freqs, psd, 0.7, 4.0)

        assertTrue("Dominant freq should be near $targetHz Hz, got $domFreq",
            abs(domFreq - targetHz) < 0.5)
    }

    // ═══════════════════════════════════════════════════════════════
    // SavitzkyGolay Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `SG derivative of sine is cosine-like`() {
        val fs = 100.0
        val n = 500
        val freq = 1.0
        val signal = DoubleArray(n) { sin(2.0 * PI * freq * it / fs) }
        
        val deriv = SavitzkyGolay.firstDerivative(signal, fs, windowSamples = 5)

        // d/dt sin(2πft) = 2πf cos(2πft)
        // Check derivative at quarter period (should be zero, crossing from positive)
        val quarterIdx = (fs / (4.0 * freq)).toInt()
        // At this point derivative should be near zero
        assertTrue("SG derivative at zero crossing should be small, got ${abs(deriv[quarterIdx])}",
            abs(deriv[quarterIdx]) < 2.0 * PI * freq * 0.15) // Within 15% of analytic peak
    }

    @Test
    fun `SG smoothing preserves DC level`() {
        val random = java.util.Random(12)
        val signal = DoubleArray(100) { 5.0 + (random.nextDouble() - 0.5) * 0.01 }
        val smoothed = SavitzkyGolay.smooth(signal, windowSamples = 5)

        val meanOriginal = signal.average()
        val meanSmoothed = smoothed.average()
        assertTrue("DC level should be preserved, diff=${abs(meanOriginal - meanSmoothed)}",
            abs(meanOriginal - meanSmoothed) < 0.01)
    }

    @Test
    fun `cubic derivative reproduces polynomial including zero derivative at origin`() {
        val signal = DoubleArray(101) { val t = (it - 50) / 100.0; t * t * t }
        val derivative = SavitzkyGolay.firstDerivative(signal, 100.0, 7, 3)
        for (i in 7 until 94) {
            val t = (i - 50) / 100.0
            assertEquals(3.0 * t * t, derivative[i], 1e-10)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CSP Delay Estimator Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `CSP recovers known delay in synthetic signal`() {
        val fs = 100.0
        val n = 6000
        val hrHz = 1.171875 // Exact Welch bin, three informative harmonics
        fun pulse(t: Double) = sin(2 * PI * hrHz * t) + 0.4 * sin(4 * PI * hrHz * t) + 0.2 * sin(6 * PI * hrHz * t)
        for (trueDelayMs in listOf(-80.0, 0.0, 80.0)) {
            val face = DoubleArray(n) { pulse(it / fs) }
            val finger = DoubleArray(n) { pulse(it / fs - trueDelayMs / 1000.0) }
            val result = CrossSpectralPhaseDelay.estimateDelay(
                face, finger, fs, hrHz * 60, maxHarmonics = 3, segmentLength = 512
            )
            assertTrue("Must have informative phase bins", result.nBins >= 3)
            assertEquals("Positive delay means finger later than face", trueDelayMs, result.delayMs, 5.0)
            assertTrue("SE should be finite", result.standardErrorMs.isFinite())
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ITM Foot Detection Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `ITM detects feet in synthetic PPG`() {
        val fs = 100.0
        val n = 1000 // 10 seconds
        // Simulate PPG-like signal: sharp rise, slow decay
        val signal = DoubleArray(n) { i ->
            val phase = (i / fs) % 0.833 // ~72 bpm
            val normalized = phase / 0.833
            // Simple sawtooth-like PPG shape
            if (normalized < 0.3) {
                normalized / 0.3 // fast upstroke
            } else {
                1.0 - (normalized - 0.3) / 0.7 // slow decay
            }
        }

        val peaks = PeakDetect.detectPeaks(signal, fs)
        assertTrue("Synthetic pulse peaks must be detected", peaks.isValid && peaks.indices.size >= 2)
        run {
            val feet = IntersectingTangentFoot.detectFeet(signal, fs, peaks.indices)
            
            val validFeet = feet.count { it.valid }
            assertTrue("Should detect at least some valid feet, got $validFeet / ${feet.size}",
                validFeet >= 1)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Kalman Fusion Tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `fusion preserves uncertainty for correlated same-data methods`() {
        val fuser = PttKalmanFuser(initialPttMs = 100.0, initialVariance = 2500.0)

        val measurements = listOf(
            PttKalmanFuser.Measurement("XCorr", 85.0, 400.0),  // 20ms SE
            PttKalmanFuser.Measurement("GCC", 90.0, 225.0),    // 15ms SE
            PttKalmanFuser.Measurement("CSP", 88.0, 100.0),    // 10ms SE
            PttKalmanFuser.Measurement("F2F", 92.0, 625.0)     // 25ms SE
        )

        val result = fuser.fuse(measurements)

        // These algorithms observe the same samples, so covariance is unknown.
        val minIndividualVar = measurements.minOf { it.varianceMs2 }
        assertTrue("Fusion must not invent independent precision", result.varianceMs2 >= minIndividualVar)
        assertEquals("All 4 methods should contribute", 4, result.methodsUsed)
        val reversed = PttKalmanFuser().fuse(measurements.reversed())
        assertEquals("Evidence order must not affect estimate", result.pttMs, reversed.pttMs, 1e-10)
        val one = PttKalmanFuser().fuse(listOf(PttKalmanFuser.Measurement("A", 80.0, 100.0)))
        val duplicated = PttKalmanFuser().fuse(List(4) { PttKalmanFuser.Measurement("A", 80.0, 100.0) })
        assertEquals("Duplicated evidence must not reduce variance", one.varianceMs2, duplicated.varianceMs2, 1e-10)
    }

    @Test
    fun `Kalman rejects outlier measurement`() {
        val fuser = PttKalmanFuser(initialPttMs = 90.0, initialVariance = 100.0, chi2Gate = 9.0)

        val measurements = listOf(
            PttKalmanFuser.Measurement("XCorr", 88.0, 100.0),  // close to prior
            PttKalmanFuser.Measurement("BAD", 300.0, 50.0),    // outlier (>3σ from prior)
            PttKalmanFuser.Measurement("CSP", 92.0, 100.0)     // close to prior
        )

        val result = fuser.fuse(measurements)

        assertEquals("Outlier should be rejected", 1, result.methodsRejected)
        assertTrue("Fused estimate should be near 90ms (not pulled to 300), got ${result.pttMs}",
            abs(result.pttMs - 90.0) < 15.0)
    }

    @Test
    fun `Kalman handles all null measurements gracefully`() {
        val fuser = PttKalmanFuser(initialPttMs = 100.0, initialVariance = 2500.0)

        val measurements = listOf(
            PttKalmanFuser.Measurement("XCorr", null, Double.POSITIVE_INFINITY),
            PttKalmanFuser.Measurement("F2F", null, Double.POSITIVE_INFINITY)
        )

        val result = fuser.fuse(measurements)

        assertEquals("No methods should contribute", 0, result.methodsUsed)
        assertTrue("No evidence must not return a finite prior as measurement", !result.pttMs.isFinite())
        assertFalse(result.isStable)
    }

    // ═══════════════════════════════════════════════════════════════
    // Coherence → Confidence Flow Test
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `coherence affects PttSqi confidence score`() {
        // High coherence should produce higher confidence
        val confHigh = PttSqi.computeCombinedConfidence(
            sqiFace = 80, sqiFinger = 80,
            corrScore = 0.7, peakSharpness = 0.1,
            delayStabilityScore = 0.8, methodAgreeMs = 10.0,
            coherenceAtHr = 0.6  // High coherence
        )

        // Low coherence should produce lower confidence
        val confLow = PttSqi.computeCombinedConfidence(
            sqiFace = 80, sqiFinger = 80,
            corrScore = 0.7, peakSharpness = 0.1,
            delayStabilityScore = 0.8, methodAgreeMs = 10.0,
            coherenceAtHr = 0.05  // Very low coherence
        )

        assertTrue("High coherence (conf=$confHigh) should beat low coherence (conf=$confLow)",
            confHigh > confLow)
        // The difference should be meaningful (not just floating point noise)
        assertTrue("Confidence difference should be > 0.01, got ${confHigh - confLow}",
            confHigh - confLow > 0.01)
    }
}
