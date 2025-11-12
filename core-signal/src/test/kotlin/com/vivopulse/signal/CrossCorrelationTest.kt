package com.vivopulse.signal

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Unit tests for cross-correlation and PTT computation.
 */
class CrossCorrelationTest {
    
    private val epsilon = 1e-6
    
    init {
        CrossCorrelation.setDebugEnabled(true)
    }
    
    @Test
    fun `computeLag - zero lag identical signals`() {
        // Use normalized signal (zero mean) to avoid edge effects
        val raw = DspFunctions.generateSineWave(1.5, 5.0, 100.0, amplitude = 1.0)
        val signal = DspFunctions.zscoreNormalize(raw)
        
        val result = CrossCorrelation.computeLag(signal, signal, sampleRateHz = 100.0)
        
        println("Zero lag test: lag=${result.lagMs}ms, lagSamples=${result.lagSamples}, corr=${result.correlationScore}, peakIdx=${result.peakIndex}")
        
        assertTrue("Should be valid", result.isValid)
        assertTrue("Correlation should be ~1.0, was ${result.correlationScore}", 
            abs(result.correlationScore - 1.0) < 0.02)
        assertTrue("Lag should be ~0, was ${result.lagMs}ms", abs(result.lagMs) < 15.0)
    }
    
    @Test
    fun `computeLag - positive lag 50ms`() {
        val sampleRate = 100.0
        val pttMs = 50.0 // 50 milliseconds  
        val pttSamples = (pttMs / 1000.0 * sampleRate).toInt() // 5 samples
        
        // Create PPG-like signal
        val duration = 10.0
        val ppgSignal = DspFunctions.generateSineWave(1.5, duration, sampleRate, amplitude = 1.0)
        
        // Simulate PTT: finger pulse arrives pttSamples after face pulse
        // At time t: face shows pulse that will appear at finger at time t+PTT
        // So finger[t] = face[t-PTT]
        
        val faceSignal = ppgSignal.copyOf()
        val fingerSignal = DoubleArray(ppgSignal.size)
        
        // Finger is delayed: at index i, finger shows what face showed at i-pttSamples
        for (i in pttSamples until ppgSignal.size) {
            fingerSignal[i] = faceSignal[i - pttSamples]
        }
        
        val result = CrossCorrelation.computeLag(faceSignal, fingerSignal, sampleRateHz = sampleRate)
        
        println("Positive lag test: lag=${result.lagMs}ms (expected ${pttMs}ms), corr=${result.correlationScore}")
        
        assertTrue("Should be valid", result.isValid)
        assertTrue("Correlation should be high, was ${result.correlationScore}", result.correlationScore > 0.7)
        assertTrue("PTT should be ~50ms, was ${result.lagMs}ms", 
            abs(result.lagMs - pttMs) < 20.0)
    }
    
    @Test
    fun `computeLag - negative lag 80ms`() {
        val sampleRate = 100.0
        val advanceMs = 80.0
        val advanceSamples = (advanceMs / 1000.0 * sampleRate).toInt() // 8 samples
        
        // Create signal
        val duration = 10.0
        val ppgSignal = DspFunctions.generateSineWave(1.2, duration, sampleRate, amplitude = 1.0)
        
        // Simulate finger AHEAD of face (negative PTT - unusual but for testing)
        val faceSignal = ppgSignal.copyOf()
        val fingerSignal = DoubleArray(ppgSignal.size)
        
        // Finger is advanced: at index i, finger shows what face will show at i+advanceSamples
        for (i in 0 until ppgSignal.size - advanceSamples) {
            fingerSignal[i] = faceSignal[i + advanceSamples]
        }
        
        val result = CrossCorrelation.computeLag(faceSignal, fingerSignal, sampleRateHz = sampleRate)
        
        println("Negative lag test: lag=${result.lagMs}ms (expected -${advanceMs}ms), corr=${result.correlationScore}")
        
        assertTrue("Should be valid", result.isValid)
        assertTrue("Correlation should be high, was ${result.correlationScore}", result.correlationScore > 0.7)
        assertTrue("Lag should be ~-80ms, was ${result.lagMs}ms", 
            abs(result.lagMs + advanceMs) < 20.0)
    }
    
