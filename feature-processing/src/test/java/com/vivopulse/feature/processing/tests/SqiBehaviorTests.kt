package com.vivopulse.feature.processing.tests

import com.vivopulse.signal.SignalQuality
import org.junit.Assert.assertTrue
import org.junit.Test

class SqiBehaviorTests {
    @Test
    fun sqi_monotonic_clean_gt_noisy_gt_heavyArtifact() {
        val fs = 100.0
        val n = 3000
        val clean = DoubleArray(n) { i -> kotlin.math.sin(2 * Math.PI * 1.2 * (i / fs)) }
        val noisy = DoubleArray(n) { i -> clean[i] + 0.5 * kotlin.math.sin(2 * Math.PI * 15.0 * (i / fs)) }
        val artifact = DoubleArray(n) { i ->
            val base = if ((i / 200) % 2 == 0) 0.0 else clean[i] // dropouts
            base + 1.0 * kotlin.math.sin(2 * Math.PI * 18.0 * (i / fs))
        }
        val sqiClean = SignalQuality.computeChannelSQI(clean, fs)
        val sqiNoisy = SignalQuality.computeChannelSQI(noisy, fs)
        val sqiArtifact = SignalQuality.computeChannelSQI(artifact, fs)
        assertTrue(sqiClean.score > sqiNoisy.score)
        assertTrue(sqiNoisy.score > sqiArtifact.score)
    }
}


