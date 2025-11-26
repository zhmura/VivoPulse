package com.vivopulse.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TimeFrequencyToolingTest {

    @Test
    fun `computeSTFT identifies correct frequency`() {
        val fs = 100.0
        val duration = 2.0
        // Generate 2 Hz sine wave
        val signal = DspFunctions.generateSineWave(2.0, duration, fs)
        
        val result = TimeFrequencyTooling.computeSTFT(
            signal, fs, windowSizeSamples = 100, overlapSamples = 50
        )
        
        // 100 sample window @ 100Hz = 1s. Resolution 1Hz.
        // Bins: 0, 1, 2, 3...
        // Should peak at bin 2.
        
        // Check middle frame
        val midFrame = result.magnitudes[result.magnitudes.size / 2]
        
        var maxMag = -1.0
        var maxIdx = -1
        for (i in midFrame.indices) {
            if (midFrame[i] > maxMag) {
                maxMag = midFrame[i]
                maxIdx = i
            }
        }
        
        val peakFreq = result.freqAxis[maxIdx]
        assertEquals("Peak frequency should be ~2.0 Hz", 2.0, peakFreq, 0.5)
    }
    
    @Test
    fun `computeSTFT output dimensions are correct`() {
        val fs = 100.0
        val signal = DoubleArray(200) // 2s
        val window = 50
        val overlap = 25
        val hop = 25
        
        // Frames: (200 - 50) / 25 + 1 = 150/25 + 1 = 6 + 1 = 7 frames
        val result = TimeFrequencyTooling.computeSTFT(signal, fs, window, overlap)
        
        assertEquals("Time axis size", 7, result.timeAxis.size)
        assertEquals("Magnitudes rows", 7, result.magnitudes.size)
        
        // Freq bins: 50/2 + 1 = 26
        assertEquals("Freq axis size", 26, result.freqAxis.size)
        assertEquals("Magnitudes cols", 26, result.magnitudes[0].size)
    }
}

