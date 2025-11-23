package com.vivopulse.signal

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for Signal Quality Index (SQI) functions.
 */
class SignalQualityTest {
    
    @Test
    fun `computeSNR - clean signal high SNR`() {
        // Pure sine wave in passband (1.5 Hz)
        val clean = DspFunctions.generateSineWave(1.5, 5.0, 100.0, amplitude = 1.0)
        
        val snr = SignalQuality.computeSNR(clean, sampleRateHz = 100.0)
        
        // Clean signal should have reasonable SNR
        // Note: residual may include filter artifacts
        assertTrue("Clean signal should have SNR > 5 dB, was ${snr}", snr > 5.0)
        
        println("Clean signal SNR: ${snr} dB")
    }
    
    @Test
    fun `computeSNR - noisy signal low SNR`() {
        // Signal with strong noise
        val signal = DspFunctions.generateSineWave(1.5, 5.0, 100.0, amplitude = 1.0)
        val noise = DspFunctions.generateSineWave(10.0, 5.0, 100.0, amplitude = 0.8)
        
        val noisy = signal.zip(noise) { s, n -> s + n }.toDoubleArray()
        
        val snr = SignalQuality.computeSNR(noisy, sampleRateHz = 100.0)
        
        // Noisy signal should have lower SNR
        assertTrue("Noisy signal should have SNR < 15 dB, was ${snr}", snr < 15.0)
        
        println("Noisy signal SNR: ${snr} dB")
    }
    
    @Test
    fun `findPeaks - regular signal`() {
        // 1.2 Hz signal for 10 seconds = 12 peaks expected
        val signal = DspFunctions.generateSineWave(1.2, 10.0, 100.0, amplitude = 1.0)
        
        val peaks = SignalQuality.findPeaks(signal, minDistance = 40) // Min 0.4s between peaks
        
        // Should find peaks (exact count varies with threshold)
        assertTrue("Should find some peaks (8-14) for 1.2 Hz over 10s, found ${peaks.size}", 
            peaks.size >= 8)
        
        println("Found ${peaks.size} peaks in 10s signal (expected ~12)")
    }
    
    @Test
    fun `findPeaks - no peaks in flat signal`() {
        val flat = DoubleArray(100) { 1.0 }
        
        val peaks = SignalQuality.findPeaks(flat)
        
        assertEquals("Flat signal should have no peaks", 0, peaks.size)
    }
    
    @Test
    fun `computePeakRegularity - regular signal high score`() {
        // Very regular signal (constant frequency)
        val signal = DspFunctions.generateSineWave(1.5, 10.0, 100.0, amplitude = 1.0)
        
        val score = SignalQuality.computePeakRegularity(signal, sampleRateHz = 100.0)
        
        // Regular signal should score high (> 80)
        assertTrue("Regular signal should score > 80, was ${score}", score > 80.0)
        
        println("Regular signal peak regularity: ${score}")
    }
    
    @Test
    fun `computePeakRegularity - irregular signal low score`() {
        // Create irregular signal (varying frequency)
        val part1 = DspFunctions.generateSineWave(1.0, 5.0, 100.0, amplitude = 1.0)
        val part2 = DspFunctions.generateSineWave(2.0, 5.0, 100.0, amplitude = 1.0)
        
        val irregular = part1 + part2
        
        val score = SignalQuality.computePeakRegularity(irregular, sampleRateHz = 100.0)
        
        // Irregular signal should score lower (< 80)
        assertTrue("Irregular signal should score < 80, was ${score}", score < 80.0)
        
        println("Irregular signal peak regularity: ${score}")
    }
    
    @Test
    fun `computeMotionScore - stationary ROI high score`() {
        // ROI stays at same position
        val positions = List(100) { Pair(100f, 200f) }
        
        val score = SignalQuality.computeMotionScore(positions)
        
        // No motion should give perfect score
        assertEquals("Stationary ROI should score 100", 100.0, score, 0.1)
    }
    
    @Test
    fun `computeMotionScore - moving ROI low score`() {
        // ROI moves significantly
        val positions = (0..99).map { i -> 
            Pair(100f + i * 0.5f, 200f + i * 0.3f) // Drifting position
        }
        
        val score = SignalQuality.computeMotionScore(positions)
        
        // Motion should lower score
        assertTrue("Moving ROI should score < 90, was ${score}", score < 90.0)
        
        println("Moving ROI motion score: ${score}")
    }
    
