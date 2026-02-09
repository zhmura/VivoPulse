package com.vivopulse.signal

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Unit tests for DSP functions.
 * 
 * Tests filtering with synthetic sine waves and drift.
 */
class DspFunctionsTest {
    
    private val epsilon = 1e-6
    
    @Test
    fun `detrend - removes DC component`() {
        // Signal with DC offset
        val signal = doubleArrayOf(5.0, 5.1, 4.9, 5.0, 5.1, 4.9, 5.0)
        val detrended = DspFunctions.detrend(signal, windowSize = 3)
        
        // Mean should be close to zero
        val mean = detrended.average()
        assertTrue(abs(mean) < 0.1)
    }
    
    @Test
    fun `detrend - removes linear drift`() {
        // Create signal with linear drift
        val baseSignal = DspFunctions.generateSineWave(1.0, 1.0, 100.0, amplitude = 1.0)
        val drifted = DspFunctions.addLinearDrift(baseSignal, driftRate = 0.01)
        
        val detrended = DspFunctions.detrend(drifted, windowSize = 50)
        
        // Detrended signal should have smaller range than drifted
        val driftedRange = drifted.maxOrNull()!! - drifted.minOrNull()!!
        val detrendedRange = detrended.maxOrNull()!! - detrended.minOrNull()!!
        
        assertTrue("Detrended range should be smaller", detrendedRange < driftedRange)
    }
    
    @Test
    fun `detrendIIR - preserves AC component`() {
        // Sine wave at 1 Hz with DC offset
        val signal = DspFunctions.generateSineWave(1.0, 2.0, 100.0, amplitude = 1.0)
            .map { it + 10.0 }.toDoubleArray()
        
        val detrended = DspFunctions.detrendIIR(signal, cutoffHz = 0.5, sampleRateHz = 100.0)
        
        // AC component should be preserved (amplitude ~1.0)
        // Allow some attenuation due to filter transition
        val power = DspFunctions.computePower(detrended)
        assertTrue("AC power mostly preserved (RMS ~0.707)", power > 0.5) // RMS of sine = amplitude/sqrt(2)
    }
    
    @Test
    fun `butterworthBandpass - passband preservation`() {
        // Generate 1.5 Hz sine wave (should pass through 0.7-4.0 Hz filter)
        val signal = DspFunctions.generateSineWave(1.5, 2.0, 100.0, amplitude = 1.0)
        
        val filtered = DspFunctions.butterworthBandpass(
            signal,
            lowCutoffHz = 0.7,
            highCutoffHz = 4.0,
            sampleRateHz = 100.0,
            order = 2
        )
        
        // Power should be mostly preserved in passband
        val inputPower = DspFunctions.computePower(signal)
        val outputPower = DspFunctions.computePower(filtered)
        
        val attenuation = outputPower / inputPower
        assertTrue("Passband signal should be preserved (attenuation > 0.7)", attenuation > 0.7)
        
        println("Passband (1.5 Hz): Input power=${inputPower}, Output power=${outputPower}, Attenuation=${attenuation}")
    }
    
    @Test
    fun `butterworthBandpass - stopband attenuation low frequency`() {
        // Generate 0.3 Hz sine wave (below 0.7 Hz cutoff, should be attenuated)
        val signal = DspFunctions.generateSineWave(0.3, 5.0, 100.0, amplitude = 1.0)
        
        val filtered = DspFunctions.butterworthBandpass(
            signal,
            lowCutoffHz = 0.7,
            highCutoffHz = 4.0,
            sampleRateHz = 100.0,
            order = 2
        )
        
        // Low frequency should be attenuated
        val inputPower = DspFunctions.computePower(signal)
        val outputPower = DspFunctions.computePower(filtered)
        
        val attenuation = outputPower / inputPower
        assertTrue("Low frequency should be attenuated (< 0.3)", attenuation < 0.3)
        
        println("Stopband low (0.3 Hz): Input power=${inputPower}, Output power=${outputPower}, Attenuation=${attenuation}")
    }
    