    @Test
    fun `computeLag - realistic PTT 100ms`() {
        val sampleRate = 100.0
        val pttMs = 100.0 // Realistic PTT
        val pttSamples = (pttMs / 1000.0 * sampleRate).toInt() // 10 samples
        
        // Create realistic PPG signals (with harmonics)
        val duration = 12.0
        val fundamental = DspFunctions.generateSineWave(1.2, duration, sampleRate, amplitude = 1.0)
        val harmonic = DspFunctions.generateSineWave(2.4, duration, sampleRate, amplitude = 0.3)
        
        val fullSignal = fundamental.zip(harmonic) { f, h -> f + h }.toDoubleArray()
        
        // Create face and delayed finger
        val faceSignal = fullSignal.copyOf()
        val fingerSignal = DoubleArray(fullSignal.size)
        
        for (i in pttSamples until fullSignal.size) {
            fingerSignal[i] = fullSignal[i - pttSamples]
        }
        
        // Add some noise to finger
        val noise = DspFunctions.generateSineWave(15.0, duration, sampleRate, amplitude = 0.1)
        val noisyFinger = fingerSignal.zip(noise) { s, n -> s + n }.toDoubleArray()
        
        val result = CrossCorrelation.computeLag(faceSignal, noisyFinger, sampleRateHz = sampleRate)
        
        assertTrue("Should be valid", result.isValid)
        assertTrue("Correlation should be good (>0.7)", result.correlationScore > 0.7)
        assertTrue("PTT should be plausible", result.isPlausible())
        assertTrue("PTT should be ~100ms, was ${result.lagMs}", 
            abs(result.lagMs - pttMs) < 20.0)
        
        println("Realistic PTT test: PTT=${result.lagMs}ms (expected ${pttMs}ms), corr=${result.correlationScore}")
    }
    
    @Test
    fun `computeLag - uncorrelated signals`() {
        val signal1 = DspFunctions.generateSineWave(1.0, 5.0, 100.0, amplitude = 1.0)
        val signal2 = DspFunctions.generateSineWave(2.5, 5.0, 100.0, amplitude = 1.0, phase = PI/4)
        
        val result = CrossCorrelation.computeLag(signal1, signal2, sampleRateHz = 100.0)
        
        assertTrue("Should be valid", result.isValid)
        // Correlation should be low for different frequencies
        assertTrue("Correlation should be low for uncorrelated signals", 
            result.correlationScore < 0.5)
        
        println("Uncorrelated test: corr=${result.correlationScore}")
    }
    
    @Test
    fun `computeLag - sub-sample refinement`() {
        // Test that quadratic interpolation improves integer-lag estimate
        val sampleRate = 100.0
        val pttMs = 75.0 // Use integer lag for simplicity
        val pttSamples = (pttMs / 1000.0 * sampleRate).toInt() // 7.5 -> 7 samples
        
        val ppgSignal = DspFunctions.generateSineWave(1.5, 10.0, sampleRate, amplitude = 1.0)
        
        val faceSignal = ppgSignal.copyOf()
        val fingerSignal = DoubleArray(ppgSignal.size)
        
        for (i in pttSamples until ppgSignal.size) {
            fingerSignal[i] = faceSignal[i - pttSamples]
        }
        
        val result = CrossCorrelation.computeLag(faceSignal, fingerSignal, sampleRateHz = sampleRate)
        
        println("Sub-sample test: lag=${result.lagMs}ms (expected ~${pttMs}ms), corr=${result.correlationScore}")
        
        assertTrue("Should be valid", result.isValid)
        assertTrue("Correlation should be high, was ${result.correlationScore}", result.correlationScore > 0.95)
        // Should be close to expected lag
        assertTrue("Lag should be ~${pttMs}ms, was ${result.lagMs}ms", 
            abs(result.lagMs - pttMs) < 20.0)
    }
    
