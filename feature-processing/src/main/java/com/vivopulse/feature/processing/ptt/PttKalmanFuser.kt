package com.vivopulse.feature.processing.ptt

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Conservative same-window PTT fusion, retaining the original API name.
 * 
 * Combines measurements from CSP, XCorr, GCC-PHAT and Foot-to-Foot.
 * These estimates share input samples, so independent Kalman updates would
 * overstate precision. The current call evaluates one window independently.
 * 
 * Key advantages over threshold-based switching:
 * - Handles disagreement gracefully via uncertainty weighting
 * - Outlier measurements rejected around a robust evidence median
 * - Does not claim independent evidence from algorithms using the same samples
 * - The returned uncertainty is a model spread, not a calibrated clinical CI
 * 
 * Class and field names are retained for source compatibility.
 */
class PttKalmanFuser(
    initialPttMs: Double = 100.0,     // Prior PTT estimate
    initialVariance: Double = 2500.0, // Prior variance (50ms² — wide initial uncertainty)
    processNoiseMs2: Double = 25.0,   // Q: process noise variance (5ms)² per step
    private val chi2Gate: Double = 9.0 // Mahalanobis² gate (3σ threshold)
) {
    /**
     * Single measurement from one estimation method.
     */
    data class Measurement(
        val method: String,    // e.g., "CSP", "XCorr", "GCC", "F2F"
        val valueMsOrNull: Double?,  // PTT estimate in ms (null if method failed)
        val varianceMs2: Double      // Measurement variance in ms² (Inf if unknown)
    )
    
    /**
     * Fusion result.
     */
    data class FusionResult(
        val pttMs: Double,          // Fused PTT estimate
        val varianceMs2: Double,    // Fused variance (uncertainty²)
        val confidenceInterval: Double, // 1.96 x conservative model spread; coverage unvalidated
        val methodsUsed: Int,       // Number of methods that contributed
        val methodsRejected: Int,   // Number rejected by innovation gate
        val isStable: Boolean       // True if variance < 100 ms²
    )
    
    // Last result, exposed for diagnostics; not a substitute for observations.
    private var x: Double = initialPttMs   // State estimate
    private var P: Double = initialVariance // State variance
    private val Q: Double = processNoiseMs2
    
    /**
     * Fuse multiple measurements into a single PTT estimate.
     * 
     * @param measurements List of method measurements for this window/beat
     * @return FusionResult with fused PTT and uncertainty
     */
    fun fuse(measurements: List<Measurement>): FusionResult {
        val finite = measurements.filter {
            it.valueMsOrNull?.isFinite() == true && it.varianceMs2.isFinite() && it.varianceMs2 > 0
        }
        if (finite.isEmpty()) return FusionResult(
            Double.NaN, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0, 0, false
        )
        fun median(values: List<Double>): Double {
            val s = values.sorted()
            return if (s.size % 2 == 0) (s[s.size / 2 - 1] + s[s.size / 2]) / 2 else s[s.size / 2]
        }
        // Gate around evidence rather than the arbitrary 100 ms initialization.
        // This is order independent and works for zero and signed test delays.
        val center = median(finite.map { it.valueMsOrNull!! })
        val mad = median(finite.map { kotlin.math.abs(it.valueMsOrNull!! - center) })
        val referenceVariance = maxOf(Q, (1.4826 * mad) * (1.4826 * mad))
        val accepted = finite.filter {
            val residual = it.valueMsOrNull!! - center
            residual * residual <= chi2Gate * (it.varianceMs2 + referenceVariance)
        }
        if (accepted.isEmpty()) return FusionResult(
            Double.NaN, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0, finite.size, false
        )
        val precision = accepted.sumOf { 1.0 / it.varianceMs2 }
        val estimate = accepted.sumOf { it.valueMsOrNull!! / it.varianceMs2 } / precision
        val disagreement = accepted.sumOf {
            val delta = it.valueMsOrNull!! - estimate
            delta * delta / it.varianceMs2
        } / precision
        // Normalized inverse-variance weights give no
        // 1/N precision gain for repeating correlated estimates of the same data.
        val variance = accepted.size / precision + disagreement
        x = estimate
        P = variance
        return FusionResult(
            pttMs = estimate,
            varianceMs2 = variance,
            confidenceInterval = 1.96 * sqrt(variance),
            methodsUsed = accepted.size,
            methodsRejected = finite.size - accepted.size,
            isStable = variance < 100.0
        )
    }
    
    /**
     * Reset filter to initial state.
     */
    fun reset(initialPttMs: Double = 100.0, initialVariance: Double = 2500.0) {
        x = initialPttMs
        P = initialVariance
    }
    
    /**
     * Get current state estimate.
     */
    fun currentEstimate(): Pair<Double, Double> = Pair(x, P)
    
    companion object {
        /**
         * Convert CSP standard error to measurement variance.
         */
        fun cspToVariance(seTauMs: Double): Double {
            return if (seTauMs.isFinite() && seTauMs > 0) {
                maxOf(25.0, seTauMs * seTauMs) // Engineering floor (5 ms), not validated coverage
            } else {
                Double.POSITIVE_INFINITY
            }
        }
        
        /**
         * Convert multi-window MAD (ms) to measurement variance.
         * Uses a more principled mapping than the old linear MAD/20ms.
         */
        fun madToVariance(madMs: Double): Double {
            // MAD → σ ≈ 1.4826 × MAD for Gaussian
            val sigma = 1.4826 * madMs
            return sigma * sigma
        }
        
        /**
         * Convert foot-to-foot IQR to measurement variance.
         */
        fun iqrToVariance(iqrMs: Double): Double {
            // IQR → σ ≈ IQR / 1.349 for Gaussian
            val sigma = iqrMs / 1.349
            return sigma * sigma
        }
        
        /**
         * Convert cross-correlation peak value to approximate variance.
         * Higher correlation → lower variance.
         */
        fun corrToVariance(corrPeak: Double, lagMs: Double): Double {
            if (corrPeak <= 0.1) return Double.POSITIVE_INFINITY
            // Rough inverse mapping: σ ≈ baseSigma / corr
            val baseSigma = 15.0 // Base uncertainty at correlation = 1.0
            val sigma = baseSigma / corrPeak.coerceIn(0.1, 1.0)
            return sigma * sigma
        }
    }
}
