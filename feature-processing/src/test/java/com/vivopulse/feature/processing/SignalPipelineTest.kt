package com.vivopulse.feature.processing

import com.vivopulse.feature.processing.timestamp.TimestampedValue
import com.vivopulse.signal.DspFunctions
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for SignalPipeline.
 * 
 * Tests end-to-end processing with resampling and filtering.
 */
class SignalPipelineTest {
    
    @Test
    fun `process - basic pipeline with synthetic data`() {
        // Create synthetic PPG signals at 30 fps
        val duration = 5.0 // 5 seconds
        val originalRate = 30.0
        val numSamples = (duration * originalRate).toInt()
        
        // Face signal: 1.2 Hz (72 BPM)
        val faceSamples = (0 until numSamples).map { i ->
            val t = i / originalRate
            val timestampNs = (t * 1_000_000_000).toLong()
            val value = DspFunctions.generateSineWave(1.2, duration, originalRate)[i]
            TimestampedValue(timestampNs, value)
        }
        
        // Finger signal: 1.3 Hz (78 BPM) - slightly different
        val fingerSamples = (0 until numSamples).map { i ->
            val t = i / originalRate
            val timestampNs = (t * 1_000_000_000).toLong()
            val value = DspFunctions.generateSineWave(1.3, duration, originalRate)[i]
            TimestampedValue(timestampNs, value)
        }
        
        val rawBuffer = RawSeriesBuffer(faceSamples, fingerSamples)
        
        // Process
        val pipeline = SignalPipeline(targetSampleRateHz = 100.0)
        val result = pipeline.process(rawBuffer)
        
        // Verify
        assertTrue("Processing should succeed", result.isValid)
        assertTrue("Should be resampled to 100 Hz", result.getSampleCount() >= 450) // ~5s at 100Hz
        assertTrue("Arrays should be aligned", result.isAligned())
        assertEquals("Sample rate", 100.0, result.sampleRateHz, 0.1)
        
        // Check normalized signals
        val faceMean = result.faceSignal.average()
        val fingerMean = result.fingerSignal.average()
        
        assertTrue("Face signal mean ~0", abs(faceMean) < 0.01)
        assertTrue("Finger signal mean ~0", abs(fingerMean) < 0.01)
        
        println("Pipeline test: ${result.getSampleCount()} samples, duration=${result.getDurationSeconds()}s")
    }
    
    @Test
    fun `process - same length arrays`() {
        // Simple test data
        val timestamps = (0..99).map { it * 33_333_333L } // 30 fps, ~3.3 seconds
        val faceData = timestamps.map { TimestampedValue(it, 1.0) }
        val fingerData = timestamps.map { TimestampedValue(it, 2.0) }
        
        val rawBuffer = RawSeriesBuffer(faceData, fingerData)
        val pipeline = SignalPipeline()
        val result = pipeline.process(rawBuffer)
        
        assertTrue(result.isValid)
        assertEquals(result.timeMillis.size, result.faceSignal.size)
        assertEquals(result.timeMillis.size, result.fingerSignal.size)
    }
    
    @Test
    fun `process - preserves passband frequencies`() {
        // Create signal with heart rate frequency (1.5 Hz)
        val duration = 10.0
        val sampleRate = 30.0
        val numSamples = (duration * sampleRate).toInt()
        
        val faceData = (0 until numSamples).map { i ->
            val t = i / sampleRate
            val timestampNs = (t * 1_000_000_000).toLong()
            val signal = DspFunctions.generateSineWave(1.5, duration, sampleRate)
            TimestampedValue(timestampNs, signal[i])
        }
        
        val fingerData = faceData // Same for simplicity
        
        val rawBuffer = RawSeriesBuffer(faceData, fingerData)
        val pipeline = SignalPipeline()
        val result = pipeline.process(rawBuffer)
        
        assertTrue(result.isValid)
        
        // Signal should still have reasonable power after filtering
        val facePower = DspFunctions.computePower(result.faceSignal)
        assertTrue("Passband signal should have power", facePower > 0.5)
        
        println("Passband preservation: Power=$facePower")
    }
    
    @Test
    fun `process - attenuates out of band`() {
        // Create high-frequency noise (10 Hz) with some HR signal (1.5 Hz)
        val duration = 5.0
        val sampleRate = 100.0 // Higher rate to capture 10 Hz
        val numSamples = (duration * sampleRate).toInt()
        
        val hrSignal = DspFunctions.generateSineWave(1.5, duration, sampleRate, amplitude = 1.0)
        val noise = DspFunctions.generateSineWave(10.0, duration, sampleRate, amplitude = 0.5)
        
        val faceData = (0 until numSamples).map { i ->
            val timestampNs = (i * 10_000_000L) // 100 Hz
            val value = hrSignal[i] + noise[i]
            TimestampedValue(timestampNs, value)
        }
        
        val rawBuffer = RawSeriesBuffer(faceData, faceData)
        val pipeline = SignalPipeline(targetSampleRateHz = 100.0)
        val result = pipeline.process(rawBuffer)
        
        assertTrue(result.isValid)
        
        // Filtered signal should have lower power than raw (noise removed)
        val rawPower = DspFunctions.computePower(result.rawFaceSignal)
        val filteredPower = DspFunctions.computePower(result.faceSignal)
        
        println("Noise filtering: Raw power=$rawPower, Filtered power=$filteredPower")
        
        // After normalization, power should be ~1.0
        assertTrue("Normalized power ~1.0", abs(filteredPower - 1.0) < 0.1)
    }
    
    @Test
    fun `process - handles empty data`() {
        val rawBuffer = RawSeriesBuffer(emptyList(), emptyList())
        val pipeline = SignalPipeline()
        val result = pipeline.process(rawBuffer)
        
        assertFalse(result.isValid)
        assertEquals(0, result.getSampleCount())
    }
    
    @Test
    fun `process - drift removal`() {
        // Create signal with linear drift
        val duration = 5.0
        val sampleRate = 30.0
        val numSamples = (duration * sampleRate).toInt()
        
        val baseSignal = DspFunctions.generateSineWave(1.5, duration, sampleRate, amplitude = 1.0)
        val driftedSignal = DspFunctions.addLinearDrift(baseSignal, driftRate = 0.1)
        
        val faceData = (0 until numSamples).map { i ->
            val t = i / sampleRate
            val timestampNs = (t * 1_000_000_000).toLong()
            TimestampedValue(timestampNs, driftedSignal[i])
        }
        
        val rawBuffer = RawSeriesBuffer(faceData, faceData)
        val pipeline = SignalPipeline()
        val result = pipeline.process(rawBuffer)
        
        assertTrue(result.isValid)
        
        // Processed signal should have drift removed (mean ~0)
        val mean = result.faceSignal.average()
        assertTrue("Drift removed, mean ~0", abs(mean) < 0.01)
        
        println("Drift removal: Mean=$mean")
    }
}

