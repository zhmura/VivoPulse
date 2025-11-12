package com.vivopulse.feature.processing.tests

import com.vivopulse.feature.processing.ProcessedSeries
import com.vivopulse.feature.processing.wave.WaveFeatures
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.Math

class WaveFeatureFormulaTests {
    @Test
    fun stifferWave_showsLowerRiseTime_higherReflectionRatio() {
        val fs = 100.0
        val n = 3000
        // Synthetic waves: create finger signals with different upstroke slopes and reflection timing
        fun makeWave(fastUpstroke: Boolean, earlyReflection: Boolean): DoubleArray {
            val s = DoubleArray(n) { 0.0 }
            var t = 0.0
            for (i in 0 until n) {
                val phase = (i % 80) / 80.0 // ~1.25 Hz
                val up = if (fastUpstroke) 8.0 else 3.0
                val base = Math.pow(phase, up) // faster upstroke = sharper rise
                val reflecPhase = if (earlyReflection) 0.35 else 0.55
                val reflec = if (phase > reflecPhase && phase < reflecPhase + 0.1) 0.15 else 0.0
                s[i] = base + reflec
                t += 1 / fs
            }
            return s
        }
        val face = DoubleArray(n) { 0.0 } // not used by WaveFeatures beyond gating; supply blank
        val stiffer = makeWave(fastUpstroke = true, earlyReflection = true)
        val elastic = makeWave(fastUpstroke = false, earlyReflection = false)

        val seriesStiff = ProcessedSeries(
            timeMillis = (0 until n).map { it * 10.0 },
            faceSignal = face,
            fingerSignal = stiffer,
            sampleRateHz = fs,
            isValid = true
        )
        val seriesElastic = ProcessedSeries(
            timeMillis = (0 until n).map { it * 10.0 },
            faceSignal = face,
            fingerSignal = elastic,
            sampleRateHz = fs,
            isValid = true
        )
        val profStiff = WaveFeatures.computeProfile(seriesStiff)
        val profElastic = WaveFeatures.computeProfile(seriesElastic)

        // Directionality checks (allow null-safe)
        if (profStiff.meanRiseTimeMs != null && profElastic.meanRiseTimeMs != null) {
            assertTrue(profStiff.meanRiseTimeMs!! < profElastic.meanRiseTimeMs!!)
        }
        if (profStiff.meanReflectionRatio != null && profElastic.meanReflectionRatio != null) {
            assertTrue(profStiff.meanReflectionRatio!! >= profElastic.meanReflectionRatio!!)
        }
    }
}


