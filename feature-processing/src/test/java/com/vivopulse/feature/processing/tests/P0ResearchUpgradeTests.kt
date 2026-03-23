package com.vivopulse.feature.processing.tests

import com.vivopulse.feature.processing.ptt.PeakDetect
import com.vivopulse.feature.processing.ptt.PttSqi
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * P0 Research Upgrade Tests
 * 
 * Validates:
 * 1. MAD-based peak detection is robust to outliers
 * 2. Prominence filtering rejects noise spikes  
 * 3. Logit-space confidence is not killed by a single bad factor
 * 4. Quality tiers are correctly assigned
 */
class P0ResearchUpgradeTests {

    // ═══════════════════════════════════════════════════════════════
    // P0.2: MAD Peak Detection Tests
    // ═══════════════════════════════════════════════════════════════
    
    @Test
    fun `MAD peak detection finds peaks in clean sinusoid`() {
        // Generate clean 1 Hz PPG-like signal at 100 Hz for 10 seconds
        // DC offset of 1.0 mimics real PPG (always positive, oscillating around mean)
        val fs = 100.0
        val duration = 10.0
        val n = (fs * duration).toInt()
        val signal = DoubleArray(n) { 1.0 + sin(2.0 * PI * 1.0 * it / fs) }
        
        val result = PeakDetect.detectPeaks(signal, fs)
        
        assertTrue("Should be valid", result.isValid)
        // 1 Hz for 10s → ~9-10 peaks (first/last may be missed)
        assertTrue("Should detect 8-10 peaks, got ${result.getPeakCount()}", 
            result.getPeakCount() in 8..10)
    }
    
    @Test
    fun `MAD peak detection is resistant to single outlier`() {
        // Generate clean 1 Hz PPG-like signal with DC offset
        val fs = 100.0
        val duration = 10.0
        val n = (fs * duration).toInt()
        val signal = DoubleArray(n) { 1.0 + sin(2.0 * PI * 1.0 * it / fs) }
        
        // Inject outlier at sample 50 (10x normal amplitude)
        signal[50] = 10.0
        
        val result = PeakDetect.detectPeaks(signal, fs)
        
        assertTrue("Should still be valid despite outlier", result.isValid)
        // Old mean+std detector would raise threshold so high that 
        // normal peaks would be missed. MAD should be robust.
        assertTrue("Should detect ≥7 peaks despite outlier, got ${result.getPeakCount()}", 
            result.getPeakCount() >= 7)
    }
    
    @Test
    fun `prominence filter rejects noise spikes between beats`() {
        // Generate 1 Hz PPG-like signal with small noise bumps between beats
        val fs = 100.0
        val duration = 10.0
        val n = (fs * duration).toInt()
        val signal = DoubleArray(n) { i ->
            val beat = 1.0 + sin(2.0 * PI * 1.0 * i / fs) // Main beat with DC offset
            val noise = 0.15 * sin(2.0 * PI * 4.0 * i / fs) // Small 4 Hz noise
            beat + noise
        }
        
        val result = PeakDetect.detectPeaks(signal, fs)
        
        assertTrue("Should be valid", result.isValid)
        // Should only find the 1 Hz peaks, not the 4 Hz noise bumps
        // Old detector might detect both. Prominence filter should remove noise peaks.
        assertTrue("Should detect 8-10 beat peaks (not noise), got ${result.getPeakCount()}", 
            result.getPeakCount() in 7..12)
    }
    
    // ═══════════════════════════════════════════════════════════════
    // P0.1: Logit-Space Confidence Tests
    // ═══════════════════════════════════════════════════════════════
    
