package com.vivopulse.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.math.PI

class HarmonicFeatureExtractorTest {

    @Test
    fun `detects fundamental frequency of pure sine wave`() {
        val fs = 100.0
        val duration = 5.0 // 5 seconds
        val freq = 1.2
        
        val signal = DspFunctions.generateSineWave(freq, duration, fs)
        
        val features = HarmonicFeatureExtractor.extractHarmonicFeatures(signal, fs)
        
        // Frequency resolution = 100 / 512 ~= 0.2 Hz (approx, depending on next power of 2)
        // 5s = 500 samples -> padded to 512. Res = 100/512 = 0.195 Hz.
        // 1.2 Hz should be at bin 6 (1.17) or 7 (1.36).
        // Wait, precision is limited by window length.
        
        assertEquals("Fundamental Hz", freq, features.fundamentalHz, 0.2)
        
        // Pure sine should have low harmonic ratios
        assertTrue("H2 ratio low", features.h2ToH1Ratio < 0.1)
        assertTrue("H3 ratio low", features.h3ToH1Ratio < 0.1)
        
        // Spectral entropy should be low (peaky spectrum)
        assertTrue("Entropy low", features.spectralEntropy < 0.5)
    }
    
    @Test
    fun `detects harmonics and ratios`() {
        val fs = 100.0
        val duration = 5.0
        val f1 = 1.0
        val a1 = 1.0
        val a2 = 0.5 // H2
        val a3 = 0.25 // H3
        
        val numSamples = (duration * fs).toInt()
        val signal = DoubleArray(numSamples) { i ->
            val t = i / fs
            a1 * sin(2 * PI * f1 * t) + 
            a2 * sin(2 * PI * 2 * f1 * t) + 
            a3 * sin(2 * PI * 3 * f1 * t)
        }
        
        val features = HarmonicFeatureExtractor.extractHarmonicFeatures(signal, fs)
        
        assertEquals("Fundamental Hz", f1, features.fundamentalHz, 0.2)
        
        // Ratios might be affected by windowing/leakage, but should be close
        assertEquals("H2 Ratio", a2/a1, features.h2ToH1Ratio, 0.1)
        assertEquals("H3 Ratio", a3/a1, features.h3ToH1Ratio, 0.1)
    }
    
    @Test
    fun `spectral entropy increases with noise`() {
        val fs = 100.0
        val duration = 5.0
        
        // Clean signal
        val clean = DspFunctions.generateSineWave(1.2, duration, fs)
        val cleanFeatures = HarmonicFeatureExtractor.extractHarmonicFeatures(clean, fs)
        
        // Noisy signal (random noise)
        val noisy = DoubleArray(clean.size) { 
            clean[it] + (Math.random() - 0.5) * 2.0 
        }
        val noisyFeatures = HarmonicFeatureExtractor.extractHarmonicFeatures(noisy, fs)
        
        assertTrue("Noisy entropy higher", noisyFeatures.spectralEntropy > cleanFeatures.spectralEntropy)
        assertTrue("Noisy SNR lower", noisyFeatures.snrDb < cleanFeatures.snrDb)
    }
}

