package com.vivopulse.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import com.vivopulse.feature.processing.simulation.SimulatedFrameSource
import com.vivopulse.feature.processing.simulation.SimulationConfig
import com.vivopulse.feature.processing.SignalPipeline
import com.vivopulse.feature.processing.PttCalculator

@RunWith(AndroidJUnit4::class)
class SimE2ETest {

    @Test
    fun simulatedMode_pttAndCorrelationWithinThresholds() {
        val cfg = SimulationConfig(
            heartRateBpm = 72.0,
            pttMs = 120.0,
            noiseLevel = 0.02,
            driftMsPerSecond = 0.0,
            durationSeconds = 30.0,
            sampleRateHz = 100.0
        )

        val simulator = SimulatedFrameSource(cfg)
        val rawBuffer = simulator.generateSignals()
        val pipeline = SignalPipeline(targetSampleRateHz = 100.0)
        val series = pipeline.process(rawBuffer)

        assertTrue("Simulated series should be valid", series.isValid)

        val ptt = PttCalculator.computePtt(series)
        assertTrue("Correlation should be ≥ 0.90, was ${ptt.corrScore}", ptt.corrScore >= 0.90)
        assertEquals("PTT should be 120±5 ms", 120.0, ptt.lagMs, 5.0)
        assertTrue("Stability SD should be ≤ 5 ms, was ${ptt.stabilitySdMs}", ptt.stabilitySdMs <= 5.0)
    }
}


