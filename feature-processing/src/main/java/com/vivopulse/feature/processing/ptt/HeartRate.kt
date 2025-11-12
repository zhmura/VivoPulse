package com.vivopulse.feature.processing.ptt

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Heart rate computation from peak detection results.
 * 
 * Computes HR in bpm and HRV metrics (RR variability).
 */
object HeartRate {
    
    /**
     * Compute heart rate from RR intervals.
     * 
     * Formula:
     * - HR (bpm) = 60,000 / mean(RR intervals in ms)
     * - HRV (CV) = std(RR) / mean(RR)
     * 
     * @param peakResult Peak detection result
     * @return HeartRateResult with HR and variability metrics
     */
    fun computeHeartRate(peakResult: PeakDetectResult): HeartRateResult {
        if (!peakResult.isValid || peakResult.rrIntervalsMs.isEmpty()) {
            return HeartRateResult(
                hrBpm = 0.0,
                rrMeanMs = 0.0,
                rrStdMs = 0.0,
                rrCV = 0.0,
                isValid = false,
                message = "No valid RR intervals"
            )
        }
        
        val rrIntervals = peakResult.rrIntervalsMs
        
        // Mean RR interval
        val meanRR = rrIntervals.average()
        
        // Heart rate (bpm)
        val hrBpm = 60_000.0 / meanRR
        
        // RR variability (std and CV)
        val variance = rrIntervals.map { (it - meanRR).pow(2) }.average()
        val stdRR = sqrt(variance)
        val cv = if (meanRR > 0) stdRR / meanRR else 0.0
        
        return HeartRateResult(
            hrBpm = hrBpm,
            rrMeanMs = meanRR,
            rrStdMs = stdRR,
            rrCV = cv,
            isValid = true,
            peakCount = peakResult.getPeakCount(),
            message = "HR=${String.format("%.1f", hrBpm)} bpm, CV=${String.format("%.3f", cv)}"
        )
    }
    
    /**
     * Check if HR is physiologically plausible.
     * 
     * @param hrBpm Heart rate in bpm
     * @return true if in range [40, 180] bpm
     */
    fun isHrPlausible(hrBpm: Double): Boolean {
        return hrBpm in 40.0..180.0
    }
    
    /**
     * Assess HR agreement between channels.
     * 
     * @param hrFace Heart rate from face channel
     * @param hrFinger Heart rate from finger channel
     * @param toleranceBpm Tolerance in bpm (default 5.0)
     * @return true if agreement within tolerance
     */
    fun checkHrAgreement(
        hrFace: Double,
        hrFinger: Double,
        toleranceBpm: Double = 5.0
    ): Boolean {
        return kotlin.math.abs(hrFace - hrFinger) <= toleranceBpm
    }
}

/**
 * Heart rate computation result.
 */
data class HeartRateResult(
    val hrBpm: Double,          // Heart rate in beats per minute
    val rrMeanMs: Double,       // Mean R-R interval in milliseconds
    val rrStdMs: Double,        // Standard deviation of R-R intervals
    val rrCV: Double,           // Coefficient of variation (std/mean)
    val isValid: Boolean,
    val peakCount: Int = 0,
    val message: String = ""
)

