package com.vivopulse.feature.processing.realtime

import com.vivopulse.signal.SignalSample
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryUsageTest {

    @Test
    fun buffersCapAtTwentySeconds() {
        val engine = RealTimeQualityEngine()
        repeat(1800) { idx ->
            engine.addSample(sample(idx))
        }
        val stats = engine.debugStats()
        assertEquals(1800, stats.faceSamples)
        assertEquals(1800, stats.fingerSamples)
    }

    private fun sample(index: Int): SignalSample {
        val timestamp = 1_000_000_000L + index * 33_000_000L
        return SignalSample(
            timestampNs = timestamp,
            faceMeanLuma = 0.4,
            fingerMeanLuma = 0.4,
            faceMotionRmsPx = 0.2,
            fingerSaturationPct = 0.02,
            torchEnabled = true
        )
    }
}


