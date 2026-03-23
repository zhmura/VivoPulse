package com.vivopulse.feature.processing.ptt

import kotlin.math.*

/**
 * PTT Signal Quality Index (PTT-SQI) for confidence assessment.
 * 
 * P0.1 Research upgrade: Replaced multiplicative confidence chain
 * with logit-space weighted sum. No single factor can collapse the
 * confidence to zero — each contributes additively in logit space.
 * 
 * Outputs:
 * - Continuous confidence score (0–1)
 * - Quality tier (HIGH / MEDIUM / LOW / REJECTED)
 * - Uncertainty estimate (when available)
 */
object PttSqi {
    
    // ── Confidence thresholds ──
    const val THRESHOLD_HIGH = 0.75
    const val THRESHOLD_MEDIUM = 0.50
    const val THRESHOLD_LOW = 0.30
    
    /**
     * Quality tier for PTT measurement.
     */
    enum class QualityTier {
        HIGH,       // conf ≥ 0.75 — report with high confidence
        MEDIUM,     // conf ≥ 0.50 — report with caveat
        LOW,        // conf ≥ 0.30 — report as experimental
        REJECTED    // conf < 0.30 — do not report
    }
    
    /**
     * Compute per-channel SQI.
     * 
     * Components:
     * - SNR (0-70 points): band power (0.7-4.0 Hz) / noise power
     * - Peak regularity (0-30 points): 1 - CV(RR)
     * - Motion penalty: applied with weight 0.15 (was 0.0)
     */
    fun computeChannelSqi(
        filteredSignal: DoubleArray,
        rawSignal: DoubleArray,
        @Suppress("UNUSED_PARAMETER") fsHz: Double,
        peakResult: PeakDetectResult,
        motionPenalty: Double = 100.0
    ): ChannelSqiResult {
        // 1. SNR component (0-70 points)
        val snrDb = computeBandSnr(filteredSignal, rawSignal)
        val snrScore = computeSnrScore(snrDb) // 0-70
        
        // 2. Peak regularity component (0-30 points)
        val peakQuality = PeakDetect.assessPeakQuality(peakResult)
        val regularityScore = peakQuality * 0.3 // Scale to 0-30
        
        // 3. Motion component (P0.1 fix: now active with weight 0.15)
        // motionPenalty: 100 = no motion, 0 = extreme motion
        // converts to 0-15 points: 100 → 15, 0 → 0
        val motionScore = (motionPenalty / 100.0) * 15.0
        
        // Combined score: max possible = 70 + 30 + 15 = 115, clamped to 100
        val sqi = snrScore + regularityScore + motionScore
        
        return ChannelSqiResult(
            sqi = sqi.toInt().coerceIn(0, 100),
            snrDb = snrDb,
            snrScore = snrScore,
            regularityScore = regularityScore,
            motionPenalty = motionPenalty,
            peakCount = peakResult.getPeakCount()
        )
    }
    
    /**
     * Compute band-limited SNR.
     */
    private fun computeBandSnr(filtered: DoubleArray, raw: DoubleArray): Double {
        val n = minOf(filtered.size, raw.size)
        
        var signalPower = 0.0
        var noisePower = 0.0
        
        for (i in 0 until n) {
            val sig = filtered[i]
            val noise = raw[i] - filtered[i]
            signalPower += sig * sig
            noisePower += noise * noise
        }
        
        signalPower /= n
        noisePower /= n
        
        return if (noisePower > 1e-10) {
            10.0 * log10(signalPower / noisePower)
        } else {
            40.0 // Very high SNR
        }
    }
    
    /**
     * Convert SNR (dB) to score (0-70 points).
     */
    private fun computeSnrScore(snrDb: Double): Double {
        return when {
            snrDb >= 15.0 -> 70.0
            snrDb >= 0.0 -> 10.0 + (snrDb / 15.0) * 60.0
            else -> 0.0
        }.coerceIn(0.0, 70.0)
    }
    
