package com.vivopulse.feature.processing.ptt

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * PttSqi unit tests, updated for P0.1 logit-space confidence model.
 * 
 * Key behavioral changes from P0.1:
 * - Confidence uses logit-space weighted sum (not multiplicative)
 * - shouldReportPtt() returns true for MEDIUM+ (conf ≥ 0.30, was ≥ 0.60)
 * - Motion weight is now active (0.15, was 0.0)
 * - Soft-min replaces hard min(SQI_face, SQI_finger)
 */
class PttSqiTests {
    
    @Test
    fun `degraded SNR decreases SQI`() {
        val fsHz = 100.0
        val n = 1000
        
        // Clean signal
        val clean = DoubleArray(n) { i -> 1.0 + sin(2 * PI * 1.2 * i / fsHz) }
        
        // Noisy raw signal (SNR ~0 dB)
        val noise = DoubleArray(n) { (Math.random() - 0.5) * 2.0 }
        val noisyRaw = clean.zip(noise) { s, nz -> s + nz }.toDoubleArray()
        
        // Filtered version (less noise)
        val noisyFiltered = clean.zip(noise) { s, nz -> s + nz * 0.3 }.toDoubleArray()
        
        // Detect peaks
        val peaksClean = PeakDetect.detectPeaks(clean, fsHz)
        val peaksNoisy = PeakDetect.detectPeaks(noisyFiltered, fsHz)
        
        // Compute SQI — note: same motionPenalty for both to isolate SNR effect
        val sqiClean = PttSqi.computeChannelSqi(clean, clean, fsHz, peaksClean, motionPenalty = 100.0)
        val sqiNoisy = PttSqi.computeChannelSqi(noisyFiltered, noisyRaw, fsHz, peaksNoisy, motionPenalty = 100.0)
        
        // With 2x noise amplitude, the noisy signal should have measurably lower SNR score
        assertTrue("Clean SNR score should be higher than noisy: clean=${sqiClean.snrScore}, noisy=${sqiNoisy.snrScore}",
            sqiClean.snrScore > sqiNoisy.snrScore)
    }
    
    @Test
    fun `very low SQI reduces confidence significantly`() {
        // P0.1: logit-space allows partial compensation, so we need ALL factors
        // to be weak (not just SQI) to trigger LOW/REJECTED.
        val veryLowSqiFace = 5
        val veryLowSqiFinger = 10
        val lowCorr = 0.30
        val lowSharpness = 0.02
        
        val confidence = PttSqi.computeCombinedConfidence(
            sqiFace = veryLowSqiFace,
            sqiFinger = veryLowSqiFinger,
            corrScore = lowCorr,
            peakSharpness = lowSharpness
        )
        
        val tier = PttSqi.getQualityTier(confidence)
        
        // All-weak factors should produce LOW or REJECTED
        assertTrue("All-weak factors should produce LOW or REJECTED tier, got $tier (conf=$confidence)",
            tier == PttSqi.QualityTier.LOW || tier == PttSqi.QualityTier.REJECTED)
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
        assertTrue("Confidence should be high (≥0.60), got $confidence", confidence >= 0.60)
    }
    
    @Test
    fun `low correlation reduces confidence`() {
        val goodSqiFace = 75
        val goodSqiFinger = 73
        val lowCorr = 0.15  // Very low correlation
        val sharpness = 0.02
        
        val confidenceLowCorr = PttSqi.computeCombinedConfidence(
            sqiFace = goodSqiFace,
            sqiFinger = goodSqiFinger,
            corrScore = lowCorr,
            peakSharpness = sharpness
        )
        
        val confidenceHighCorr = PttSqi.computeCombinedConfidence(
            sqiFace = goodSqiFace,
            sqiFinger = goodSqiFinger,
            corrScore = 0.90,
            peakSharpness = 0.20
        )
        
        // Low correlation should produce lower confidence than high correlation
        assertTrue("Low correlation should reduce confidence: low=$confidenceLowCorr, high=$confidenceHighCorr",
            confidenceLowCorr < confidenceHighCorr)
    }
}
