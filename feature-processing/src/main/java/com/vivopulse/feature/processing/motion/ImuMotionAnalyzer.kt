package com.vivopulse.feature.processing.motion

import com.vivopulse.signal.DspFunctions
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * IMU Motion Analyzer for Walking Mode.
 * 
 * Detects step events and estimates step frequency from accelerometer data.
 */
class ImuMotionAnalyzer {

    data class MotionFeatures(
        val stepFrequencyHz: Double?,
        val rmsG: Double,
        val isWalking: Boolean
    )

    /**
     * Analyze IMU data for walking features.
     * 
     * @param imuRmsG Array of IMU RMS values (sampled at ~30Hz or similar)
     * @param sampleRateHz Sample rate of imuRmsG array
     * @return MotionFeatures
     */
    fun analyze(imuRmsG: DoubleArray, sampleRateHz: Double): MotionFeatures {
        if (imuRmsG.isEmpty()) {
            return MotionFeatures(null, 0.0, false)
        }
        
        val rms = imuRmsG.average()
        
        // Walking detection based on RMS threshold
        // Walking typically > 0.2G RMS variation?
        // Since imuRmsG is already variation (high-pass filtered magnitude),
        // mean value represents activity level.
        val isWalking = rms > 0.15
        
        var stepFreq: Double? = null
        
        if (isWalking) {
            // Estimate step frequency using zero-crossing or peak detection on the RMS signal
            // (Envelope of acceleration)
            // Step frequency usually 1.5 - 2.5 Hz (90 - 150 steps/min)
            
            // Detrend first
            val detrended = DspFunctions.detrend(imuRmsG, windowSize = (sampleRateHz * 2).toInt())
            
            // Find peaks
            val peaks = findPeaks(detrended, sampleRateHz)
            if (peaks.size >= 3) {
                // Calculate mean interval
                val intervals = peaks.zip(peaks.drop(1)) { a, b -> (b - a) / sampleRateHz }
                val meanInterval = intervals.average()
                if (meanInterval > 0) {
                    val freq = 1.0 / meanInterval
                    if (freq in 1.0..3.0) {
                        stepFreq = freq
                    }
                }
            }
        }
        
        return MotionFeatures(stepFreq, rms, isWalking)
    }
    
    private fun findPeaks(signal: DoubleArray, fs: Double): List<Int> {
        // Simple peak detector
        val peaks = mutableListOf<Int>()
        val threshold = signal.maxOrNull()?.times(0.3) ?: 0.1
        val minDist = (fs * 0.3).toInt() // Min 300ms between steps
        
        var lastPeak = -minDist
        for (i in 1 until signal.size - 1) {
            if (signal[i] > signal[i-1] && signal[i] > signal[i+1] && signal[i] > threshold) {
                if (i - lastPeak >= minDist) {
                    peaks.add(i)
                    lastPeak = i
                }
            }
        }
        return peaks
    }
}

