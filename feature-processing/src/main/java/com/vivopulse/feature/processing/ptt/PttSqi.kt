package com.vivopulse.feature.processing.ptt

import kotlin.math.*

/**
 * PTT Signal Quality Index (PTT-SQI) for confidence assessment.
 * 
 * Combines:
 * - Per-channel SQI (SNR, peak regularity, motion penalty)
 * - Cross-correlation score
 * - Peak sharpness
 * 
 * Confidence threshold: ≥0.60 to report PTT
 */
object PttSqi {
    
    /**
     * Compute per-channel SQI.
     * 
     * Components:
     * - SNR (0-70 points): band power (0.7-4.0 Hz) / noise power
     * - Peak regularity (0-30 points): 1 - CV(RR)
     * - Motion penalty (0 points for finger, applied for face)
     * 
     * @param filteredSignal Filtered signal (0.7-4.0 Hz bandpass)
     * @param rawSignal Raw signal (before filtering)
     * @param fsHz Sample rate
     * @param peakResult Peak detection result
     * @param motionPenalty Motion penalty 0-100 (100 = no motion, default)
     * @return Channel SQI 0-100
     */
    fun computeChannelSqi(
        filteredSignal: DoubleArray,
        rawSignal: DoubleArray,
        fsHz: Double,
        peakResult: PeakDetectResult,
        motionPenalty: Double = 100.0
    ): ChannelSqiResult {
        // 1. SNR component (0-70 points)
        val snrDb = computeBandSnr(filteredSignal, rawSignal)
        val snrScore = computeSnrScore(snrDb) // 0-70
        
        // 2. Peak regularity component (0-30 points)
        val peakQuality = PeakDetect.assessPeakQuality(peakResult)
        val regularityScore = peakQuality * 0.3 // Scale to 0-30
        
        // 3. Motion component (0 points base, penalty reduces score)
        val motionScore = motionPenalty * 0.0 // Currently 0 weight, can adjust
        
        // Combined score
        val sqi = snrScore + regularityScore + motionScore
        
        return ChannelSqiResult(
            sqi = sqi.toInt().coerceIn(0, 100),
            snrDb = snrDb,
            snrScore = snrScore,
            regularityScore = regularityScore,
            motionPenalty = motionPenalty,
            peakCount = peakResult.getPeakCount()
        )
    }
    
    /**
     * Compute band-limited SNR.
     * 
     * SNR = 10*log10(signal_power / noise_power)
     * Signal = filtered (0.7-4.0 Hz passband)
     * Noise = raw - filtered
     * 
     * @param filtered Filtered signal
     * @param raw Raw signal
     * @return SNR in dB
     */
    private fun computeBandSnr(filtered: DoubleArray, raw: DoubleArray): Double {
        val n = minOf(filtered.size, raw.size)
        
        var signalPower = 0.0
        var noisePower = 0.0
        
        for (i in 0 until n) {
            val sig = filtered[i]
            val noise = raw[i] - filtered[i]
            signalPower += sig * sig
            noisePower += noise * noise
        }
        
        signalPower /= n
        noisePower /= n
        
        return if (noisePower > 1e-10) {
            10.0 * log10(signalPower / noisePower)
        } else {
            40.0 // Very high SNR
        }
    }
    
    /**
     * Convert SNR (dB) to score (0-70 points).
     * 
     * Mapping:
     * - SNR 15+ dB → 70 points
     * - SNR 10 dB → 50 points
     * - SNR 5 dB → 30 points
     * - SNR 0 dB → 10 points
     * - SNR <0 dB → 0 points
     */
    private fun computeSnrScore(snrDb: Double): Double {
        return when {
            snrDb >= 15.0 -> 70.0
            snrDb >= 0.0 -> 10.0 + (snrDb / 15.0) * 60.0
            else -> 0.0
        }.coerceIn(0.0, 70.0)
    }
    
    /**
     * Compute combined PTT confidence.
     * 
     * Formula:
     * confidence = (min(SQI_face, SQI_finger) / 100) * corrScore * sharpnessNorm
     * 
     * Where:
     * - sharpnessNorm = min(1.0, peakSharpness / 0.2)  (0.2 sharpness = full confidence)
     * 
     * Threshold: confidence ≥ 0.60 to report PTT
     * 
     * @param sqiFace Face channel SQI (0-100)
     * @param sqiFinger Finger channel SQI (0-100)
     * @param corrScore Cross-correlation score (0-1)
     * @param peakSharpness Peak sharpness from cross-correlation
     * @return Combined confidence (0-1)
     */
    fun computeCombinedConfidence(
        sqiFace: Int,
        sqiFinger: Int,
        corrScore: Double,
        peakSharpness: Double
    ): Double {
        // Weakest link for SQI
        val minSqi = minOf(sqiFace, sqiFinger)
        
        // Normalize sharpness (0.2 = full confidence)
        val sharpnessNorm = minOf(1.0, peakSharpness / 0.2)
        
        // Combined confidence
        val confidence = (minSqi / 100.0) * corrScore * sharpnessNorm
        
        return confidence.coerceIn(0.0, 1.0)
    }
    
    /**
     * Check if PTT should be reported based on confidence.
     * 
     * @param confidence Combined confidence (0-1)
     * @return true if confidence ≥ 0.60
     */
    fun shouldReportPtt(confidence: Double): Boolean {
        return confidence >= 0.60
    }
}

/**
 * Per-channel SQI result.
 */
data class ChannelSqiResult(
    val sqi: Int,                   // Overall SQI 0-100
    val snrDb: Double,              // SNR in dB
    val snrScore: Double,           // SNR score 0-70
    val regularityScore: Double,    // Peak regularity score 0-30
    val motionPenalty: Double,      // Motion penalty 0-100
    val peakCount: Int              // Number of detected peaks
)

