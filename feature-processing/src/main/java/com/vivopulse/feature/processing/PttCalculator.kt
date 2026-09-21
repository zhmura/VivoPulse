package com.vivopulse.feature.processing

import com.vivopulse.feature.processing.sync.GoodSyncDetector
import com.vivopulse.feature.processing.sync.GoodSyncSegment
import com.vivopulse.feature.processing.ptt.CrossCorr

/**
 * Pulse Transit Time (PTT) calculator.
 *
 * Literature alignment:
 * - Computes dual-site optical PTT surrogate using Consensus Engine (XCorr + Foot-to-Foot).
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
        val pttOutput = processedSeries.pttOutput
        if (!processedSeries.isValid || !processedSeries.isAligned() ||
            processedSeries.invalidReasons.isNotEmpty() || pttOutput == null ||
            !pttOutput.isValid || pttOutput.pttMs?.isFinite() != true) {
            return PttResult(
                pttMs = Double.NaN,
                correlationScore = 0.0,
                stabilityMs = Double.NaN,
                isValid = false,
                message = processedSeries.invalidReasons.takeIf { it.isNotEmpty() }?.joinToString("|")
                    ?: pttOutput?.guidance?.joinToString("|") ?: "NO_REPORTABLE_DELAY"
            )
        }
        
        val faceSignal = processedSeries.faceSignal
        val fingerSignal = processedSeries.fingerSignal
        val sampleRate = processedSeries.sampleRateHz
        
        // The pipeline is the single authority. Never recompute around its rejection gates.
        
        // Use the same bounded GCC family as consensus. The legacy whole-lag
        // search can jump by entire cardiac cycles and mislabel a steady delay.
        // This SD describes variation across overlapping windows, not clinical
        // precision or a confidence interval for the session estimate.
        val windowResult = CrossCorr.multiWindowLag(faceSignal, fingerSignal, sampleRate,
            windowSec = 5.0, overlapFrac = 0.5, minLagMs = 0.0, maxLagMs = 400.0)
        val windowLags = windowResult.perWindowLagMs.filter { it.isFinite() }
        val stabilityAvailable = windowResult.isValid && windowLags.size >= 2
        val stabilityMs = if (stabilityAvailable) {
            val mean = windowLags.average()
            kotlin.math.sqrt(windowLags.sumOf { (it - mean) * (it - mean) } / windowLags.size)
        } else Double.NaN
        
        // Detect GoodSync segments for UI visualization and further analysis
        val goodSyncDetector = GoodSyncDetector()
        val segments = goodSyncDetector.detectSessionSegments(faceSignal, fingerSignal, sampleRate)
        
        return PttResult(
            pttMs = pttOutput.pttMs!!,
            correlationScore = pttOutput.corrScore,
            stabilityMs = stabilityMs,
            windowCount = if (stabilityAvailable) windowLags.size else 0,
            isValid = pttOutput.isValid,
            isReliable = pttOutput.confidence >= 0.60,
            isPlausible = pttOutput.pttMs != null,
            isStable = stabilityAvailable && stabilityMs <= 25.0,
            message = pttOutput.guidance?.joinToString(", ") ?: if (stabilityAvailable)
                "Experimental optical delay; bounded window SD ${"%.1f".format(java.util.Locale.US, stabilityMs)} ms (${windowLags.size} windows)"
                else "Experimental optical delay; insufficient windows to assess stability",
            goodSegments = segments
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
    val isReliable: Boolean = false, // Algorithmic quality >= 0.60, not calibrated probability
    val isPlausible: Boolean = false, // A finite accepted optical delay, not a clinical range check
    val isStable: Boolean = false,    // Stability < 25ms
    val message: String = "",
    val goodSegments: List<GoodSyncSegment> = emptyList()
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

