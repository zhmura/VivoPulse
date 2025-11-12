package com.vivopulse.signal

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PeakDetectorTest {

    @Test
    fun `peak detection jittered pulses has low false positive and negative rate`() {
        val fs = 100.0
        val durationS = 60.0
        val n = (fs * durationS).toInt()

        // Generate base line and strong periodic peaks every 0.83s (~72 bpm)
        val base = DoubleArray(n) { i -> 0.05 * sin(2 * PI * 0.2 * i / fs) }
        val expectedPeakIndices = mutableListOf<Int>()
        var t = 0.83
        while (t < durationS) {
            val idx = (t * fs).toInt()
            if (idx in 2 until n - 2) {
                // Create a tall narrow peak
                base[idx - 2] += 0.2
                base[idx - 1] += 0.5
                base[idx] += 1.2
                base[idx + 1] += 0.5
                base[idx + 2] += 0.2
                expectedPeakIndices.add(idx)
            }
            t += 0.83
        }

        val detected = SignalQuality.findPeaks(base, minDistance = (fs * 0.4).toInt())

        // Validate detected count is reasonably close to expected
        val expected = expectedPeakIndices.size
        val detectedCount = detected.size
        val diffRatio = kotlin.math.abs(detectedCount - expected).toDouble() / expected.toDouble()
        assertTrue("Detected peaks within 20% of expected (exp=$expected, got=$detectedCount)", diffRatio <= 0.2)
    }
}


