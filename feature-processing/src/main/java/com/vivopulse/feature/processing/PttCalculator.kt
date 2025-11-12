package com.vivopulse.feature.processing

import com.vivopulse.signal.CrossCorrelation
import com.vivopulse.signal.LagStabilityResult

/**
 * Pulse Transit Time (PTT) calculator.
 *
 * Literature alignment:
 * - Computes dual-site optical PTT surrogate as the lag (ms) that maximizes
 *   normalized cross-correlation between proximal (face) and distal (finger) PPG-like signals.
 * - Includes windowed stability check and plausibility gating.
 *
 * Safety:
 * - This is NOT cfPWV or calibrated PWV in m/s, and NOT a blood pressure measurement.
 *   It is an experimental timing surrogate only.
 *
 * Computes lag between face and finger PPG signals using cross-correlation.
 */
object PttCalculator {
    
    /**
     * Compute PTT from processed signal series.
     * 
     * @param processedSeries Aligned and filtered face/finger signals
     * @return PttResult with lag, correlation, and stability metrics
     */
    fun computePtt(processedSeries: ProcessedSeries): PttResult {
        if (!processedSeries.isValid || !processedSeries.isAligned()) {
            return PttResult(
                pttMs = 0.0,
                correlationScore = 0.0,
                stabilityMs = 0.0,
                isValid = false,
                message = "Invalid or misaligned signal series"
            )
        }
        
        val faceSignal = processedSeries.faceSignal
        val fingerSignal = processedSeries.fingerSignal
        val sampleRate = processedSeries.sampleRateHz
        
        // Compute overall lag
        val lagResult = CrossCorrelation.computeLag(
            faceSignal,
            fingerSignal,
            sampleRateHz = sampleRate
        )
        
        if (!lagResult.isValid) {
            return PttResult(
                pttMs = 0.0,
                correlationScore = 0.0,
                stabilityMs = 0.0,
                isValid = false,
                message = "Cross-correlation failed"
            )
        }
        
        // Compute stability across sliding windows
        val stabilityResult = CrossCorrelation.computeLagStability(
            faceSignal,
            fingerSignal,
            sampleRateHz = sampleRate,
            windowSizeS = 10.0,
            overlapS = 5.0
        )
        
        return PttResult(
            pttMs = if (stabilityResult.isValid) stabilityResult.meanLagMs else lagResult.lagMs,
            correlationScore = if (stabilityResult.isValid) stabilityResult.meanCorrelation else lagResult.correlationScore,
            stabilityMs = if (stabilityResult.isValid) stabilityResult.stdLagMs else 0.0,
            windowCount = stabilityResult.windowCount,
            isValid = true,
            isReliable = lagResult.isReliable(),
            isPlausible = lagResult.isPlausible(),
            isStable = stabilityResult.isStable(),
            message = stabilityResult.message.ifEmpty { lagResult.message }
        )
    }
}

/**
 * PTT calculation result.
 */
data class PttResult(
    val pttMs: Double,              // Pulse transit time in milliseconds
    val correlationScore: Double,   // Cross-correlation coefficient (0-1)
    val stabilityMs: Double,        // Standard deviation of PTT across windows
    val windowCount: Int = 0,       // Number of windows analyzed
    val isValid: Boolean,
    val isReliable: Boolean = false, // Correlation > 0.7
    val isPlausible: Boolean = false, // PTT in 30-200ms range
    val isStable: Boolean = false,    // Stability < 25ms
    val message: String = ""
) {
    /**
     * Get quality indicator.
     * 
     * @return Quality level: EXCELLENT, GOOD, FAIR, POOR
     */
    fun getQuality(): Quality {
        return when {
            !isValid -> Quality.POOR
            !isPlausible -> Quality.POOR
            isReliable && isStable -> Quality.EXCELLENT
            isReliable -> Quality.GOOD
            correlationScore > 0.5 -> Quality.FAIR
            else -> Quality.POOR
        }
    }
    
    enum class Quality {
        EXCELLENT, GOOD, FAIR, POOR
    }
}

