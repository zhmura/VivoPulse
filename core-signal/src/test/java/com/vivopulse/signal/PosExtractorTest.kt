package com.vivopulse.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

class PosExtractorTest {

    @Test
    fun `computePosSignal returns correct length array`() {
        val n = 1000
        val fs = 100.0
        val r = DoubleArray(n) { 1.0 }
        val g = DoubleArray(n) { 1.0 }
        val b = DoubleArray(n) { 1.0 }

        val result = PosExtractor.computePosSignal(r, g, b, fs)
        assertEquals(n, result.size)
    }

    @Test
    fun `computePosSignal extracts pulse from modulated green channel`() {
        val n = 500
        val fs = 50.0
        val freq = 1.0 // 1 Hz pulse
        
        // Synthesize RGB: 
        // Skin reflection model suggests pulse is strongest in Green
        // We simulate a static color with a small AC component in Green
        
        val r = DoubleArray(n) { 1.0 }
        // Green has pulse: 1.0 + 0.01 * sin(wt)
        val g = DoubleArray(n) { i -> 
            1.0 + 0.01 * sin(2 * PI * freq * i / fs)
        }
        val b = DoubleArray(n) { 1.0 }

        val pos = PosExtractor.computePosSignal(r, g, b, fs)
        
        // POS should recover the AC signal shape
        // We check correlation with expected sine wave
        val reference = DoubleArray(n) { i -> sin(2 * PI * freq * i / fs) }
        
        // Compute correlation
        val corr = computeCorrelation(pos, reference)
        
        // POS output might be inverted depending on projection, so we check abs correlation
        assertTrue("Correlation should be high: $corr", kotlin.math.abs(corr) > 0.8)
    }
    
    @Test
    fun `computePosSignal handles empty input`() {
        val result = PosExtractor.computePosSignal(DoubleArray(0), DoubleArray(0), DoubleArray(0), 100.0)
        assertEquals(0, result.size)
    }

    private fun computeCorrelation(s1: DoubleArray, s2: DoubleArray): Double {
        if (s1.size != s2.size || s1.isEmpty()) return 0.0
        
        val m1 = s1.average()
        val m2 = s2.average()
        
        var num = 0.0
        var den1 = 0.0
        var den2 = 0.0
        
        for (i in s1.indices) {
            val d1 = s1[i] - m1
            val d2 = s2[i] - m2
            num += d1 * d2
            den1 += d1 * d1
            den2 += d2 * d2
        }
        
        if (den1 == 0.0 || den2 == 0.0) return 0.0
        return num / kotlin.math.sqrt(den1 * den2)
    }
}
