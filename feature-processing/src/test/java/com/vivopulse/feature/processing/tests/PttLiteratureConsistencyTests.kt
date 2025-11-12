package com.vivopulse.feature.processing.tests

import com.vivopulse.feature.processing.ProcessedSeries
import com.vivopulse.feature.processing.PttCalculator
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PttLiteratureConsistencyTests {
    @Test
    fun knownLag_highSnr_errorUnder5ms() {
        val fs = 100.0
        val n = 5000
        val lagMs = 80.0
        val lagSamples = (lagMs * fs / 1000.0).toInt()
        val face = DoubleArray(n) { i -> kotlin.math.sin(2 * Math.PI * 1.2 * (i / fs)) }
        val finger = DoubleArray(n) { i ->
            val idx = i - lagSamples
            if (idx >= 0) face[idx] else 0.0
        }
        val series = ProcessedSeries(
            timeMillis = (0 until n).map { it * 10.0 },
            faceSignal = face,
            fingerSignal = finger,
            sampleRateHz = fs,
            isValid = true
        )
        val result = PttCalculator.computePtt(series)
        assertTrue(result.isValid)
        assertTrue(abs(result.pttMs - lagMs) <= 5.0)
    }

    @Test
    fun knownLag_moderateSnr_errorUnder10ms_andMonotonicity() {
        val fs = 100.0
        val n = 5000
        fun makeSignals(lagMs: Double, noiseAmp: Double): Pair<DoubleArray, DoubleArray> {
            val lagSamples = (lagMs * fs / 1000.0).toInt()
            val face = DoubleArray(n) { i ->
                kotlin.math.sin(2 * Math.PI * 1.3 * (i / fs)) + noiseAmp * kotlin.math.sin(2 * Math.PI * 9.0 * (i / fs))
            }
            val finger = DoubleArray(n) { i ->
                val idx = i - lagSamples
                val base = if (idx >= 0) face[idx] else 0.0
                base + noiseAmp * kotlin.math.sin(2 * Math.PI * 7.0 * (i / fs))
            }
            return face to finger
        }
        val (f1, g1) = makeSignals(70.0, 0.3)
        val s1 = ProcessedSeries(
            timeMillis = (0 until n).map { it * 10.0 },
            faceSignal = f1,
            fingerSignal = g1,
            sampleRateHz = fs,
            isValid = true
        )
        val r1 = PttCalculator.computePtt(s1)
        assertTrue(r1.isValid)
        assertTrue(abs(r1.pttMs - 70.0) <= 10.0)

        val (f2, g2) = makeSignals(50.0, 0.3)
        val s2 = ProcessedSeries(
            timeMillis = (0 until n).map { it * 10.0 },
            faceSignal = f2,
            fingerSignal = g2,
            sampleRateHz = fs,
            isValid = true
        )
        val r2 = PttCalculator.computePtt(s2)
        assertTrue(r2.isValid)
        // Monotonic: reduced true lag -> reduced measured PTT
        assertTrue(r2.pttMs < r1.pttMs)
    }
}


