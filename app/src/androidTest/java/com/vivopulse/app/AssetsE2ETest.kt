package com.vivopulse.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue
import kotlin.math.abs
import com.vivopulse.signal.DspFunctions
import com.vivopulse.signal.SignalQuality
import com.vivopulse.core.BuildConfig
import com.vivopulse.feature.processing.PttCalculator
import com.vivopulse.feature.processing.ProcessedSeries

@RunWith(AndroidJUnit4::class)
class AssetsE2ETest {

    @Test
    fun prerecordedAssets_hrAndPttWithinTolerances() {
        // Generate synthetic CSV-like arrays (avoids large static assets)
        val fs = 100.0
        val duration = 10.0
        val n = (fs * duration).toInt()
        val pttMs = 100.0
        val pttSamples = (pttMs / 1000.0 * fs).toInt()

        val face = DoubleArray(n) { i -> kotlin.math.sin(2 * Math.PI * 1.2 * i / fs) }
        val finger = DoubleArray(n)
        for (i in 0 until n) {
            val j = i - pttSamples
            finger[i] = if (j >= 0) face[j] else 0.0
        }

        // Process similar to pipeline (bandpass + z-score)
        val faceFiltered = DspFunctions.zscoreNormalize(
            DspFunctions.butterworthBandpass(face, 0.7, 4.0, fs, order = 2)
        )
        val fingerFiltered = DspFunctions.zscoreNormalize(
            DspFunctions.butterworthBandpass(finger, 0.7, 4.0, fs, order = 2)
        )

        // Compute correlation-based lag using PttCalculator lower-level API
        val series = ProcessedSeries(
            timeMs = DoubleArray(n) { i -> i * 1000.0 / fs },
            face = faceFiltered,
            finger = fingerFiltered,
            sampleRateHz = fs,
            isValid = true
        )
        val ptt = PttCalculator.computePtt(series)

        assertTrue("corrScore ≥ 0.9 expected, was ${ptt.corrScore}", ptt.corrScore >= 0.9)
        assertTrue("PTT within ±8 ms expected, was ${ptt.lagMs}", abs(ptt.lagMs - pttMs) <= 8.0)
    }
}