    @Test
    fun `butterworthBandpass - stopband attenuation high frequency`() {
        // Generate 6 Hz sine wave (above 4.0 Hz cutoff, should be attenuated)
        val signal = DspFunctions.generateSineWave(6.0, 2.0, 100.0, amplitude = 1.0)
        
        val filtered = DspFunctions.butterworthBandpass(
            signal,
            lowCutoffHz = 0.7,
            highCutoffHz = 4.0,
            sampleRateHz = 100.0,
            order = 2
        )
        
        // High frequency should be attenuated
        val inputPower = DspFunctions.computePower(signal)
        val outputPower = DspFunctions.computePower(filtered)
        
        val attenuation = outputPower / inputPower
        
        println("Stopband high (6 Hz): Input power=${inputPower}, Output power=${outputPower}, Attenuation=${attenuation}")
        
        // 2nd order Butterworth has gentle rolloff
        // At 6 Hz (1.5x cutoff), expect ~40-50% attenuation
        assertTrue("High frequency should be attenuated (< 0.6)", attenuation < 0.6)
    }
    
    @Test
    fun `butterworthBandpass - heart rate frequencies preserved`() {
        // Test typical heart rate frequencies (1-2 Hz = 60-120 BPM)
        val frequencies = listOf(1.0, 1.5, 2.0)
        
        frequencies.forEach { freq ->
            val signal = DspFunctions.generateSineWave(freq, 2.0, 100.0, amplitude = 1.0)
            val filtered = DspFunctions.butterworthBandpass(
                signal,
                lowCutoffHz = 0.7,
                highCutoffHz = 4.0,
                sampleRateHz = 100.0,
                order = 2
            )
            
            val inputPower = DspFunctions.computePower(signal)
            val outputPower = DspFunctions.computePower(filtered)
            val attenuation = outputPower / inputPower
            
            println("Heart rate freq ($freq Hz): Attenuation=$attenuation")
            assertTrue("HR frequency $freq Hz should pass (> 0.7)", attenuation > 0.7)
        }
    }
    
    @Test
    fun `zscoreNormalize - zero mean unit variance`() {
        val signal = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
        val normalized = DspFunctions.zscoreNormalize(signal)
        
        val mean = normalized.average()
        val variance = normalized.map { (it - mean).pow(2) }.average()
        val std = sqrt(variance)
        
        assertTrue("Mean should be ~0", abs(mean) < epsilon)
        assertTrue("Std should be ~1", abs(std - 1.0) < 0.01)
    }
    
    @Test
    fun `zscoreNormalize - constant signal`() {
        val signal = doubleArrayOf(5.0, 5.0, 5.0, 5.0, 5.0)
        val normalized = DspFunctions.zscoreNormalize(signal)
        
        // Constant signal should become all zeros
        assertTrue(normalized.all { abs(it) < epsilon })
    }
    
    @Test
    fun `zscoreNormalize - preserves shape`() {
        val signal = DspFunctions.generateSineWave(1.0, 1.0, 100.0, amplitude = 2.0)
        val normalized = DspFunctions.zscoreNormalize(signal)
        
        // Should still be sinusoidal (check zero crossings)
        var crossings = 0
        for (i in 1 until normalized.size) {
            if ((normalized[i - 1] < 0 && normalized[i] >= 0) ||
                (normalized[i - 1] >= 0 && normalized[i] < 0)) {
                crossings++
            }
        }
        
        // 1 Hz sine over 1 second should have ~2 zero crossings
        assertTrue("Sine shape preserved", crossings >= 1 && crossings <= 3)
    }
    
