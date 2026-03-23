package com.vivopulse.feature.processing.ptt

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Scalar Kalman filter for PTT fusion.
 * 
 * Treats PTT as a slowly-varying scalar state and fuses measurements
 * from multiple estimation methods (CSP, XCorr, GCC-PHAT, Foot-to-Foot),
 * each with their own uncertainty estimate.
 * 
 * Key advantages over threshold-based switching:
 * - Handles disagreement gracefully via uncertainty weighting
 * - Outlier measurements rejected via Mahalanobis innovation gate
 * - Produces a fused estimate with tracked uncertainty
 * - Temporal smoothing prevents jump artifacts
 * 
 * P1.6 Research upgrade from: "Uncertainty-weighted fusion with a scalar
 * Kalman filter over windows/beats" (research v1, §3).
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
        val confidenceInterval: Double, // ±1.96×SE (95% CI half-width)
        val methodsUsed: Int,       // Number of methods that contributed
        val methodsRejected: Int,   // Number rejected by innovation gate
        val isStable: Boolean       // True if variance < 100 ms²
    )
    
    // Kalman state
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
        // Predict step: PTT is slowly varying
        val xPred = x
        val PPred = P + Q
        
        var xUpdate = xPred
        var PUpdate = PPred
        var methodsUsed = 0
        var methodsRejected = 0
        
        // Sequential update for each valid measurement
        for (m in measurements) {
            if (m.valueMsOrNull == null || m.varianceMs2.isInfinite() || m.varianceMs2 <= 0) {
                continue // Skip failed methods
            }
            
            val y = m.valueMsOrNull
            val R = m.varianceMs2
            
            // Innovation
            val nu = y - xUpdate
            val S = PUpdate + R // Innovation variance
            
            // Outlier gate: reject if innovation is > √chi2Gate sigmas
            val mahalanobis2 = (nu * nu) / S
            if (mahalanobis2 > chi2Gate) {
                methodsRejected++
                android.util.Log.d("KalmanFuser", "REJECTED ${m.method}: " +
                    "value=${"%.1f".format(y)}ms, innovation=${"%.1f".format(nu)}ms, " +
                    "mahal²=${"%.1f".format(mahalanobis2)} > gate=$chi2Gate")
                continue
            }
            
            // Kalman gain
            val K = PUpdate / S
            
            // Update
            xUpdate += K * nu
            PUpdate *= (1.0 - K)
            
            methodsUsed++
        }
        
        // Store updated state
        x = xUpdate
        P = PUpdate
        
        val se = sqrt(PUpdate)
        val ci95 = 1.96 * se
        
        return FusionResult(
            pttMs = xUpdate,
            varianceMs2 = PUpdate,
            confidenceInterval = ci95,
            methodsUsed = methodsUsed,
            methodsRejected = methodsRejected,
            isStable = PUpdate < 100.0 // SE < 10ms
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
                seTauMs * seTauMs
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
