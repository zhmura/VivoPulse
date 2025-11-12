package com.vivopulse.feature.processing.ptt

/**
 * Integrated PTT engine combining all components.
 * 
 * Orchestrates peak detection, heart rate calculation, cross-correlation,
 * and confidence assessment.
 */
object PttEngine {
    
    /**
     * Compute PTT with full confidence assessment.
     * 
     * Pipeline:
     * 1. Detect peaks in both channels
     * 2. Compute heart rate from peaks
     * 3. Compute cross-correlation lag (PTT)
     * 4. Compute per-channel SQI
     * 5. Compute combined confidence
     * 6. Return PTT if confidence ≥ 0.60, else null
     * 
     * @param faceSig Face signal (filtered, normalized)
     * @param fingerSig Finger signal (filtered, normalized)
     * @param faceRaw Face raw signal (before filtering)
     * @param fingerRaw Finger raw signal (before filtering)
     * @param fsHz Sample rate in Hz
     * @param windowSec Correlation window in seconds (default 20.0)
     * @param faceMotionPenalty Face motion penalty 0-100 (default 100 = no motion)
     * @return PttOutput with PTT, confidence, and quality metrics
     */
    fun computePtt(
        faceSig: DoubleArray,
        fingerSig: DoubleArray,
        faceRaw: DoubleArray,
        fingerRaw: DoubleArray,
        fsHz: Double,
        windowSec: Double = 20.0,
        faceMotionPenalty: Double = 100.0
    ): PttOutput {
        // 1. Detect peaks
        val facePeaks = PeakDetect.detectPeaks(faceSig, fsHz)
        val fingerPeaks = PeakDetect.detectPeaks(fingerSig, fsHz)
        
        // 2. Compute heart rate
        val hrFace = HeartRate.computeHeartRate(facePeaks)
        val hrFinger = HeartRate.computeHeartRate(fingerPeaks)
        
        // 3. Compute cross-correlation lag
        val xcorrResult = CrossCorr.crossCorrelationLag(faceSig, fingerSig, fsHz, windowSec)
        
        // 4. Compute per-channel SQI
        val sqiFace = PttSqi.computeChannelSqi(
            filteredSignal = faceSig,
            rawSignal = faceRaw,
            fsHz = fsHz,
            peakResult = facePeaks,
            motionPenalty = faceMotionPenalty
        )
        
        val sqiFinger = PttSqi.computeChannelSqi(
            filteredSignal = fingerSig,
            rawSignal = fingerRaw,
            fsHz = fsHz,
            peakResult = fingerPeaks,
            motionPenalty = 100.0 // Finger typically doesn't have motion
        )
        
        // 5. Compute combined confidence
        val confidence = PttSqi.computeCombinedConfidence(
            sqiFace = sqiFace.sqi,
            sqiFinger = sqiFinger.sqi,
            corrScore = xcorrResult.corrScore,
            peakSharpness = xcorrResult.peakSharpness
        )
        
        // 6. Determine if PTT should be reported
        val shouldReport = PttSqi.shouldReportPtt(confidence)
        val pttMs = if (shouldReport) xcorrResult.lagMs else null
        
        // 7. Generate guidance if confidence low
        val guidance = if (!shouldReport) {
            generateLowConfidenceGuidance(sqiFace, sqiFinger, xcorrResult.corrScore)
        } else {
            null
        }
        
        return PttOutput(
            pttMs = pttMs,
            corrScore = xcorrResult.corrScore,
            confidence = confidence,
            hrFaceBpm = hrFace.hrBpm,
            hrFingerBpm = hrFinger.hrBpm,
            sqiFace = sqiFace.sqi,
            sqiFinger = sqiFinger.sqi,
            peakSharpness = xcorrResult.peakSharpness,
            facePeakCount = facePeaks.getPeakCount(),
            fingerPeakCount = fingerPeaks.getPeakCount(),
            guidance = guidance,
            isValid = xcorrResult.isValid && hrFace.isValid && hrFinger.isValid
        )
    }
    
    /**
     * Generate guidance for low confidence scenarios.
     */
    private fun generateLowConfidenceGuidance(
        sqiFace: ChannelSqiResult,
        sqiFinger: ChannelSqiResult,
        corrScore: Double
    ): List<String> {
        val tips = mutableListOf<String>()
        
        // Check face quality
        if (sqiFace.sqi < 60) {
            when {
                sqiFace.snrScore < 30 -> tips.add("Improve face lighting (reduce shadows)")
                sqiFace.regularityScore < 15 -> tips.add("Hold device steadier (reduce face motion)")
                else -> tips.add("Check face camera positioning")
            }
        }
        
        // Check finger quality
        if (sqiFinger.sqi < 60) {
            when {
                sqiFinger.snrScore < 30 -> tips.add("Reduce finger pressure on lens")
                sqiFinger.regularityScore < 15 -> tips.add("Ensure finger fully covers camera")
                else -> tips.add("Check torch is enabled and finger placement")
            }
        }
        
        // Check correlation
        if (corrScore < 0.60) {
            tips.add("Hold both cameras steady (reduce movement)")
            tips.add("Ensure proper positioning for both face and finger")
        }
        
        if (tips.isEmpty()) {
            tips.add("Signal quality too low, please retry")
        }
        
        return tips
    }
}

/**
 * PTT computation output with full quality metrics.
 * 
 * Contains PTT, confidence, heart rate, and quality metrics for both channels.
 * PTT is null if confidence < 0.60 (unreliable).
 */
data class PttOutput(
    val pttMs: Double?,             // Pulse transit time in ms (null if low confidence)
    val corrScore: Double,          // Cross-correlation coefficient (0-1)
    val confidence: Double,         // Combined confidence (0-1)
    val hrFaceBpm: Double,          // Heart rate from face channel (bpm)
    val hrFingerBpm: Double,        // Heart rate from finger channel (bpm)
    val sqiFace: Int,               // Face channel SQI (0-100)
    val sqiFinger: Int,             // Finger channel SQI (0-100)
    val peakSharpness: Double = 0.0,// Cross-correlation peak sharpness
    val facePeakCount: Int = 0,     // Number of face peaks detected
    val fingerPeakCount: Int = 0,   // Number of finger peaks detected
    val guidance: List<String>? = null, // Low-confidence guidance tips
    val isValid: Boolean = false    // Overall validity
) {
    /**
     * Check if PTT is reportable.
     */
    fun isPttReportable(): Boolean = pttMs != null && confidence >= 0.60
    
    /**
     * Check if HR values agree within tolerance.
     */
    fun hrAgreementGood(toleranceBpm: Double = 5.0): Boolean {
        return HeartRate.checkHrAgreement(hrFaceBpm, hrFingerBpm, toleranceBpm)
    }
    
    /**
     * Get confidence level label.
     */
    fun getConfidenceLevel(): String {
        return when {
            confidence >= 0.80 -> "High"
            confidence >= 0.60 -> "Moderate"
            confidence >= 0.40 -> "Low"
            else -> "Very Low"
        }
    }
}

