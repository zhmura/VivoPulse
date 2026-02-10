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
        @Suppress("UNUSED_PARAMETER") fsHz: Double,
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
     * Compute photometric SQI (R3-C).
     * 
     * Detects exposure steps and signal clipping that corrupt PPG quality.
     * 
     * @param rawSignal Raw (unfiltered) signal
     * @return PhotometricSqiResult with score, step count, clip percentage
     */
    fun computePhotometricSqi(rawSignal: DoubleArray): PhotometricSqiResult {
        if (rawSignal.size < 10) {
            return PhotometricSqiResult(score = 0, stepCount = 0, clipPercent = 0.0)
        }
        
        // 1. Detect exposure steps: |Δsignal[i]| > 3×MAD(Δsignal)
        val diffs = DoubleArray(rawSignal.size - 1) { i -> rawSignal[i + 1] - rawSignal[i] }
        val absDiffs = diffs.map { kotlin.math.abs(it) }
        val sortedAbsDiffs = absDiffs.sorted()
        val medianDiff = if (sortedAbsDiffs.size % 2 == 0) {
            (sortedAbsDiffs[sortedAbsDiffs.size / 2 - 1] + sortedAbsDiffs[sortedAbsDiffs.size / 2]) / 2.0
        } else {
            sortedAbsDiffs[sortedAbsDiffs.size / 2]
        }
        val madDiff = sortedAbsDiffs.map { kotlin.math.abs(it - medianDiff) }.sorted().let { sorted ->
            if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            else sorted[sorted.size / 2]
        }
        
        val stepThreshold = medianDiff + 3.0 * madDiff
        val stepCount = absDiffs.count { it > stepThreshold && stepThreshold > 1e-10 }
        
        // 2. Detect clipping: samples within 1% of min/max range
        val minVal = rawSignal.minOrNull() ?: 0.0
        val maxVal = rawSignal.maxOrNull() ?: 1.0
        val range = maxVal - minVal
        if (range < 1e-10) {
            return PhotometricSqiResult(score = 0, stepCount = rawSignal.size, clipPercent = 100.0)
        }
        val clipMargin = range * 0.01
        val clipCount = rawSignal.count { it <= minVal + clipMargin || it >= maxVal - clipMargin }
        val clipPercent = (clipCount.toDouble() / rawSignal.size) * 100.0
        
        // Score: start at 100, penalize steps and clipping
        val score = (100.0 - 20.0 * stepCount - 10.0 * clipPercent / 100.0 * 10.0)
            .coerceIn(0.0, 100.0).toInt()
        
        return PhotometricSqiResult(score = score, stepCount = stepCount, clipPercent = clipPercent)
    }

    /**
     * Compute combined PTT confidence (updated for R3-B + R3-C).
     * 
     * Formula:
     * confidence = (min(SQI_face, SQI_finger) / 100)
     *            × corrScore
     *            × sharpnessNorm
     *            × delayStability      ← R3-B: multi-window lag consistency
     *            × agreementNorm        ← method agreement (xcorr vs foot-to-foot)
     * 
     * Threshold: confidence ≥ 0.60 to report PTT
     * 
     * @param sqiFace Face channel SQI (0-100)
     * @param sqiFinger Finger channel SQI (0-100)
     * @param corrScore Cross-correlation score (0-1)
     * @param peakSharpness Peak sharpness from cross-correlation
     * @param delayStabilityScore R3-B: multi-window delay stability (0-1, default 1.0)
     * @param methodAgreeMs Agreement between xcorr and foot-to-foot (ms)
     * @return Combined confidence (0-1)
     */
    fun computeCombinedConfidence(
        sqiFace: Int,
        sqiFinger: Int,
        corrScore: Double,
        peakSharpness: Double,
        delayStabilityScore: Double = 1.0,
        methodAgreeMs: Double = 0.0
    ): Double {
        // Weakest link for SQI
        val minSqi = minOf(sqiFace, sqiFinger)
        
        // Normalize sharpness (0.2 = full confidence)
        val sharpnessNorm = minOf(1.0, peakSharpness / 0.2)
        
        // R3-B: delay stability factor
        val stabilityFactor = delayStabilityScore.coerceIn(0.0, 1.0)
        
        // Method agreement factor: 0ms = perfect (1.0), 50ms = 0.375, 80ms+ = 0.0
        // MAX_VALUE = unknown (foot-to-foot failed) → penalize to 0.7
        val agreementFactor = when {
            methodAgreeMs == Double.MAX_VALUE -> 0.7 // Unknown agreement
            methodAgreeMs <= 0.0 -> 1.0              // Perfect or default agreement
            else -> (1.0 - (methodAgreeMs / 80.0)).coerceIn(0.0, 1.0)
        }
        
        // Combined confidence
        val confidence = (minSqi / 100.0) * corrScore * sharpnessNorm * stabilityFactor * agreementFactor
        
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

/**
 * Photometric SQI result (R3-C).
 */
data class PhotometricSqiResult(
    val score: Int,                 // Photometric SQI 0-100
    val stepCount: Int,             // Number of detected exposure steps
    val clipPercent: Double         // Percentage of clipped samples
)
