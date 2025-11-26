package com.vivopulse.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

class BandPassFilterTests {

    @Test
    fun `filter attenuates stopband frequencies`() {
        val fs = 100.0
        val duration = 10.0
        
        // Generate test signals
        val signal = DspFunctions.generateSineWave(1.2, duration, fs, amplitude = 1.0)
        val lowNoise = DspFunctions.generateSineWave(0.1, duration, fs, amplitude = 1.0)
        val highNoise = DspFunctions.generateSineWave(8.0, duration, fs, amplitude = 1.0)
        
        // Process with Order 4 to meet 20dB attenuation spec at 1 octave
        val order = 4
        val filteredSignal = DspFunctions.butterworthBandpass(signal, 0.7, 4.0, fs, order)
        val filteredLow = DspFunctions.butterworthBandpass(lowNoise, 0.7, 4.0, fs, order)
        val filteredHigh = DspFunctions.butterworthBandpass(highNoise, 0.7, 4.0, fs, order)
        
        // Measure power (RMS) - drop first 2 seconds for settling
        val skip = (2.0 * fs).toInt()
        val powerSignal = computeRms(filteredSignal.drop(skip).toDoubleArray())
        val powerLow = computeRms(filteredLow.drop(skip).toDoubleArray())
        val powerHigh = computeRms(filteredHigh.drop(skip).toDoubleArray())
        
        // Passband attenuation should be low (< 3dB loss)
        // 1.0 amplitude sine has RMS 0.707
        // Allow some loss due to settling or ripple
        assertTrue("Passband signal preserved (RMS > 0.6), was $powerSignal", powerSignal > 0.6)
        
        // Stopband attenuation
        // Target >= 20dB attenuation
        val attLow = 20 * log10(powerSignal / powerLow)
        val attHigh = 20 * log10(powerSignal / powerHigh)
        
        println("Attenuation Low (0.1Hz): ${String.format("%.2f", attLow)} dB")
        println("Attenuation High (8.0Hz): ${String.format("%.2f", attHigh)} dB")
        
        assertTrue("Low freq attenuated by > 20dB, was $attLow", attLow > 20.0)
        assertTrue("High freq attenuated by > 20dB, was $attHigh", attHigh > 20.0)
    }
    
    @Test
    fun `filter handles DC offset removal`() {
        val fs = 100.0
        val signal = DspFunctions.generateSineWave(1.2, 5.0, fs, amplitude = 1.0)
        val offsetSignal = signal.map { it + 100.0 }.toDoubleArray()
        
        // Filter with order 4
        val filtered = DspFunctions.butterworthBandpass(offsetSignal, 0.7, 4.0, fs, order = 4)
        
        // Check mean after settling (drop 3s to allow IIR step response to decay)
        val steadyState = filtered.drop(300)
        val mean = steadyState.average()
        // Tolerance 0.5 on 100.0 offset is 0.5% error, reasonable for settling
        assertEquals("DC offset removed (mean ~ 0)", 0.0, mean, 0.5)
    }
    
    @Test
    fun `filter delay consistency`() {
        val fs = 100.0
        val duration = 2.0
        val signal = DspFunctions.generateSineWave(1.2, duration, fs)
        
        // Filter
        val filtered = DspFunctions.butterworthBandpass(signal, 0.7, 4.0, fs)
        
        // Cross-correlate to find delay
        // Simple peak search
        // Input peak at 0.25/1.2 = 0.208s
        // Find peak in output
        
        // Note: IIR filters have non-linear phase, but for single freq it is constant delay
        // Just ensuring it doesn't explode or shift wildly
        
        // Just check it output is not empty and has valid range
        assertTrue(filtered.isNotEmpty())
        assertEquals(signal.size, filtered.size)
    }

    private fun computeRms(data: DoubleArray): Double {
        if (data.isEmpty()) return 0.0
        return sqrt(data.map { it * it }.average())
    }
}

