package com.vivopulse.feature.processing.ptt

import com.vivopulse.signal.WelchEstimator
import kotlin.math.*

/**
 * Cross-Spectral Phase-slope delay estimator (CSP).
 * 
 * Estimates time delay τ from the linear relationship between
 * cross-spectral phase and frequency: φ_xy(f) ≈ -2πfτ + φ₀
 * 
 * Key advantages over NCC/GCC-PHAT:
 * - Robust to amplitude distortion (uses phase, not amplitude)
 * - Sub-sample resolution without ad-hoc interpolation
 * - Produces a principled uncertainty estimate (SE_τ) from coherence
 * - Exploits quasi-periodicity via HR-harmonic bin selection
 * 
 * P1.4 Research upgrade from: "Coherence-weighted cross-spectral
 * phase-slope delay estimator" (research v1, §1).
 */
object CrossSpectralPhaseDelay {
    
    /**
     * Result of CSP delay estimation.
     */
    data class CspResult(
        val delayMs: Double,           // Estimated delay in ms
        val standardErrorMs: Double,   // SE of delay estimate in ms
        val meanCoherence: Double,     // Mean γ²(f) at selected bins
        val nBins: Int,                // Number of frequency bins used
        val harmonicsUsed: Int         // Number of HR harmonics found
    )
    
    /**
     * Estimate delay using coherence-weighted cross-spectral phase slope.
     * 
     * @param face Face PPG signal (filtered)
     * @param finger Finger PPG signal (filtered)
     * @param fs Sampling frequency (Hz)
     * @param hrBpm Estimated heart rate (bpm) from finger or average
     * @param maxHarmonics Max number of HR harmonics to include
     * @param gammaMin Minimum coherence for bin inclusion
     * @param segmentLength Welch segment length (samples)
     * @return CspResult with delay, uncertainty, and quality metrics
     */
    fun estimateDelay(
        face: DoubleArray,
        finger: DoubleArray,
        fs: Double,
        hrBpm: Double,
        maxHarmonics: Int = 4,
        gammaMin: Double = 0.10,
        segmentLength: Int = 256
    ): CspResult {
        if (face.size < segmentLength || finger.size < segmentLength || hrBpm <= 0) {
            return CspResult(0.0, Double.MAX_VALUE, 0.0, 0, 0)
        }
        
        // 1. Compute Welch cross-spectral estimates
        val welch = WelchEstimator.crossSpectral(face, finger, fs, segmentLength)
        if (welch.nSegments == 0) {
            return CspResult(0.0, Double.MAX_VALUE, 0.0, 0, 0)
        }
        
        val freqs = welch.freqs
        val coherence = welch.coherence
        
        // 2. Compute cross-spectral phase: φ(f) = atan2(Im(S_xy), Re(S_xy))
        val phase = DoubleArray(freqs.size) { k ->
            atan2(welch.crossImag[k], welch.crossReal[k])
        }
        
        // 3. Select HR-harmonic bins
        val fHR = hrBpm / 60.0 // Hz
        val binBandwidth = 0.15 // Hz, search radius around each harmonic
        
        // Collect (frequency, phase, weight) tuples for bins near HR harmonics
        val omegaList = mutableListOf<Double>()
        val phiList = mutableListOf<Double>()
        val weightList = mutableListOf<Double>()
        var harmonicsFound = 0
        
        for (k in 1..maxHarmonics) {
            val fTarget = k * fHR
            if (fTarget > fs / 2 - binBandwidth || fTarget > 8.0) break
            
            var foundInHarmonic = false
            for (i in freqs.indices) {
                if (abs(freqs[i] - fTarget) <= binBandwidth) {
                    val gamma2 = coherence[i]
                    if (gamma2 >= gammaMin) {
                        omegaList.add(2.0 * PI * freqs[i])
                        phiList.add(phase[i])
                        weightList.add(gamma2) // coherence-weighted
                        foundInHarmonic = true
                    }
                }
            }
            if (foundInHarmonic) harmonicsFound++
        }
        
        // Need at least 3 bins for meaningful regression
        if (omegaList.size < 3) {
            val meanCoh = if (weightList.isNotEmpty()) weightList.average() else 0.0
            return CspResult(0.0, Double.MAX_VALUE, meanCoh, omegaList.size, harmonicsFound)
        }
        
        // 4. Unwrap phase (simple sequential unwrapping)
        val phiUnwrapped = unwrapPhase(phiList.toDoubleArray())
        
        // 5. Weighted least-squares: τ = Σ(w·ω·φ) / Σ(w·ω²)
        // Model: φ(ω) = -ω·τ  (ignoring intercept for simplicity)
        val omega = omegaList.toDoubleArray()
        val w = weightList.toDoubleArray()
        
        var sumWOP = 0.0  // Σ w·ω·φ
        var sumWOO = 0.0  // Σ w·ω²
        for (i in omega.indices) {
            sumWOP += w[i] * omega[i] * phiUnwrapped[i]
            sumWOO += w[i] * omega[i] * omega[i]
        }
        
        if (sumWOO < 1e-20) {
            return CspResult(0.0, Double.MAX_VALUE, w.average(), omega.size, harmonicsFound)
        }
        
        // Note: phase convention: φ = -ω·τ, so τ = -Σ(wωφ)/Σ(wω²)
        val tauSec = -sumWOP / sumWOO
        
        // 6. Compute SE from residuals
        val residuals = DoubleArray(omega.size) { i ->
            wrapToPi(phiUnwrapped[i] + omega[i] * tauSec) // residual = φ + ω·τ (should be ~0)
        }
        
        var sumWR2 = 0.0
        var sumW = 0.0
        for (i in residuals.indices) {
            sumWR2 += w[i] * residuals[i] * residuals[i]
            sumW += w[i]
        }
        val weightedResidVar = if (sumW > 0) sumWR2 / sumW else 1.0
        val seTauSec = sqrt(weightedResidVar / (sumWOO + 1e-20))
        
        val meanCoherence = w.average()
        
        // Plausibility check: PTT should be in [10, 400] ms range
        val tauMs = tauSec * 1000.0
        val seTauMs = seTauSec * 1000.0
        
        return CspResult(
            delayMs = tauMs,
            standardErrorMs = seTauMs,
            meanCoherence = meanCoherence,
            nBins = omega.size,
            harmonicsUsed = harmonicsFound
        )
    }
    
    /**
     * Simple sequential phase unwrapping.
     */
    private fun unwrapPhase(phase: DoubleArray): DoubleArray {
        if (phase.isEmpty()) return phase
        val result = phase.copyOf()
        for (i in 1 until result.size) {
            var diff = result[i] - result[i - 1]
            while (diff > PI) diff -= 2 * PI
            while (diff < -PI) diff += 2 * PI
            result[i] = result[i - 1] + diff
        }
        return result
    }
    
    /**
     * Wrap angle to [-π, π].
     */
    private fun wrapToPi(angle: Double): Double {
        var a = angle % (2 * PI)
        if (a > PI) a -= 2 * PI
        if (a < -PI) a += 2 * PI
        return a
    }
}
