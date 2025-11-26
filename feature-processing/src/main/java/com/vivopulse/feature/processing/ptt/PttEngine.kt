package com.vivopulse.feature.processing.ptt

/**
 * Integrated PTT engine combining all components.
 * 
 * Orchestrates peak detection, heart rate calculation, cross-correlation,
 * and confidence assessment.
 */
object PttEngine {
    
    private val consensus = PTTConsensus()
    
    /**
     * Compute PTT with full confidence assessment.
     * 
     * Pipeline:
     * 1. Detect peaks in both channels
     * 2. Compute heart rate from peaks
     * 3. Compute PTT Consensus (XCorr + Foot-to-Foot)
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
        
        // 3. Compute Consensus PTT
        val durationMs = (faceSig.size / fsHz * 1000).toLong()
        val consensusResult = consensus.estimateConsensusPtt(
            face = faceSig,
            finger = fingerSig,
            fsHz = fsHz,
            hrFaceBpm = hrFace.hrBpm,
            hrFingerBpm = hrFinger.hrBpm,
            segment = com.vivopulse.feature.processing.sync.Window(0, durationMs)
        )
        
        // Use median PTT from consensus
        val pttMsRaw = consensusResult.pttMsMedian
        val agreementScore = if (consensusResult.methodAgreeMs <= 50.0) 1.0 else 0.5
        
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
            corrScore = 0.8, // Placeholder corrScore, need to get from Consensus? No, SyncMetrics returns it inside Consensus
            peakSharpness = 0.0 // Placeholder
        ) * agreementScore
        
        // Re-calculate SyncMetrics for confidence inputs if needed, or expose them in ConsensusPtt
        // Let's compute metrics directly for confidence:
        val syncMetrics = com.vivopulse.feature.processing.sync.SyncMetrics.computeMetrics(
            faceSig, fingerSig, hrFace.hrBpm, hrFinger.hrBpm, fsHz
        )
        
        val finalConfidence = PttSqi.computeCombinedConfidence(
            sqiFace = sqiFace.sqi,
            sqiFinger = sqiFinger.sqi,
            corrScore = syncMetrics.correlation,
            peakSharpness = 0.5 // Simplified sharpness
        ) * agreementScore
        
        // 6. Determine if PTT should be reported
        val shouldReport = PttSqi.shouldReportPtt(finalConfidence)
        val pttMs = if (shouldReport) pttMsRaw else null
        
        // 7. Generate guidance if confidence low
        val guidance = if (!shouldReport) {
            generateLowConfidenceGuidance(sqiFace, sqiFinger, syncMetrics.correlation)
        } else {
            null
        }
        
        return PttOutput(
            pttMs = pttMs,
            corrScore = syncMetrics.correlation,
            confidence = finalConfidence,
            hrFaceBpm = hrFace.hrBpm,
            hrFingerBpm = hrFinger.hrBpm,
            sqiFace = sqiFace.sqi,
            sqiFinger = sqiFinger.sqi,
            peakSharpness = 0.5,
            facePeakCount = facePeaks.getPeakCount(),
            fingerPeakCount = fingerPeaks.getPeakCount(),
            guidance = guidance,
            isValid = finalConfidence > 0 && hrFace.isValid && hrFinger.isValid
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