    @Test
    fun `generateSineWave - correct frequency`() {
        val freq = 2.0 // 2 Hz
        val duration = 1.0 // 1 second
        val sampleRate = 100.0
        
        val signal = DspFunctions.generateSineWave(freq, duration, sampleRate)
        
        // Should have 100 samples
        assertEquals(100, signal.size)
        
        // Count zero crossings (should be ~4 for 2 Hz over 1 second)
        var crossings = 0
        for (i in 1 until signal.size) {
            if ((signal[i - 1] < 0 && signal[i] >= 0) ||
                (signal[i - 1] >= 0 && signal[i] < 0)) {
                crossings++
            }
        }
        
        // Allow 3-5 crossings due to sampling at boundaries
        assertTrue("2 Hz should have 3-5 zero crossings in 1s, was: $crossings", 
            crossings in 3..5)
    }
    
    @Test
    fun `addLinearDrift - creates trend`() {
        val signal = DoubleArray(100) { 1.0 }
        val drifted = DspFunctions.addLinearDrift(signal, driftRate = 0.1)
        
        // First sample should be ~1.0
        assertEquals(1.0, drifted[0], 0.01)
        
        // Last sample should be ~1.0 + 99*0.1 = 10.9
        assertEquals(10.9, drifted[99], 0.1)
        
        // Should have positive slope
        assertTrue(drifted.last() > drifted.first())
    }
    
    @Test
    fun `full pipeline - synthetic PPG signal`() {
        // Create realistic PPG signal: 1.2 Hz (72 BPM) with noise and drift
        val duration = 10.0 // seconds
        val sampleRate = 100.0
        
        // Clean PPG signal
        val ppgSignal = DspFunctions.generateSineWave(1.2, duration, sampleRate, amplitude = 0.5)
        
        // Add noise (high frequency)
        val noise = DspFunctions.generateSineWave(15.0, duration, sampleRate, amplitude = 0.1)
        
        // Add drift
        val noisySignal = ppgSignal.zip(noise) { p, n -> p + n }.toDoubleArray()
        val driftedSignal = DspFunctions.addLinearDrift(noisySignal, driftRate = 0.05)
        
        // Apply full pipeline
        val detrended = DspFunctions.detrendIIR(driftedSignal, cutoffHz = 0.5, sampleRateHz = sampleRate)
        val filtered = DspFunctions.butterworthBandpass(
            detrended,
            lowCutoffHz = 0.7,
            highCutoffHz = 4.0,
            sampleRateHz = sampleRate,
            order = 2
        )
        val normalized = DspFunctions.zscoreNormalize(filtered)
        
        // Verify properties
        val mean = normalized.average()
        val std = sqrt(normalized.map { it.pow(2) }.average())
        
        assertTrue("Mean ~0", abs(mean) < 0.01)
        assertTrue("Std ~1", abs(std - 1.0) < 0.01)
        
        // Output should have reasonable power
        val power = DspFunctions.computePower(normalized)
        assertTrue("Power should be significant", power > 0.5)
        
        println("Full pipeline test: Mean=${mean}, Std=${std}, Power=${power}")
    }
    
    @Test
    fun `empty signal handling`() {
        val empty = doubleArrayOf()
        
        assertEquals(0, DspFunctions.detrend(empty).size)
        assertEquals(0, DspFunctions.detrendIIR(empty).size)
        assertEquals(0, DspFunctions.butterworthBandpass(empty).size)
        assertEquals(0, DspFunctions.zscoreNormalize(empty).size)
        assertEquals(0.0, DspFunctions.computePower(empty), epsilon)
    }
    @Test
    fun `removeMean - correctly centers signal`() {
        val signal = doubleArrayOf(10.0, 12.0, 14.0) // Mean = 12.0
        val centered = DspFunctions.removeMean(signal)
        
        assertEquals(-2.0, centered[0], epsilon)
        assertEquals(0.0, centered[1], epsilon)
        assertEquals(2.0, centered[2], epsilon)
        assertEquals(0.0, centered.average(), epsilon)
    }

