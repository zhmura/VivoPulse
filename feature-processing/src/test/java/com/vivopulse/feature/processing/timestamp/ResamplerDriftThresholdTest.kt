package com.vivopulse.feature.processing.timestamp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplerDriftThresholdTest {

    @Test
    fun `resample two streams with +8 ms per second drift produces valid unified timeline`() {
        val fps = 30.0
        val intervalNs = (1e9 / fps).toLong()
        val seconds = 20
        val frames = seconds * fps.toInt()

        // stream1: ideal timestamps
        val s1 = (0 until frames).map { it * intervalNs }

        // stream2: +8 ms per second drift → per second = 8_000_000 ns
        val driftPerSecondNs = 8_000_000L
        val s2 = (0 until frames).map { i ->
            val t = i * intervalNs
            val sec = t / 1_000_000_000.0
            (t + (sec * driftPerSecondNs).toLong())
        }

        val data1 = s1.map { TimestampedValue(it, 1.0) }
        val data2 = s2.map { TimestampedValue(it, 1.0) }

        val resampled = TimestampSync.resampleToUnifiedTimeline(data1, data2, targetFrequencyHz = 100.0)

        assertTrue("Resampling should be valid", resampled.isValid)
        assertEquals(100.0, resampled.sampleRate, 1e-6)
        assertTrue("Unified timestamps should be non-empty", resampled.unifiedTimestamps.isNotEmpty())
        assertEquals(
            "Both streams must be aligned onto same timeline length",
            resampled.stream1Values.size,
            resampled.stream2Values.size
        )
    }
}


