package com.vivopulse.feature.processing

import com.vivopulse.feature.processing.timestamp.TimestampedValue
import com.vivopulse.signal.ProcessedSignal
import org.junit.Assert.*
import org.junit.Test

class SignalPipelineTest {
    private val pipeline = SignalPipeline(targetSampleRateHz = 100.0, lowCutoffHz = 0.5, highCutoffHz = 4.0)
    private fun source() = (0..600).map { TimestampedValue(it * 33_333_333L, 100.0 + kotlin.math.sin(it * 0.25)) }

    @Test fun `process aligns signals to 100 Hz and preserves observations`() {
        val input = source()
        val result = pipeline.process(RawSeriesBuffer(input, input))
        assertTrue(result.isValid)
        assertEquals(100.0, result.sampleRateHz, 0.1)
        assertTrue(result.timeMillis.size in 1780..1805)
        assertEquals(input, result.sourceFaceData)
        assertTrue("UNVERIFIED_TIMEBASE" in result.invalidReasons)
        assertNull(result.pttOutput)
    }

    @Test fun `process propagates timestamped acquisition quality without inventing sample correspondence`() {
        val input = source()
        val result = pipeline.process(RawSeriesBuffer(input, input,
            faceMotion = input.map { it.copy(value = 0.5) },
            imuRms = input.map { it.copy(value = 0.02) }))
        assertTrue(result.isValid)
        assertEquals(0.02, result.imuRmsG.average(), 0.001)
        assertEquals(0.5, result.faceMotionRms.average(), 0.001)
    }

    @Test fun `process handles empty input gracefully`() {
        val result = pipeline.process(RawSeriesBuffer(emptyList(), emptyList()))
        assertFalse(result.isValid)
        assertEquals(listOf("MISSING_CHANNEL"), result.invalidReasons)
    }

    @Test fun `high motion invalidates delay without repairing waveform`() {
        val input = source()
        val imu = input.mapIndexed { i, value -> value.copy(value = if (i in 200..300) 0.5 else 0.02) }
        val result = pipeline.process(RawSeriesBuffer(input, input, imuRms = imu,
            provenance = SignalProvenance.SYNTHETIC, timingVerified = true, hardwarePttCapable = true))
        assertTrue(result.isValid)
        assertTrue((result.imuRmsG.maxOrNull() ?: 0.0) > 0.4)
        assertEquals(input, result.sourceFaceData)
        assertTrue("MOTION" in result.invalidReasons)
        assertNull(result.pttOutput)
        assertFalse(PttCalculator.computePtt(result).isValid)
    }
}