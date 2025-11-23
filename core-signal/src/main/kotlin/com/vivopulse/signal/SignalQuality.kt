package com.vivopulse.signal

import kotlin.math.*

/**
 * Signal Quality Index (SQI) computation for PPG signals.
 * 
 * Provides quality metrics for signal reliability assessment.
 */
object SignalQuality {
    
    /**
     * Compute Signal-to-Noise Ratio (SNR) proxy.
     * 
     * SNR = (signal power in passband) / (noise power outside passband)
     * 
     * @param signal Input signal
     * @param sampleRateHz Sample rate
     * @param passbandLow Lower passband frequency (default 0.7 Hz)
     * @param passbandHigh Upper passband frequency (default 4.0 Hz)
     * @return SNR in dB, or 0 if calculation fails
     */
    fun computeSNR(
        signal: DoubleArray,
        sampleRateHz: Double,
        passbandLow: Double = 0.7,
        passbandHigh: Double = 4.0
    ): Double {
        if (signal.isEmpty()) return 0.0
        
        // Extract passband signal
        val passband = DspFunctions.butterworthBandpass(
            signal,
            lowCutoffHz = passbandLow,
            highCutoffHz = passbandHigh,
            sampleRateHz = sampleRateHz,
            order = 2
        )
        
        // Compute residual (noise) = original - passband
        val noise = signal.zip(passband) { orig, pass -> orig - pass }.toDoubleArray()
        
        // Compute powers
        val signalPower = DspFunctions.computePower(passband)
        val noisePower = DspFunctions.computePower(noise)
        
        // SNR in dB
        return if (noisePower > 1e-10) {
            20.0 * log10(signalPower / noisePower)
        } else {
            40.0 // Very high SNR (essentially no noise)
        }
    }
    
    /**
     * Compute peak regularity score based on inter-peak intervals.
     * 
     * Lower coefficient of variation = more regular peaks = better quality.
     * 
     * @param signal Input signal
     * @param sampleRateHz Sample rate
     * @return Regularity score (0-100), higher is better
     */
    fun computePeakRegularity(signal: DoubleArray, sampleRateHz: Double): Double {
        if (signal.isEmpty()) return 0.0
        
        // Find peaks
        val peaks = findPeaks(signal, minDistance = (sampleRateHz * 0.4).toInt()) // Min 0.4s between peaks
        
        if (peaks.size < 3) {
            // Need at least 3 peaks for regularity
            return 0.0
        }
        
        // Compute inter-peak intervals (RR intervals)
        val intervals = mutableListOf<Double>()
        for (i in 1 until peaks.size) {
            val intervalSamples = peaks[i] - peaks[i - 1]
            val intervalMs = intervalSamples * 1000.0 / sampleRateHz
            intervals.add(intervalMs)
        }
        
        // Coefficient of variation
        val mean = intervals.average()
        val variance = intervals.map { (it - mean).pow(2) }.average()
        val std = sqrt(variance)
        val cv = if (mean > 0) std / mean else 1.0
        
        // Convert CV to 0-100 score (lower CV = higher score)
        // CV of 0.05 (5%) = excellent = 95
        // CV of 0.20 (20%) = poor = 60
        // CV of 0.50 (50%) = very poor = 0
        val score = max(0.0, 100.0 * (1.0 - cv / 0.5))
        
        return score
    }
    
    /**
     * Find peaks in signal using simple threshold-based detection.
     * 
     * @param signal Input signal
     * @param minDistance Minimum distance between peaks (samples)
     * @return List of peak indices
     */
    fun findPeaks(signal: DoubleArray, minDistance: Int = 10): List<Int> {
        if (signal.size < 3) return emptyList()
        
        val peaks = mutableListOf<Int>()
        
        // Use adaptive threshold (mean + 0.5 * std)
        val mean = signal.average()
        val std = sqrt(signal.map { (it - mean).pow(2) }.average())
        val threshold = mean + 0.3 * std
        
        var lastPeakIndex = -minDistance
        
        for (i in 1 until signal.size - 1) {
            // Check if local maximum
            if (signal[i] > signal[i - 1] && 
                signal[i] > signal[i + 1] && 
                signal[i] > threshold &&
                i - lastPeakIndex >= minDistance) {
                peaks.add(i)
                lastPeakIndex = i
            }
        }
        
        return peaks
    }
    
