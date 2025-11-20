package com.vivopulse.feature.processing.sqi

import kotlin.math.max
import kotlin.math.min

/**
 * Computes per-channel Signal Quality Index (SQI).
 *
 * Maps raw metrics (SNR, motion, saturation) to a 0-100 quality score.
 */
object ChannelSqi {

    /**
     * Compute SQI for Face channel.
     *
     * @param snrDb Signal-to-Noise Ratio in dB
     * @param motionRmsPx Motion RMS in pixels
     * @param imuRmsG IMU acceleration RMS in G
     * @return SQI score (0-100)
     */
    fun computeFaceSqi(
        snrDb: Double,
        motionRmsPx: Double,
        imuRmsG: Double
    ): Int {
        var score = 100.0
        
        // 1. SNR Penalty
        // Target: > 6dB is good. < 0dB is bad.
        if (snrDb < 6.0) {
            val penalty = (6.0 - snrDb) * 10.0 // -10 points per dB below 6
            score -= penalty
        }
        
        // 2. Motion Penalty
        // Target: < 0.5px is good. > 1.2px is bad.
        if (motionRmsPx > 0.5) {
            val penalty = (motionRmsPx - 0.5) * 40.0 // Rapid drop
            score -= penalty
        }
        
        // 3. IMU Penalty
        // Target: < 0.05g is good.
        if (imuRmsG > 0.05) {
            val penalty = (imuRmsG - 0.05) * 200.0 // Very sensitive to body motion
            score -= penalty
        }
        
        return clampScore(score)
    }
    
    /**
     * Compute SQI for Finger channel.
     *
     * @param snrDb Signal-to-Noise Ratio in dB
     * @param saturationPct Saturation percentage (0.0-1.0)
     * @param imuRmsG IMU acceleration RMS in G
     * @return SQI score (0-100)
     */
    fun computeFingerSqi(
        snrDb: Double,
        saturationPct: Double,
        imuRmsG: Double
    ): Int {
        var score = 100.0
        
        // 1. SNR Penalty
        // Finger usually has higher SNR. Target > 10dB.
        if (snrDb < 10.0) {
            val penalty = (10.0 - snrDb) * 8.0
            score -= penalty
        }
        
        // 2. Saturation Penalty
        // Target: < 5% (0.05)
        if (saturationPct > 0.05) {
            val penalty = (saturationPct - 0.05) * 500.0 // Severe penalty for clipping
            score -= penalty
        }
        
        // 3. IMU Penalty
        if (imuRmsG > 0.05) {
            val penalty = (imuRmsG - 0.05) * 200.0
            score -= penalty
        }
        
        return clampScore(score)
    }
    
    private fun clampScore(score: Double): Int {
        return max(0, min(100, score.toInt()))
    }
}