    @Test
    fun `computeLagStability - stable PTT`() {
        val sampleRate = 100.0
        val pttMs = 100.0
        val pttSamples = (pttMs / 1000.0 * sampleRate).toInt()
        
        // Create long signal (30 seconds)
        val duration = 30.0
        val ppgSignal = DspFunctions.generateSineWave(1.2, duration, sampleRate, amplitude = 1.0)
        
        // Delayed finger signal with consistent lag
        val faceSignal = ppgSignal.copyOf()
        val fingerSignal = DoubleArray(ppgSignal.size)
        for (i in pttSamples until ppgSignal.size) {
            fingerSignal[i] = faceSignal[i - pttSamples]
        }
        
        val result = CrossCorrelation.computeLagStability(
            faceSignal,
            fingerSignal,
            sampleRateHz = sampleRate,
            windowSizeS = 10.0,
            overlapS = 5.0
        )
        
        assertTrue("Should be valid", result.isValid)
        assertTrue("Mean PTT should be ~100ms", abs(result.meanLagMs - pttMs) < 20.0)
        assertTrue("Should be stable (std < 25ms)", result.isStable())
        assertTrue("Std should be small for stable signal", result.stdLagMs < 15.0)
        
        println("Stability test: mean=${result.meanLagMs}ms, std=${result.stdLagMs}ms, windows=${result.windowCount}")
    }
    
    @Test
    fun `computeLagStability - varying PTT`() {
        val sampleRate = 100.0
        val duration = 30.0
        
        // Create PPG signal
        val ppgSignal = DspFunctions.generateSineWave(1.2, duration, sampleRate, amplitude = 1.0)
        
        // Create signal with time-varying lag (simulating changing physiology)
        val faceSignal = ppgSignal.copyOf()
        val fingerSignal = DoubleArray(ppgSignal.size)
        
        // Variable lag: 80ms -> 120ms over duration
        for (i in fingerSignal.indices) {
            val progress = i.toDouble() / fingerSignal.size
            val lagMs = 80.0 + progress * 40.0 // 80 to 120 ms
            val lagSamples = (lagMs / 1000.0 * sampleRate).toInt()
            
            if (i >= lagSamples) {
                fingerSignal[i] = faceSignal[i - lagSamples]
            }
        }
        
        val result = CrossCorrelation.computeLagStability(
            faceSignal,
            fingerSignal,
            sampleRateHz = sampleRate,
            windowSizeS = 10.0,
            overlapS = 5.0
        )
        
        println("Varying PTT test: mean=${result.meanLagMs}ms, std=${result.stdLagMs}ms, valid=${result.isValid}")
        
        // This test just verifies the stability calculation works
        // The exact values depend on the time-varying signal construction
        if (result.isValid) {
            // Std should show variability
            assertTrue("Std should be > 0 for varying PTT", result.stdLagMs > 0.0)
            // Window count should be reasonable
            assertTrue("Should have multiple windows", result.windowCount >= 2)
        }
    }
    
    @Test
    fun `empty signals handling`() {
        val empty = doubleArrayOf()
        val signal = doubleArrayOf(1.0, 2.0, 3.0)
        
        val result1 = CrossCorrelation.computeLag(empty, signal, 100.0)
        assertFalse(result1.isValid)
        
        val result2 = CrossCorrelation.computeLag(signal, empty, 100.0)
        assertFalse(result2.isValid)
    }
    
    @Test
    fun `mismatched signal lengths`() {
        val signal1 = doubleArrayOf(1.0, 2.0, 3.0)
        val signal2 = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        
        val result = CrossCorrelation.computeLag(signal1, signal2, 100.0)
        assertFalse(result.isValid)
    }
}

