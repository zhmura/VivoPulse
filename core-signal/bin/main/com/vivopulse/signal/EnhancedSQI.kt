package com.vivopulse.signal

import kotlin.math.*

/**
 * Enhanced Signal Quality Index (SQI 1.0) for production accuracy.
 * 
 * Combines:
 * - Band SNR (0.7–4.0 Hz passband vs outside)
 * - Peak regularity (RR interval coefficient of variation)
 * - Motion penalty (optical flow magnitude for face)
 * 
 * Target: ≥70 combined SQI for usable sessions.
 */
object EnhancedSQI {
    
    /**
     * Compute band-limited SNR.
     * 
     * SNR = power in passband (0.7–4.0 Hz) / power outside passband
     * 
     * @param signal Filtered signal (already bandpassed)
     * @param rawSignal Raw signal (before filtering)
     * @param sampleRateHz Sample rate
     * @return Band SNR in dB
     */
    fun computeBandSNR(
        signal: DoubleArray,
        rawSignal: DoubleArray,
        sampleRateHz: Double
    ): Double {
        if (signal.isEmpty() || rawSignal.isEmpty()) return 0.0
        
        // Passband signal power (already filtered 0.7-4.0 Hz)
        val passbandPower = DspFunctions.computePower(signal)
        
        // Noise = raw - filtered
        val noise = DoubleArray(minOf(rawSignal.size, signal.size)) { i ->
            rawSignal[i] - signal[i]
        }
        val noisePower = DspFunctions.computePower(noise)
        
        // SNR in dB
        return if (noisePower > 1e-10) {
            10.0 * log10(passbandPower / noisePower)
        } else {
            40.0 // Very high SNR
        }
    }
    
    /**
     * Compute RR regularity score.
     * 
     * Lower CV (coefficient of variation) = more regular = better quality.
     * 
     * @param signal Filtered signal
     * @param sampleRateHz Sample rate
     * @return Regularity score 0-100 (higher is better)
     */
    fun computeRRRegularity(signal: DoubleArray, sampleRateHz: Double): Double {
        if (signal.isEmpty()) return 0.0
        
        // Find peaks
        val peaks = SignalQuality.findPeaks(signal, minDistance = (sampleRateHz * 0.4).toInt())
        
        if (peaks.size < 3) return 0.0
        
        // Compute RR intervals (peak-to-peak)
        val rrIntervals = mutableListOf<Double>()
        for (i in 1 until peaks.size) {
            val intervalSamples = peaks[i] - peaks[i - 1]
            val intervalMs = intervalSamples * 1000.0 / sampleRateHz
            rrIntervals.add(intervalMs)
        }
        
        // Coefficient of variation
        val mean = rrIntervals.average()
        val variance = rrIntervals.map { (it - mean).pow(2) }.average()
        val std = sqrt(variance)
        val cv = if (mean > 0) std / mean else 1.0
        
        // Convert to 0-100 score
        // CV < 0.05 (5%) = excellent = 100
        // CV = 0.10 (10%) = good = 80
        // CV = 0.20 (20%) = fair = 60
        // CV > 0.40 (40%) = poor = 0
        val score = max(0.0, 100.0 * (1.0 - cv / 0.4))
        
        return score
    }
    
    /**
     * Compute motion penalty from optical flow magnitude.
     * 
     * @param flowMagnitudes List of optical flow magnitudes (pixels)
     * @return Motion penalty 0-100 (0 = high motion penalty, 100 = no motion)
     */
    fun computeMotionPenalty(flowMagnitudes: List<Double>): Double {
        if (flowMagnitudes.isEmpty()) return 100.0
        
        // Average flow magnitude
        val avgFlow = flowMagnitudes.average()
        
        // Convert to penalty
        // 0-2 pixels = no penalty (100)
        // 5 pixels = moderate penalty (80)
        // 10 pixels = severe penalty (50)
        // 20+ pixels = maximum penalty (0)
        val score = max(0.0, 100.0 * (1.0 - avgFlow / 20.0))
        
        return score
    }
    