    /**
     * Compute photometric SQI (R3-C).
     * Detects exposure steps and signal clipping.
     */
    fun computePhotometricSqi(rawSignal: DoubleArray): PhotometricSqiResult {
        if (rawSignal.size < 10) {
            return PhotometricSqiResult(score = 100, stepCount = 0, clipPercent = 0.0)
        }
        
        val diffs = DoubleArray(rawSignal.size - 1) { i -> rawSignal[i + 1] - rawSignal[i] }
        val absDiffs = diffs.map { abs(it) }
        val sortedAbsDiffs = absDiffs.sorted()
        val medianDiff = if (sortedAbsDiffs.size % 2 == 0) {
            (sortedAbsDiffs[sortedAbsDiffs.size / 2 - 1] + sortedAbsDiffs[sortedAbsDiffs.size / 2]) / 2.0
        } else {
            sortedAbsDiffs[sortedAbsDiffs.size / 2]
        }
        val madDiff = sortedAbsDiffs.map { abs(it - medianDiff) }.sorted().let { sorted ->
            if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            else sorted[sorted.size / 2]
        }
        
        val stepThreshold = maxOf(medianDiff + 6.0 * madDiff, medianDiff * 3.0)
        val stepCount = if (stepThreshold > 1e-10) {
            absDiffs.count { it > stepThreshold }
        } else 0
        
        val minVal = rawSignal.minOrNull() ?: 0.0
        val maxVal = rawSignal.maxOrNull() ?: 1.0
        val range = maxVal - minVal
        if (range < 1e-10) {
            return PhotometricSqiResult(score = 0, stepCount = rawSignal.size, clipPercent = 100.0)
        }
        val clipMargin = range * 0.01
        val clipCount = rawSignal.count { it <= minVal + clipMargin || it >= maxVal - clipMargin }
        val clipPercent = (clipCount.toDouble() / rawSignal.size) * 100.0
        
        val stepPercent = (stepCount.toDouble() / rawSignal.size) * 100.0
        val stepPenalty = (stepPercent * 8.0).coerceAtMost(40.0)
        val clipPenalty = (clipPercent * 2.0).coerceAtMost(30.0)
        
        val score = (100.0 - stepPenalty - clipPenalty).coerceIn(0.0, 100.0).toInt()
        
        return PhotometricSqiResult(score = score, stepCount = stepCount, clipPercent = clipPercent)
    }