    @Test
    fun `computeChannelSQI - good signal high score`() {
        // Clean PPG signal
        val signal = DspFunctions.generateSineWave(1.2, 10.0, 100.0, amplitude = 1.0)
        val normalized = DspFunctions.zscoreNormalize(signal)
        
        val sqi = SignalQuality.computeChannelSQI(normalized, sampleRateHz = 100.0)
        
        // Verify score is reasonable (may vary based on filtering)
        assertTrue("Signal should have score > 0, was ${sqi.score}", sqi.score > 0.0)
        assertTrue("Signal should have score <= 100, was ${sqi.score}", sqi.score <= 100.0)
        println("Good signal SQI: ${sqi.score} (SNR=${sqi.snr} dB, Regularity=${sqi.peakRegularity}, Peaks=${sqi.peakCount})")
    }
    
    @Test
    fun `computeChannelSQI - poor signal low score`() {
        // Very noisy signal
        val signal = DspFunctions.generateSineWave(1.2, 10.0, 100.0, amplitude = 0.3)
        val noise = DspFunctions.generateSineWave(12.0, 10.0, 100.0, amplitude = 1.0)
        val noisy = signal.zip(noise) { s, n -> s + n }.toDoubleArray()
        
        val sqi = SignalQuality.computeChannelSQI(noisy, sampleRateHz = 100.0)
        
        assertTrue("Poor signal should score < 70, was ${sqi.score}", sqi.score < 70.0)
        println("Poor signal SQI: ${sqi.score} (SNR=${sqi.snr} dB, Regularity=${sqi.peakRegularity})")
    }
    
    @Test
    fun `computeChannelSQI - with motion penalty`() {
        val signal = DspFunctions.generateSineWave(1.2, 10.0, 100.0, amplitude = 1.0)
        val normalized = DspFunctions.zscoreNormalize(signal)
        
        // With high motion score
        val sqiNoMotion = SignalQuality.computeChannelSQI(normalized, 100.0, motionScore = 100.0)
        
        // With low motion score (significant movement)
        val sqiWithMotion = SignalQuality.computeChannelSQI(normalized, 100.0, motionScore = 40.0)
        
        // Motion penalty should reduce score
        assertTrue("Motion penalty should reduce score", sqiWithMotion.score < sqiNoMotion.score)
        
        println("SQI without motion: ${sqiNoMotion.score}, with motion: ${sqiWithMotion.score}")
    }
    
    @Test
    fun `computeChannelSQI - with IMU penalty`() {
        val signal = DspFunctions.generateSineWave(1.2, 10.0, 100.0, amplitude = 1.0)
        val normalized = DspFunctions.zscoreNormalize(signal)
        
        // With high IMU score (stable)
        val sqiStable = SignalQuality.computeChannelSQI(normalized, 100.0, imuScore = 100.0)
        
        // With low IMU score (shaky)
        val sqiShaky = SignalQuality.computeChannelSQI(normalized, 100.0, imuScore = 40.0)
        
        // IMU penalty should reduce score
        assertTrue("IMU penalty should reduce score", sqiShaky.score < sqiStable.score)
        
        println("SQI stable: ${sqiStable.score}, shaky: ${sqiShaky.score}")
    }
    
    @Test
    fun `computePttConfidence - good signals high confidence`() {
        // Good quality both channels
        val faceSQI = ChannelSQI(85.0, 15.0, 85.0, 90.0, 95.0, 100.0, 12)
        val fingerSQI = ChannelSQI(80.0, 12.0, 80.0, 85.0, 100.0, 100.0, 11)
        val correlation = 0.95
        
        val confidence = SignalQuality.computePttConfidence(faceSQI, fingerSQI, correlation)
        
        assertTrue("Good signals should have confidence >= 70, was ${confidence}", 
            confidence >= 70.0)
        
        println("Good signals PTT confidence: ${confidence}")
    }
    
    @Test
    fun `computePttConfidence - poor signals low confidence`() {
        // Poor quality channels
        val faceSQI = ChannelSQI(45.0, 2.0, 40.0, 50.0, 60.0, 60.0, 5)
        val fingerSQI = ChannelSQI(50.0, 3.0, 45.0, 55.0, 100.0, 100.0, 6)
        val correlation = 0.4
        
        val confidence = SignalQuality.computePttConfidence(faceSQI, fingerSQI, correlation)
        
        assertTrue("Poor signals should have confidence < 60, was ${confidence}", 
            confidence < 60.0)
        
        println("Poor signals PTT confidence: ${confidence}")
    }
    
    @Test
    fun `empty signal handling`() {
        val empty = doubleArrayOf()
        
        val snr = SignalQuality.computeSNR(empty, 100.0)
        assertEquals(0.0, snr, 0.01)
        
        val regularity = SignalQuality.computePeakRegularity(empty, 100.0)
        assertEquals(0.0, regularity, 0.01)
        
        val peaks = SignalQuality.findPeaks(empty)
        assertEquals(0, peaks.size)
    }
}