    /**
     * Compute motion penalty from ROI position variance.
     * 
     * @param roiPositions List of ROI center positions over time
     * @return Motion score (0-100), higher means less motion
     */
    fun computeMotionScore(roiPositions: List<Pair<Float, Float>>): Double {
        if (roiPositions.size < 2) return 100.0
        
        // Compute position variance
        val xPositions = roiPositions.map { it.first }
        val yPositions = roiPositions.map { it.second }
        
        val xMean = xPositions.average()
        val yMean = yPositions.average()
        
        val xVariance = xPositions.map { (it - xMean).pow(2) }.average()
        val yVariance = yPositions.map { (it - yMean).pow(2) }.average()
        
        val totalVariance = xVariance + yVariance
        val motion = sqrt(totalVariance)
        
        // Convert motion to 0-100 score
        // 0 pixels motion = 100 score
        // 5 pixels motion = 80 score
        // 20 pixels motion = 0 score
        val score = max(0.0, 100.0 * (1.0 - motion / 20.0))
        
        return score
    }
    
    /**
     * Compute per-channel Signal Quality Index.
     * 
     * Combines SNR, peak regularity, motion (for face), and IMU stability.
     *
     * @param signal Processed signal
     * @param sampleRateHz Sample rate
     * @param motionScore Optional motion score (0-100)
     * @param imuScore Optional IMU stability score (0-100)
     * @return SQI score (0-100)
     */
    fun computeChannelSQI(
        signal: DoubleArray,
        sampleRateHz: Double,
        motionScore: Double? = null,
        imuScore: Double? = null
    ): ChannelSQI {
        val snr = computeSNR(signal, sampleRateHz)
        val regularity = computePeakRegularity(signal, sampleRateHz)
        val motion = motionScore ?: 100.0
        val imu = imuScore ?: 100.0
        
        // Convert SNR to 0-100 score
        // SNR 20 dB = excellent = 100
        val snrScore = max(0.0, min(100.0, (snr + 5.0) * 4.0))
        
        // Weighted combination
        val weights = mutableMapOf(
            "snr" to 0.5,
            "regularity" to 0.3,
            "motion" to 0.0,
            "imu" to 0.0
        )
        
        if (motionScore != null) {
            weights["snr"] = 0.4
            weights["regularity"] = 0.2
            weights["motion"] = 0.2
            weights["imu"] = 0.2
        } else if (imuScore != null) {
            weights["snr"] = 0.4
            weights["regularity"] = 0.3
            weights["imu"] = 0.3
        }
        
        // Normalize weights
        val sumWeights = weights.values.sum()
        val normWeights = weights.mapValues { it.value / sumWeights }
        
        val combinedScore = normWeights["snr"]!! * snrScore +
                           normWeights["regularity"]!! * regularity +
                           normWeights["motion"]!! * motion +
                           normWeights["imu"]!! * imu
        
        return ChannelSQI(
            score = combinedScore,
            snr = snr,
            snrScore = snrScore,
            peakRegularity = regularity,
            motionScore = motion,
            imuScore = imu,
            peakCount = findPeaks(signal, minDistance = (sampleRateHz * 0.4).toInt()).size
        )
    }
    
    /**
     * Compute combined PTT confidence score.
     * 
     * @param faceSQI Face channel quality
     * @param fingerSQI Finger channel quality
     * @param correlationScore Cross-correlation coefficient
     * @return Combined confidence (0-100)
     */
    fun computePttConfidence(
        faceSQI: ChannelSQI,
        fingerSQI: ChannelSQI,
        correlationScore: Double
    ): Double {
        // Take minimum of both channels (weakest link)
        val minChannelSQI = min(faceSQI.score, fingerSQI.score)
        
        // Factor in correlation (0-1 → 0-100)
        val corrScore = correlationScore * 100.0
        
        // Weighted combination
        val confidence = 0.6 * minChannelSQI + 0.4 * corrScore
        
        return confidence.coerceIn(0.0, 100.0)
    }
}

/**
 * Per-channel Signal Quality Index result.
 */
data class ChannelSQI(
    val score: Double,              // Overall 0-100 score
    val snr: Double,                // SNR in dB
    val snrScore: Double,           // SNR as 0-100 score
    val peakRegularity: Double,     // Regularity score 0-100
    val motionScore: Double,        // Motion score 0-100
    val imuScore: Double,           // IMU stability score 0-100
    val peakCount: Int             // Number of detected peaks
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
}

/**
 * Quality level enum.
 */
enum class QualityLevel {
    EXCELLENT, GOOD, FAIR, POOR
}