    /**
     * Compute enhanced per-channel SQI 1.0.
     * 
     * Combines band SNR, RR regularity, and motion penalty (for face).
     * 
     * @param filteredSignal Filtered signal (0.7-4.0 Hz bandpass)
     * @param rawSignal Raw signal (before filtering)
     * @param sampleRateHz Sample rate
     * @param flowMagnitudes Optional optical flow magnitudes (for face camera)
     * @return Enhanced SQI result
     */
    fun computeChannelSQI(
        filteredSignal: DoubleArray,
        rawSignal: DoubleArray,
        sampleRateHz: Double,
        flowMagnitudes: List<Double>? = null
    ): EnhancedChannelSQI {
        val bandSNR = computeBandSNR(filteredSignal, rawSignal, sampleRateHz)
        val rrRegularity = computeRRRegularity(filteredSignal, sampleRateHz)
        val motionScore = flowMagnitudes?.let { computeMotionPenalty(it) } ?: 100.0
        
        // Convert SNR to 0-100 score
        // SNR 15+ dB = excellent = 100
        // SNR 10 dB = good = 75
        // SNR 5 dB = fair = 50
        // SNR 0 dB = poor = 25
        // SNR < 0 dB = very poor = 0
        val snrScore = max(0.0, min(100.0, 25.0 + bandSNR * 5.0))
        
        // Weighted combination
        val weights = if (flowMagnitudes != null) {
            mapOf("snr" to 0.4, "regularity" to 0.3, "motion" to 0.3)
        } else {
            mapOf("snr" to 0.5, "regularity" to 0.5, "motion" to 0.0)
        }
        
        val combinedScore = weights["snr"]!! * snrScore +
                           weights["regularity"]!! * rrRegularity +
                           weights["motion"]!! * motionScore
        
        return EnhancedChannelSQI(
            score = combinedScore,
            bandSNR = bandSNR,
            snrScore = snrScore,
            rrRegularity = rrRegularity,
            motionScore = motionScore,
            peakCount = SignalQuality.findPeaks(filteredSignal, minDistance = (sampleRateHz * 0.4).toInt()).size
        )
    }
    
    /**
     * Compute combined SQI from face and finger channels.
     * 
     * Combined SQI = min(face, finger) * correlation_factor
     * 
     * @param faceSQI Face channel SQI
     * @param fingerSQI Finger channel SQI
     * @param correlation Cross-correlation coefficient (0-1)
     * @return Combined SQI 0-100
     */
    fun computeCombinedSQI(
        faceSQI: EnhancedChannelSQI,
        fingerSQI: EnhancedChannelSQI,
        correlation: Double
    ): Double {
        // Weakest link approach
        val minChannelSQI = min(faceSQI.score, fingerSQI.score)
        
        // Correlation factor (0.7 = 0.85x, 0.9 = 1.0x, 1.0 = 1.05x)
        val corrFactor = 0.5 + 0.55 * correlation
        
        val combined = minChannelSQI * corrFactor
        
        return combined.coerceIn(0.0, 100.0)
    }
}

/**
 * Enhanced per-channel SQI 1.0 result.
 */
data class EnhancedChannelSQI(
    val score: Double,              // Overall 0-100 score
    val bandSNR: Double,            // Band-limited SNR in dB
    val snrScore: Double,           // SNR as 0-100 score
    val rrRegularity: Double,       // RR regularity 0-100
    val motionScore: Double,        // Motion score 0-100 (higher = less motion)
    val peakCount: Int              // Number of detected peaks
) {
    /**
     * Get quality level.
     */
    fun getQualityLevel(): QualityLevel {
        return when {
            score >= 80 -> QualityLevel.EXCELLENT
            score >= 70 -> QualityLevel.GOOD
            score >= 50 -> QualityLevel.FAIR
            else -> QualityLevel.POOR
        }
    }
    
    /**
     * Get primary issue if score < 70.
     */
    fun getPrimaryIssue(): String? {
        if (score >= 70) return null
        
        return when {
            snrScore < 60 -> "Low SNR (noisy signal)"
            rrRegularity < 60 -> "Irregular peaks"
            motionScore < 60 -> "Excessive motion"
            else -> "General low quality"
        }
    }
}

