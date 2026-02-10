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
        faceMotionPenalty: Double = 100.0,
        footDetectionEnabled: Boolean = true
    ): PttOutput {
        val tag = "PttEngine"
        
        // 1. Detect peaks
        val facePeaks = PeakDetect.detectPeaks(faceSig, fsHz)
        val fingerPeaks = PeakDetect.detectPeaks(fingerSig, fsHz)
        android.util.Log.d(tag, "Peaks: Face=${facePeaks.getPeakCount()}, Finger=${fingerPeaks.getPeakCount()}")
        
        // 2. Compute heart rate
        val hrFace = HeartRate.computeHeartRate(facePeaks)
        val hrFinger = HeartRate.computeHeartRate(fingerPeaks)
        android.util.Log.d(tag, "HR: Face=${"%.1f".format(hrFace.hrBpm)} bpm (valid=${hrFace.isValid}), Finger=${"%.1f".format(hrFinger.hrBpm)} bpm (valid=${hrFinger.isValid})")
        
        // 3. Compute Consensus PTT
        val durationMs = (faceSig.size / fsHz * 1000).toLong()
        val consensusResult = consensus.estimateConsensusPtt(
            face = faceSig,
            finger = fingerSig,
            fsHz = fsHz,
            hrFaceBpm = hrFace.hrBpm,
            hrFingerBpm = hrFinger.hrBpm,
            segment = com.vivopulse.feature.processing.sync.Window(0, durationMs),
            footDetectionEnabled = footDetectionEnabled
        )
        
        // Use median PTT from consensus
        val pttMsRaw = consensusResult.pttMsMedian
        android.util.Log.d(tag, "Consensus: PTT=${"%.1f".format(pttMsRaw)} ms, Agreement=${"%.1f".format(consensusResult.methodAgreeMs)} ms (nBeats=${consensusResult.nBeats}, stability=${"%.2f".format(consensusResult.delayStabilityScore)})")
        
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
        android.util.Log.d(tag, "SQI: Face=${sqiFace.sqi} (SNR=${sqiFace.snrScore}), Finger=${sqiFinger.sqi} (SNR=${sqiFinger.snrScore})")
        
        // P3-A DIAGNOSTIC: Compute band-limited SQI alongside raw SQI for comparison
        // Band-limited SQI uses filtered signal as both input and reference (no raw noise)
        val bandSqiFace = PttSqi.computeChannelSqi(
            filteredSignal = faceSig, rawSignal = faceSig, fsHz = fsHz,
            peakResult = facePeaks, motionPenalty = faceMotionPenalty
        )
        val bandSqiFinger = PttSqi.computeChannelSqi(
            filteredSignal = fingerSig, rawSignal = fingerSig, fsHz = fsHz,
            peakResult = fingerPeaks, motionPenalty = 100.0
        )
        android.util.Log.i(tag, "SQI_DUAL_DIAG | face: raw=${sqiFace.sqi}(snr=${"%.1f".format(sqiFace.snrDb)}dB) band=${bandSqiFace.sqi}(snr=${"%.1f".format(bandSqiFace.snrDb)}dB) | " +
              "finger: raw=${sqiFinger.sqi}(snr=${"%.1f".format(sqiFinger.snrDb)}dB) band=${bandSqiFinger.sqi}(snr=${"%.1f".format(bandSqiFinger.snrDb)}dB)")
        
        // P3-D DIAGNOSTIC: Log what adaptive bandpass cutoff would be
        val adaptiveLowFace = if (hrFace.hrBpm > 0) maxOf(0.5, (hrFace.hrBpm / 60.0) * 0.5) else 0.7
        val adaptiveLowFinger = if (hrFinger.hrBpm > 0) maxOf(0.5, (hrFinger.hrBpm / 60.0) * 0.5) else 0.7
        android.util.Log.i(tag, "BANDPASS_DIAG | hrFace=${"%.0f".format(hrFace.hrBpm)}bpm → adaptiveLow=${"%.2f".format(adaptiveLowFace)}Hz | " +
              "hrFinger=${"%.0f".format(hrFinger.hrBpm)}bpm → adaptiveLow=${"%.2f".format(adaptiveLowFinger)}Hz | currentLow=0.7Hz")
        
        // 5. Compute combined confidence (R3-B + R3-C integrated)
        // Re-calculate SyncMetrics for confidence inputs
        val syncMetrics = com.vivopulse.feature.processing.sync.SyncMetrics.computeMetrics(
            faceSig, fingerSig, hrFace.hrBpm, hrFinger.hrBpm, fsHz
        )
        
        // R3-C: Photometric SQI (exposure steps + clipping detection)
        val photoSqiFace = PttSqi.computePhotometricSqi(faceRaw)
        val photoSqiFinger = PttSqi.computePhotometricSqi(fingerRaw)
        android.util.Log.i(tag, "PHOTO_SQI | face: score=${photoSqiFace.score} steps=${photoSqiFace.stepCount} clip=${"%.1f".format(photoSqiFace.clipPercent)}% | " +
              "finger: score=${photoSqiFinger.score} steps=${photoSqiFinger.stepCount} clip=${"%.1f".format(photoSqiFinger.clipPercent)}%")
        
        // Blend photometric SQI into channel SQI (weighted average: 80% band SQI + 20% photometric)
        val blendedSqiFace = ((sqiFace.sqi * 0.8 + photoSqiFace.score * 0.2).toInt()).coerceIn(0, 100)
        val blendedSqiFinger = ((sqiFinger.sqi * 0.8 + photoSqiFinger.score * 0.2).toInt()).coerceIn(0, 100)
        
        val finalConfidence = PttSqi.computeCombinedConfidence(
            sqiFace = blendedSqiFace,
            sqiFinger = blendedSqiFinger,
            corrScore = syncMetrics.correlation,
            peakSharpness = 0.5, // Simplified sharpness
            delayStabilityScore = consensusResult.delayStabilityScore,
            methodAgreeMs = consensusResult.methodAgreeMs
        )
        
        android.util.Log.d(tag, "Confidence: ${"%.2f".format(finalConfidence)} (Corr=${"%.2f".format(syncMetrics.correlation)}, " +
              "Stability=${"%.2f".format(consensusResult.delayStabilityScore)}, " +
              "SQI=face:$blendedSqiFace/finger:$blendedSqiFinger)")
        
        // 6. Determine if PTT should be reported
        val shouldReport = PttSqi.shouldReportPtt(finalConfidence)
        val pttMs = if (shouldReport) pttMsRaw else null
        
        if (!shouldReport) {
            android.util.Log.w(tag, "PTT Rejected: Confidence too low ($finalConfidence < 0.60)")
        }
        
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
            sqiFace = blendedSqiFace,
            sqiFinger = blendedSqiFinger,
            peakSharpness = 0.5,
            facePeakCount = facePeaks.getPeakCount(),
            fingerPeakCount = fingerPeaks.getPeakCount(),
            nBeats = consensusResult.nBeats,
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
    val nBeats: Int = 0,            // Number of valid foot-to-foot beats for PTT
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