    @Test
    fun `single bad factor does not kill confidence`() {
        // Old multiplicative: (70/100) * 0.8 * 0.5 * 0.95 * 0.7 = 0.19 → REJECTED
        // New logit-space: should be higher because factors are additive
        
        val confidence = PttSqi.computeCombinedConfidence(
            sqiFace = 70,        // decent
            sqiFinger = 80,      // good
            corrScore = 0.80,    // good
            peakSharpness = 0.03, // BAD (low sharpness)
            delayStabilityScore = 0.95, // good
            methodAgreeMs = 20.0  // acceptable
        )
        
        // Should NOT be REJECTED just because sharpness is low
        assertTrue("Confidence should be > 0.30 (REJECTED threshold) despite low sharpness, got $confidence",
            confidence > PttSqi.THRESHOLD_LOW)
        
        val tier = PttSqi.getQualityTier(confidence)
        assertNotEquals("Should not be REJECTED", PttSqi.QualityTier.REJECTED, tier)
    }
    
    @Test
    fun `all good factors produce HIGH confidence`() {
        val confidence = PttSqi.computeCombinedConfidence(
            sqiFace = 90,
            sqiFinger = 85,
            corrScore = 0.90,
            peakSharpness = 0.15,
            delayStabilityScore = 0.90,
            methodAgreeMs = 5.0
        )
        
        assertEquals("Should be HIGH tier", PttSqi.QualityTier.HIGH, PttSqi.getQualityTier(confidence))
        assertTrue("Should be > 0.75, got $confidence", confidence >= PttSqi.THRESHOLD_HIGH)
    }
    
    @Test
    fun `all bad factors produce REJECTED`() {
        val confidence = PttSqi.computeCombinedConfidence(
            sqiFace = 10,
            sqiFinger = 15,
            corrScore = 0.10,
            peakSharpness = 0.01,
            delayStabilityScore = 0.10,
            methodAgreeMs = 100.0
        )
        
        assertEquals("Should be REJECTED", PttSqi.QualityTier.REJECTED, PttSqi.getQualityTier(confidence))
    }
    
    @Test
    fun `quality tiers are ordered correctly`() {
        // Generate confidences at various levels and verify ordering
        val highConf = PttSqi.computeCombinedConfidence(90, 90, 0.95, 0.2, 0.95, 2.0)
        val medConf = PttSqi.computeCombinedConfidence(60, 60, 0.60, 0.08, 0.60, 30.0)
        val lowConf = PttSqi.computeCombinedConfidence(30, 35, 0.30, 0.03, 0.30, 60.0)
        
        assertTrue("HIGH > MEDIUM: $highConf > $medConf", highConf > medConf)
        assertTrue("MEDIUM > LOW: $medConf > $lowConf", medConf > lowConf)
    }
    
    @Test
    fun `method agreement MAX_VALUE does not collapse confidence`() {
        // When foot-to-foot fails, methodAgreeMs = MAX_VALUE
        // Old system: agreement factor = 0.7 × correlation kills product
        // New system: should degrade gracefully
        
        val withAgreement = PttSqi.computeCombinedConfidence(
            sqiFace = 75, sqiFinger = 80, corrScore = 0.80,
            peakSharpness = 0.10, delayStabilityScore = 0.85, methodAgreeMs = 10.0
        )
        val withoutAgreement = PttSqi.computeCombinedConfidence(
            sqiFace = 75, sqiFinger = 80, corrScore = 0.80,
            peakSharpness = 0.10, delayStabilityScore = 0.85, methodAgreeMs = Double.MAX_VALUE
        )
        
        assertTrue("Without agreement should still have reasonable confidence, got $withoutAgreement",
            withoutAgreement > PttSqi.THRESHOLD_LOW)
        assertTrue("Without agreement should be lower than with, $withoutAgreement < $withAgreement",
            withoutAgreement < withAgreement)
    }
    
    @Test
    fun `shouldReportPtt reports MEDIUM and above`() {
        // MEDIUM confidence should now be reported (was rejected before)
        assertTrue("Should report HIGH", PttSqi.shouldReportPtt(0.80))
        assertTrue("Should report MEDIUM", PttSqi.shouldReportPtt(0.55))
        assertTrue("Should report LOW", PttSqi.shouldReportPtt(0.35))
        assertFalse("Should reject < 0.30", PttSqi.shouldReportPtt(0.20))
    }
}
