package com.vivopulse.feature.processing

import com.vivopulse.feature.processing.timestamp.TimestampSync
import com.vivopulse.feature.processing.timestamp.TimestampedValue
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MeasurementValidityRegressionTest {
    @Test fun `waveform equality must not hide changed measurement validity or provenance`() {
        val series = ProcessedSeries(listOf(0.0, 10.0), doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0, 2.0),
            sampleRateHz = 100.0, isValid = true)
        assertNotEquals(series, series.copy(provenance = SignalProvenance.SYNTHETIC))
        assertNotEquals(series, series.copy(timingVerified = true))
        assertNotEquals(series, series.copy(invalidReasons = listOf("MOTION")))
        assertNotEquals(series, series.copy(timelineOriginNs = 100_000_000L))
    }

    private fun clean(fs: Double = 30.0): RawSeriesBuffer {
        fun channel(delay: Double) = (0 until (20 * fs).toInt()).map {
            val t = it / fs
            TimestampedValue((t * 1e9).toLong(), 100 + 5 * sin(2 * PI * 1.2 * (t - delay)))
        }
        return RawSeriesBuffer(channel(0.0), channel(0.1), provenance = SignalProvenance.SYNTHETIC,
            timingVerified = true, hardwarePttCapable = true)
    }

    @Test fun `missing channel never creates synthetic measurements`() {
        val raw = clean().copy(faceData = emptyList(), provenance = SignalProvenance.REAL)
        val result = SignalPipeline().process(raw)
        assertEquals(listOf("MISSING_CHANNEL"), result.invalidReasons)
        assertTrue(result.faceSignal.isEmpty())
        assertEquals(raw.fingerData, result.sourceFingerData)
        assertEquals(SignalProvenance.REAL, result.provenance)
        val presented = PttCalculator.computePtt(result)
        assertFalse(presented.isValid)
        assertTrue(presented.pttMs.isNaN())
    }

    @Test fun `invalid timebase cannot be compensated by clean signals`() {
        val result = SignalPipeline().process(clean().copy(timingVerified = false))
        assertTrue(result.isValid) // signal plots remain available
        assertTrue("UNVERIFIED_TIMEBASE" in result.invalidReasons)
        assertNull(result.pttOutput)
        assertFalse(PttCalculator.computePtt(result).isValid)
    }

    @Test fun `adapter does not recompute rejected engine output`() {
        val result = SignalPipeline().process(clean()).copy(pttOutput = null, invalidReasons = listOf("MOTION"))
        val presented = PttCalculator.computePtt(result)
        assertFalse(presented.isValid)
        assertEquals("MOTION", presented.message)
    }

    @Test fun `motion is a mandatory rejection rather than a soft quality penalty`() {
        val raw = clean()
        val result = SignalPipeline().process(raw.copy(imuRms = raw.faceData.map { it.copy(value = 0.5) }))
        assertTrue("MOTION" in result.invalidReasons)
        assertNull(result.pttOutput)
        assertFalse(PttCalculator.computePtt(result).isValid)
    }

    @Test fun `low native fps does not become timing evidence after upsampling`() {
        val result = SignalPipeline().process(clean(20.0))
        assertEquals(40.0, result.sampleRateHz, 0.01)
        assertTrue("LOW_NATIVE_FPS" in result.invalidReasons)
        assertNull(result.pttOutput)
    }

    @Test fun `bounded interpolation marks missing spans instead of bridging them`() {
        val raw = clean()
        val sparse = raw.faceData.filter { it.timestampNs !in 8_000_000_000L..9_000_000_000L }
        val resampled = TimestampSync.resampleToUnifiedTimeline(sparse, raw.fingerData)
        assertFalse(resampled.isValid)
        assertTrue(resampled.validityMask.any { !it })
        assertEquals("INTERPOLATION_GAP", resampled.message)
        val result = SignalPipeline().process(raw.copy(faceData = sparse))
        assertNull(result.pttOutput)
        assertTrue("INTERPOLATION_GAP" in result.invalidReasons)
    }

    @Test fun `acquisition truncation cannot establish clock drift`() {
        val raw = clean()
        val sync = TimestampSync.analyzeSynchronization(raw.faceData.map { it.timestampNs }, raw.fingerData.drop(20).map { it.timestampNs })
        assertTrue(sync.isValid)
        assertTrue(sync.rateRatio.isNaN())
        assertTrue(sync.driftMsPerSecond.isNaN())
        assertFalse(sync.offsetValid)
    }

    @Test fun `nonfinite and duplicate timestamp observations are rejected before filtering`() {
        val raw = clean()
        val nonfinite = raw.faceData.toMutableList().apply { this[50] = this[50].copy(value = Double.NaN) }
        assertEquals(listOf("NONFINITE_SAMPLE"), SignalPipeline().process(raw.copy(faceData = nonfinite)).invalidReasons)
        val duplicate = raw.faceData.toMutableList().apply { this[50] = this[50].copy(timestampNs = this[49].timestampNs) }
        assertEquals(listOf("NONMONOTONIC_TIMESTAMPS"), SignalPipeline().process(raw.copy(faceData = duplicate)).invalidReasons)
    }
}
