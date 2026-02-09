package com.vivopulse.feature.processing.tests

import com.vivopulse.feature.processing.ProcessedSeries
import com.vivopulse.feature.processing.PttCalculator
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
            rawFaceSignal = face,
            rawFingerSignal = finger,
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
        fun makeSignals(lagMs: Double, noiseAmp: Double): Triple<DoubleArray, DoubleArray, Pair<DoubleArray, DoubleArray>> {
            val lagSamples = (lagMs * fs / 1000.0).toInt()
            
            // Generate clean signals (simulating filtered output)
            val faceClean = DoubleArray(n) { i ->
                kotlin.math.sin(2 * Math.PI * 1.3 * (i / fs))
            }
            val fingerClean = DoubleArray(n) { i ->
                val idx = i - lagSamples
                val base = if (idx >= 0) faceClean[idx] else 0.0
                base
            }

            // Generate noisy signals (raw input)
            // Noise is 9Hz (high freq) which would be removed by 4Hz lowpass
            val faceNoisy = DoubleArray(n) { i ->
                faceClean[i] + noiseAmp * kotlin.math.sin(2 * Math.PI * 9.0 * (i / fs))
            }
            val fingerNoisy = DoubleArray(n) { i ->
                fingerClean[i] + noiseAmp * kotlin.math.sin(2 * Math.PI * 7.0 * (i / fs))
            }
            
            return Triple(faceClean, fingerClean, faceNoisy to fingerNoisy)
        }

        val (f1Clean, g1Clean, raw1) = makeSignals(70.0, 0.3)
        val s1 = ProcessedSeries(
            timeMillis = (0 until n).map { it * 10.0 },
            faceSignal = f1Clean,
            fingerSignal = g1Clean,
            rawFaceSignal = raw1.first,
            rawFingerSignal = raw1.second,
            sampleRateHz = fs,
            isValid = true
        )
        val r1 = PttCalculator.computePtt(s1)
        assertTrue(r1.isValid)
        assertTrue(abs(r1.pttMs - 70.0) <= 10.0)

        val (f2Clean, g2Clean, raw2) = makeSignals(50.0, 0.3)
        val s2 = ProcessedSeries(
            timeMillis = (0 until n).map { it * 10.0 },
            faceSignal = f2Clean,
            fingerSignal = g2Clean,
            rawFaceSignal = raw2.first,
            rawFingerSignal = raw2.second,
            sampleRateHz = fs,
            isValid = true
        )
        val r2 = PttCalculator.computePtt(s2)
        assertTrue(r2.isValid)
        // Monotonic: reduced true lag -> reduced measured PTT
        assertTrue(r2.pttMs < r1.pttMs)
    }

    @Test
    fun knownLag_withNoiseAndHarmonics_errorUnder15ms() {
        // Realistic test: cardiac harmonics + moderate noise on both channels
        // This tests PTT accuracy under non-ideal but plausible conditions.
        val fs = 100.0
        val n = 5000
        val lagMs = 75.0
        val lagSamples = (lagMs * fs / 1000.0).toInt()
        val rng = java.util.Random(42)

        // Generate realistic face signal: fundamental + harmonic (cardiac-like)
        val cleanFace = DoubleArray(n) { i ->
            kotlin.math.sin(2 * Math.PI * 1.2 * (i / fs)) +
            0.3 * kotlin.math.sin(2 * Math.PI * 2.4 * (i / fs))
        }

        // Finger = delayed face + independent noise (post-filtering SNR ~16)
        val noisyFinger = DoubleArray(n) { i ->
            val idx = i - lagSamples
            val base = if (idx >= 0) cleanFace[idx] else 0.0
            base + 0.08 * rng.nextGaussian()
        }

        // Face with different noise realization
        val noisyFace = DoubleArray(n) { i ->
            cleanFace[i] + 0.08 * rng.nextGaussian()
        }

        val times = (0 until n).map { it * (1000.0 / fs) }

        // Apply simple filtering (moving average, window=5) to simulate pipeline preprocessing
        fun simpleFilter(data: DoubleArray): DoubleArray {
            val out = DoubleArray(data.size)
            for (i in 2 until data.size - 2) {
                out[i] = (data[i-2] + data[i-1] + data[i] + data[i+1] + data[i+2]) / 5.0
            }
            return out
        }
        
        val faceFiltered = simpleFilter(noisyFace)
        val fingerFiltered = simpleFilter(noisyFinger)

        val series = ProcessedSeries(
            timeMillis = times,
            faceSignal = faceFiltered,
            fingerSignal = fingerFiltered,
            rawFaceSignal = noisyFace,
            rawFingerSignal = noisyFinger,
            sampleRateHz = fs,
            isValid = true
        )
        val result = PttCalculator.computePtt(series)
        assertTrue("Result should be valid with moderate noise", result.isValid)
        assertTrue(
            "PTT error ${abs(result.pttMs - lagMs)}ms should be < 15ms",
            abs(result.pttMs - lagMs) <= 15.0
        )
    }
}


