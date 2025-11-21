package com.vivopulse.feature.processing.realtime

import com.vivopulse.signal.SignalSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealTimeQualityEngineTest {

    @Test
    fun stableSignalsReachGreen() {
        val engine = RealTimeQualityEngine()
        var latest: RealTimeQualityState? = null
        repeat(500) { idx ->
            val state = engine.addSample(sample(idx, faceBase = 0.6, fingerBase = 0.6))
            if (state != null) latest = state
        }
        assertEquals(QualityStatus.GREEN, latest?.face?.status)
        assertEquals(QualityStatus.GREEN, latest?.finger?.status)
    }

    @Test
    fun highSaturationTriggersTip() {
        val engine = RealTimeQualityEngine()
        var latest: RealTimeQualityState? = null
        repeat(450) { idx ->
            val sat = if (idx > 250) 0.2 else 0.02
            latest = engine.addSample(sample(idx, faceBase = 0.6, fingerBase = 0.6, saturation = sat))
        }
        assertTrue(latest?.tip?.contains("finger", ignoreCase = true) == true)
        assertEquals(QualityStatus.RED, latest?.finger?.status)
    }

    @Test
    fun updateRateStaysBelowFourHz() {
        val engine = RealTimeQualityEngine()
        var states = 0
        repeat(120) { idx ->
            val state = engine.addSample(sample(idx, faceBase = 0.6, fingerBase = 0.55))
            if (state != null) states++
        }
        // 120 samples @ ~30Hz ≈ 4 seconds → expect <= 12 updates @ 3 Hz target
        assertTrue(states <= 12)
    }

    private fun sample(
        index: Int,
        faceBase: Double,
        fingerBase: Double,
        motion: Double = 0.2,
        saturation: Double = 0.02
    ): SignalSample {
        val timestamp = 1_000_000_000L + index * 33_000_000L
        val omega = 2 * Math.PI * 1.2 / 30.0
        val phase = index * omega
        return SignalSample(
            timestampNs = timestamp,
            faceMeanLuma = faceBase + 0.4 * kotlin.math.sin(phase),
            fingerMeanLuma = fingerBase + 0.4 * kotlin.math.cos(phase),
            faceMotionRmsPx = motion,
            fingerSaturationPct = saturation,
            torchEnabled = true
        )
    }
}


