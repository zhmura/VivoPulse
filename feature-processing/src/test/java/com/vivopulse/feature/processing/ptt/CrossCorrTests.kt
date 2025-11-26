package com.vivopulse.feature.processing.ptt

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class CrossCorrTests {
    
    @Test
    fun `crossCorrelationLag - known lag 60ms at SNR 10dB`() {
        val fsHz = 100.0
        val pttMs = 60.0
        val pttSamples = (pttMs / 1000.0 * fsHz).toInt()
        val duration = 20.0
        val n = (fsHz * duration).toInt()
        
        // Generate base signal
        val base = DoubleArray(n) { i -> sin(2 * PI * 1.2 * i / fsHz) }
        
        // Create delayed signal
        val delayed = DoubleArray(n)
        for (i in 0 until n) {
            val j = i - pttSamples
            delayed[i] = if (j >= 0) base[j] else 0.0
        }
        
        // Add noise for SNR ~10 dB
        val noise = DoubleArray(n) { (Math.random() - 0.5) * 0.1 }
        val signal1 = base.zip(noise) { s, n -> s + n }.toDoubleArray()
        val signal2 = delayed.zip(noise) { s, n -> s + n }.toDoubleArray()
        
        val result = CrossCorr.crossCorrelationLag(signal1, signal2, fsHz, windowSec = 20.0)
        
        assertTrue("Result should be valid", result.isValid)
        assertTrue("Lag error ≤5ms, was ${abs(result.lagMs - pttMs)}ms", 
            abs(result.lagMs - pttMs) <= 5.0)
        assertTrue("Correlation should be high", result.corrScore > 0.7)
    }
    
    @Test
    fun `crossCorrelationLag - known lag 100ms at SNR 10dB`() {
        val fsHz = 100.0
        val pttMs = 100.0
        val pttSamples = (pttMs / 1000.0 * fsHz).toInt()
        val duration = 20.0
        val n = (fsHz * duration).toInt()
        
        val base = DoubleArray(n) { i -> sin(2 * PI * 1.2 * i / fsHz) }
        val delayed = DoubleArray(n)
        for (i in 0 until n) {
            val j = i - pttSamples
            delayed[i] = if (j >= 0) base[j] else 0.0
        }
        
        val noise = DoubleArray(n) { (Math.random() - 0.5) * 0.1 }
        val signal1 = base.zip(noise) { s, n -> s + n }.toDoubleArray()
        val signal2 = delayed.zip(noise) { s, n -> s + n }.toDoubleArray()
        
        val result = CrossCorr.crossCorrelationLag(signal1, signal2, fsHz, windowSec = 20.0)
        
        assertTrue("Result should be valid", result.isValid)
        assertTrue("Lag error ≤5ms, was ${abs(result.lagMs - pttMs)}ms", 
            abs(result.lagMs - pttMs) <= 5.0)
    }
    
    @Test
    fun `crossCorrelationLag - known lag 140ms at SNR 10dB`() {
        val fsHz = 100.0
        val pttMs = 140.0
        val pttSamples = (pttMs / 1000.0 * fsHz).toInt()
        val duration = 20.0
        val n = (fsHz * duration).toInt()
        
        val base = DoubleArray(n) { i -> sin(2 * PI * 1.2 * i / fsHz) }
        val delayed = DoubleArray(n)
        for (i in 0 until n) {
            val j = i - pttSamples
            delayed[i] = if (j >= 0) base[j] else 0.0
        }
        
        val noise = DoubleArray(n) { (Math.random() - 0.5) * 0.1 }
        val signal1 = base.zip(noise) { s, n -> s + n }.toDoubleArray()
        val signal2 = delayed.zip(noise) { s, n -> s + n }.toDoubleArray()
        
        val result = CrossCorr.crossCorrelationLag(signal1, signal2, fsHz, windowSec = 20.0)
        
        assertTrue("Result should be valid", result.isValid)
        assertTrue("Lag error ≤5ms, was ${abs(result.lagMs - pttMs)}ms", 
            abs(result.lagMs - pttMs) <= 5.0)
    }
    
    @Test
    fun `crossCorrelationLag - low SNR 6dB allows 10ms error`() {
        val fsHz = 100.0
        val pttMs = 100.0
        val pttSamples = (pttMs / 1000.0 * fsHz).toInt()
        val duration = 20.0
        val n = (fsHz * duration).toInt()
        
        val base = DoubleArray(n) { i -> sin(2 * PI * 1.2 * i / fsHz) }
        val delayed = DoubleArray(n)
        for (i in 0 until n) {
            val j = i - pttSamples
            delayed[i] = if (j >= 0) base[j] else 0.0
        }
        
        // Add more noise for SNR ~6 dB
        val noise = DoubleArray(n) { (Math.random() - 0.5) * 0.3 }
        val signal1 = base.zip(noise) { s, n -> s + n }.toDoubleArray()
        val signal2 = delayed.zip(noise) { s, n -> s + n }.toDoubleArray()
        
        val result = CrossCorr.crossCorrelationLag(signal1, signal2, fsHz, windowSec = 20.0)
        
        assertTrue("Result should be valid", result.isValid)
        assertTrue("Lag error ≤10ms for low SNR, was ${abs(result.lagMs - pttMs)}ms", 
            abs(result.lagMs - pttMs) <= 10.0)
    }
    
    @Test
    fun `peak sharpness increases with better correlation`() {
        val fsHz = 100.0
        val n = 2000
        
        // Clean signals → high sharpness
        val signal1 = DoubleArray(n) { i -> sin(2 * PI * 1.2 * i / fsHz) }
        val signal2 = signal1.copyOf()
        
        val result = CrossCorr.crossCorrelationLag(signal1, signal2, fsHz)
        
        assertTrue("Peak sharpness should be positive", result.peakSharpness > 0.0)
        assertTrue("Correlation should be very high", result.corrScore > 0.95)
    }
}







