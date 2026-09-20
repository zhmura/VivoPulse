package com.vivopulse.feature.processing.tests

import com.vivopulse.feature.processing.ptt.CrossCorr
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * P2 Research Upgrade Tests
 *
 * Validates adaptive GCC-PHAT with coherence weighting (P2.7).
 */
class P2ResearchUpgradeTests {

    @Test
    fun `adaptive GCC recovers known delay`() {
        val fs = 100.0
        val n = 2000 // 20 seconds
        val trueDelayMs = 80.0
        val delaySamples = (trueDelayMs / 1000.0 * fs).toInt()

        val finger = DoubleArray(n) { sin(2.0 * PI * 1.2 * it / fs) +
                                      0.3 * sin(2.0 * PI * 2.4 * it / fs) }
        val face = DoubleArray(n) { i ->
            val d = i - delaySamples
            if (d >= 0 && d < n) finger[d] else 0.0
        }

        val result = CrossCorr.adaptiveGccLag(face, finger, fs)

        assertTrue("Should be valid", result.isValid)
        assertTrue("Should recover delay magnitude near 80ms, got ${result.lagMs}ms",
            abs(abs(result.lagMs) - trueDelayMs) < 30.0)
    }

    @Test
    fun `adaptive GCC falls back for short signal`() {
        val fs = 100.0
        val n = 400 // Too short for Welch (< 256*2 segments)
        val signal = DoubleArray(n) { sin(2.0 * PI * 1.0 * it / fs) }

        val result = CrossCorr.adaptiveGccLag(
            signal, signal, fs, welchSegment = 256
        )

        // Should still work with fallback β
        assertTrue("Should be valid with fallback", result.isValid)
        assertTrue("Message should indicate non-adaptive: ${result.message}",
            result.message.contains("adaptive=false"))
    }

    @Test
    fun `adaptive GCC uses coherence for long signal`() {
        val fs = 100.0
        val n = 3000 // 30 seconds — enough for Welch with 256-sample segments
        val delaySamples = 10 // 100ms

        val finger = DoubleArray(n) { sin(2.0 * PI * 1.0 * it / fs) +
                                      0.5 * sin(2.0 * PI * 2.0 * it / fs) }
        val face = DoubleArray(n) { i ->
            val d = i - delaySamples
            if (d >= 0 && d < n) finger[d] else 0.0
        }

        val result = CrossCorr.adaptiveGccLag(face, finger, fs, welchSegment = 256)

        assertTrue("Should be valid", result.isValid)
        assertTrue("Message should indicate adaptive mode: ${result.message}",
            result.message.contains("adaptive=true"))
    }

    @Test
    fun `adaptive GCC handles noisy signal gracefully`() {
        val fs = 100.0
        val n = 2000
        val delaySamples = 8

        val finger = DoubleArray(n) { sin(2.0 * PI * 1.2 * it / fs) +
                                      (Math.random() - 0.5) * 0.5 }
        val face = DoubleArray(n) { i ->
            val d = i - delaySamples
            if (d >= 0 && d < n) finger[d] + (Math.random() - 0.5) * 0.3 else 0.0
        }

        val result = CrossCorr.adaptiveGccLag(face, finger, fs)

        assertTrue("Should be valid even with noise", result.isValid)
        // Lag may be less accurate with noise but should not crash
    }
}
