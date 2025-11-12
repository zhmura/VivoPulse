package com.vivopulse.feature.processing.ptt

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PttSqiTests {
    
    @Test
    fun `degraded SNR decreases SQI`() {
        val fsHz = 100.0
        val n = 1000
        
        // Clean signal
        val clean = DoubleArray(n) { i -> sin(2 * PI * 1.2 * i / fsHz) }
        
        // Noisy raw signal (SNR ~0 dB)
        val noise = DoubleArray(n) { (Math.random() - 0.5) * 1.0 }
        val noisyRaw = clean.zip(noise) { s, nz -> s + nz }.toDoubleArray()
        
        // Filtered version (less noise)
        val noisyFiltered = clean.zip(noise) { s, nz -> s + nz * 0.3 }.toDoubleArray()
        
        // Detect peaks
        val peaksClean = PeakDetect.detectPeaks(clean, fsHz)
        val peaksNoisy = PeakDetect.detectPeaks(noisyFiltered, fsHz)
        
        // Compute SQI (using filtered vs raw for SNR calculation)
        val sqiClean = PttSqi.computeChannelSqi(clean, clean, fsHz, peaksClean)
        val sqiNoisy = PttSqi.computeChannelSqi(noisyFiltered, noisyRaw, fsHz, peaksNoisy)
        
        assertTrue("Clean signal should have higher SQI, clean=${sqiClean.sqi}, noisy=${sqiNoisy.sqi}", 
            sqiClean.sqi > sqiNoisy.sqi)
    }
    
    @Test
    fun `SQI below 60 withholds PTT`() {
        val lowSqiFace = 50
        val lowSqiFinger = 45
        val highCorr = 0.85
        val sharpness = 0.15
        
        val confidence = PttSqi.computeCombinedConfidence(
            sqiFace = lowSqiFace,
            sqiFinger = lowSqiFinger,
            corrScore = highCorr,
            peakSharpness = sharpness
        )
        
        val shouldReport = PttSqi.shouldReportPtt(confidence)
        
        assertFalse("PTT should be withheld when SQI <60", shouldReport)
        assertTrue("Confidence should be low", confidence < 0.60)
    }
    
    @Test
    fun `high quality signals produce high confidence`() {
        val highSqiFace = 85
        val highSqiFinger = 82
        val highCorr = 0.90
        val highSharpness = 0.25
        
        val confidence = PttSqi.computeCombinedConfidence(
            sqiFace = highSqiFace,
            sqiFinger = highSqiFinger,
            corrScore = highCorr,
            peakSharpness = highSharpness
        )
        
        val shouldReport = PttSqi.shouldReportPtt(confidence)
        
        assertTrue("PTT should be reported for high quality", shouldReport)
        assertTrue("Confidence should be high (≥0.70)", confidence >= 0.70)
    }
    
    @Test
    fun `low correlation reduces confidence below threshold`() {
        val goodSqiFace = 75
        val goodSqiFinger = 73
        val lowCorr = 0.45
        val sharpness = 0.10
        
        val confidence = PttSqi.computeCombinedConfidence(
            sqiFace = goodSqiFace,
            sqiFinger = goodSqiFinger,
            corrScore = lowCorr,
            peakSharpness = sharpness
        )
        
        assertFalse("Low correlation should prevent PTT reporting", 
            PttSqi.shouldReportPtt(confidence))
    }
}