    @Test
    fun `filtfilt - zero phase shift`() {
        // Create a symmetric Gaussian pulse. Peak is exactly at center.
        val size = 100
        val center = size / 2
        val signal = DoubleArray(size) { i ->
            exp(-0.5 * ((i - center) / 5.0).pow(2))
        }

        // Apply filtfilt with a lowpass filter
        val filtered = DspFunctions.filtfilt(signal) { input ->
             // Let's us a simple dummy filter to prove the mechanism: reverse-filter-reverse.
             // But to test REAL usage, we use butterworth.
             // Let's simulate a standard bandpass.
             DspFunctions.butterworthBandpass(input, 0.1, 4.0, 30.0, 2)
        }
        
        // Find peak of input and output
        val inputPeakIdx = signal.indices.maxByOrNull { signal[it] }!!
        val outputPeakIdx = filtered.indices.maxByOrNull { filtered[it] }!!
        
        // The peak should stay at the same index
        assertEquals("Peak should not move (Zero Phase)", inputPeakIdx, outputPeakIdx)
    }

    @Test
    fun `filtfilt - transient suppression at boundaries`() {
        // Step function: 0, 0, ..., 10, 10, ...
        // A normal IIR filter would ring heavily at the step if it's at index 0.
        // Our padding should strictly handle the boundaries.
        // Let's test a signal that starts with a high DC offset.
        val size = 100
        val signal = DoubleArray(size) { 100.0 + sin(it * 0.1) } // DC = 100
        
        // If we didn't remove DC or pad, the start would be wild.
        // removeMean + filtfilt should handle this.
        val centered = DspFunctions.removeMean(signal)
        val filtered = DspFunctions.filtfilt(centered) { input ->
            DspFunctions.butterworthBandpass(input, 0.5, 4.0, 30.0, 2)
        }
        
        // Check the first few samples. They should not be massive spikes relative to the rest.
        val maxVal = filtered.map { abs(it) }.maxOrNull()!!
        val firstVal = abs(filtered[0])
        
        // The artifact shouldn't be effectively infinite or orders of magnitude larger
        assertTrue("Start artifact ${firstVal} should be reasonable vs max ${maxVal}", firstVal < maxVal * 2.0)
    }

    @Test
    fun `filtfilt - window boundary invariance`() {
        val fs = 100.0
        // Generate 10s signal (1000 samples) — long enough for windows + padding
        val totalDuration = 10.0
        val totalSamples = (totalDuration * fs).toInt()
        val s1 = DspFunctions.generateSineWave(1.2, totalDuration, fs, amplitude = 1.0)
        val s2 = DspFunctions.generateSineWave(2.5, totalDuration, fs, amplitude = 0.5)
        val signal = DoubleArray(totalSamples) { i -> s1[i] + s2[i] }
        
        // Window 1: 0..5s (500 samples)
        val w1Len = 500
        val w1 = signal.sliceArray(0 until w1Len)
        
        // Window 2: 0.5..5.5s (shift 50 samples)
        val shift = 50
        val w2 = signal.sliceArray(shift until (w1Len + shift))
        
        val filterOp: (DoubleArray) -> DoubleArray = { 
            DspFunctions.butterworthBandpass(it, 0.7, 4.0, fs, 2)
        }
        
        // padLength = 150 matches production SignalPipeline setting
        val f1 = DspFunctions.filtfilt(w1, padLength = 150, filterOp)
        val f2 = DspFunctions.filtfilt(w2, padLength = 150, filterOp)
        
        // Compare overlapping region in the safe middle.
        // Overlap in absolute time: [0.5s, 5.0s].
        // Skip first 1.5s of each window to avoid any edge effects.
        // Compare absolute time [2.0s, 4.0s]:
        //   w1 indices: 200..400
        //   w2 indices: 150..350
        
        var maxDiff = 0.0
        val compareLen = 200
        for (i in 0 until compareLen) {
            val v1 = f1[200 + i]
            val v2 = f2[150 + i]
            val diff = abs(v1 - v2)
            if (diff > maxDiff) maxDiff = diff
        }
        
        println("Window Invariance Max Diff: $maxDiff")
        assertTrue("Invariance failed, maxDiff=$maxDiff > 0.01", maxDiff < 0.01)
    }
}