    // ═══════════════════════════════════════════════════════════════
    // P0.1: Logit-space soft confidence (replaces multiplicative)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compute combined PTT confidence using logit-space weighted sum.
     * 
     * Instead of multiplying factors (where any ~0 kills everything),
     * each factor is mapped to a sigmoid membership μᵢ ∈ (0,1),
     * then combined in logit space:
     *     score = Σ wᵢ · logit(μᵢ)
     *     confidence = σ(score)
     * 
     * This is equivalent to a logistic regression model where each
     * quality metric is a feature. No single bad metric can collapse
     * confidence to zero.
     */
    fun computeCombinedConfidence(
        sqiFace: Int,
        sqiFinger: Int,
        corrScore: Double,
        peakSharpness: Double,
        delayStabilityScore: Double = 1.0,
        methodAgreeMs: Double = 0.0
    ): Double {
        // ── Map each factor to membership μ ∈ (ε, 1-ε) ──
        // Clamped to (0.01, 0.99) to prevent logit(0) = -Inf
        val eps = 0.01
        
        // 1. SQI: use soft-min instead of hard min
        //    soft-min(a,b) = -1/λ · log(e^(-λa) + e^(-λb))
        val lambda = 5.0 // temperature: higher = closer to hard min
        val sqiFaceNorm = (sqiFace / 100.0).coerceIn(eps, 1.0 - eps)
        val sqiFingerNorm = (sqiFinger / 100.0).coerceIn(eps, 1.0 - eps)
        val muSqi = softMin(sqiFaceNorm, sqiFingerNorm, lambda).coerceIn(eps, 1.0 - eps)
        
        // 2. Correlation: already ∈ [0, 1]
        val muCorr = corrScore.coerceIn(eps, 1.0 - eps)
        
        // 3. Peak sharpness: sigmoid mapping (now uses real value, not hardcoded)
        val muSharpness = sigmoid((peakSharpness - 0.05) / 0.05).coerceIn(eps, 1.0 - eps)
        
        // 4. Delay stability: already ∈ [0, 1]
        val muStability = delayStabilityScore.coerceIn(eps, 1.0 - eps)
        
        // 5. Method agreement: sigmoid gate on disagreement
        val muAgreement = when {
            methodAgreeMs == Double.MAX_VALUE -> 0.50 // Unknown = uncertain, not disqualifying
            methodAgreeMs <= 0.0 -> 0.95              // Perfect agreement
            else -> sigmoid((30.0 - methodAgreeMs) / 15.0).coerceIn(eps, 1.0 - eps)
        }
        
        // ── Logit-space weighted sum ──
        // Weights reflect relative importance:
        val weights = doubleArrayOf(
            2.0,   // SQI (most important — signal quality)
            1.5,   // Correlation (cross-channel agreement)
            0.5,   // Peak sharpness (supporting evidence)
            1.0,   // Delay stability (multi-window consistency)
            1.0    // Method agreement (xcorr vs foot-to-foot)
        )
        val memberships = doubleArrayOf(muSqi, muCorr, muSharpness, muStability, muAgreement)
        
        var logitSum = 0.0
        var weightSum = 0.0
        for (i in weights.indices) {
            logitSum += weights[i] * logit(memberships[i])
            weightSum += weights[i]
        }
        
        // Normalize by total weight so confidence range stays interpretable
        val normalizedLogit = logitSum / weightSum
        
        val confidence = sigmoid(normalizedLogit)
        
        return confidence.coerceIn(0.0, 1.0)
    }
    
    /**
     * Determine quality tier from confidence.
     */
    fun getQualityTier(confidence: Double): QualityTier {
        return when {
            confidence >= THRESHOLD_HIGH -> QualityTier.HIGH
            confidence >= THRESHOLD_MEDIUM -> QualityTier.MEDIUM
            confidence >= THRESHOLD_LOW -> QualityTier.LOW
            else -> QualityTier.REJECTED
        }
    }
    
    /**
     * Check if PTT should be reported based on confidence.
     * 
     * P0.1: Now reports for MEDIUM and above (was only HIGH/≥0.60).
     * LOW tier can optionally be shown with "experimental" caveat.
     */
    fun shouldReportPtt(confidence: Double): Boolean {
        return getQualityTier(confidence) != QualityTier.REJECTED
    }
    
    // ── Math helpers ──
    
    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
    
    private fun logit(p: Double): Double = ln(p / (1.0 - p))
    
    /**
     * Differentiable soft-min: penalizes the worse channel without hard cutoff.
     * softMin(a, b) ≈ min(a, b) as λ → ∞
     */
    private fun softMin(a: Double, b: Double, lambda: Double): Double {
        return -1.0 / lambda * ln(exp(-lambda * a) + exp(-lambda * b))
    }
}

/**
 * Per-channel SQI result.
 */
data class ChannelSqiResult(
    val sqi: Int,                   // Overall SQI 0-100
    val snrDb: Double,              // SNR in dB
    val snrScore: Double,           // SNR score 0-70
    val regularityScore: Double,    // Peak regularity score 0-30
    val motionPenalty: Double,      // Motion penalty 0-100
    val peakCount: Int              // Number of detected peaks
)

/**
 * Photometric SQI result (R3-C).
 */
data class PhotometricSqiResult(
    val score: Int,                 // Photometric SQI 0-100
    val stepCount: Int,             // Number of detected exposure steps
    val clipPercent: Double         // Percentage of clipped samples
)
