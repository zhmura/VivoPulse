package com.vivopulse.feature.processing.ptt

/**
 * Integrated PTT engine combining all components.
 * 
 * Orchestrates peak detection, heart rate calculation, cross-correlation,
 * and confidence assessment.
 * 
 * P0.1 Research upgrade: Uses real peak sharpness, quality tiers,
 * and soft confidence (logit-space) instead of brittle multiplicative chain.
 */
object PttEngine {
    
    private val consensus = PTTConsensus()
    
    /**
     * Compute PTT with full confidence assessment.
     * 
     * Pipeline:
     * 1. Detect peaks in both channels (MAD + prominence)
     * 2. Compute heart rate from peaks
     * 3. Compute PTT Consensus (XCorr + Foot-to-Foot)
     * 4. Compute per-channel SQI
     * 5. Compute combined confidence (logit-space)
     * 6. Return PTT with quality tier
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
            motionPenalty = 100.0
        )
        android.util.Log.d(tag, "SQI: Face=${sqiFace.sqi} (SNR=${sqiFace.snrScore}), Finger=${sqiFinger.sqi} (SNR=${sqiFinger.snrScore})")
        
        // P3-A DIAGNOSTIC: Band-limited SQI
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
        
        // P3-D DIAGNOSTIC: Adaptive bandpass
        val adaptiveLowFace = if (hrFace.hrBpm > 0) maxOf(0.5, (hrFace.hrBpm / 60.0) * 0.5) else 0.7
        val adaptiveLowFinger = if (hrFinger.hrBpm > 0) maxOf(0.5, (hrFinger.hrBpm / 60.0) * 0.5) else 0.7
        android.util.Log.i(tag, "BANDPASS_DIAG | hrFace=${"%.0f".format(hrFace.hrBpm)}bpm → adaptiveLow=${"%.2f".format(adaptiveLowFace)}Hz | " +
              "hrFinger=${"%.0f".format(hrFinger.hrBpm)}bpm → adaptiveLow=${"%.2f".format(adaptiveLowFinger)}Hz | currentLow=0.7Hz")
        
        // 5. Compute combined confidence (logit-space)
        val syncMetrics = com.vivopulse.feature.processing.sync.SyncMetrics.computeMetrics(
            faceSig, fingerSig, hrFace.hrBpm, hrFinger.hrBpm, fsHz
        )
        
        val photoSqiFace = PttSqi.computePhotometricSqi(faceRaw)
        val photoSqiFinger = PttSqi.computePhotometricSqi(fingerRaw)
        android.util.Log.i(tag, "PHOTO_SQI | face: score=${photoSqiFace.score} steps=${photoSqiFace.stepCount} clip=${"%.1f".format(photoSqiFace.clipPercent)}% | " +
              "finger: score=${photoSqiFinger.score} steps=${photoSqiFinger.stepCount} clip=${"%.1f".format(photoSqiFinger.clipPercent)}%")
        
        // P0.1: Compute REAL peak sharpness from cross-correlation
        val xcorrResult = CrossCorr.crossCorrelationLag(faceSig, fingerSig, fsHz)
        val realPeakSharpness = xcorrResult.peakSharpness
        
        val finalConfidence = PttSqi.computeCombinedConfidence(
            sqiFace = sqiFace.sqi,
            sqiFinger = sqiFinger.sqi,
            corrScore = syncMetrics.correlation,
            peakSharpness = realPeakSharpness, // P0.1 fix: real value, not 0.5
            delayStabilityScore = consensusResult.delayStabilityScore,
            methodAgreeMs = consensusResult.methodAgreeMs,
            coherenceAtHr = consensusResult.meanCoherenceAtHr
        )
        
        val qualityTier = PttSqi.getQualityTier(finalConfidence)
        
        android.util.Log.d(tag, "Confidence: ${"%.3f".format(finalConfidence)} ($qualityTier) | " +
              "Corr=${"%.2f".format(syncMetrics.correlation)}, " +
              "Stability=${"%.2f".format(consensusResult.delayStabilityScore)}, " +
              "Sharpness=${"%.3f".format(realPeakSharpness)}, " +
              "SQI=face:${sqiFace.sqi}/finger:${sqiFinger.sqi}")
        
        // 6. Determine if PTT should be reported (P0.1: MEDIUM+ now reported)
        val shouldReport = PttSqi.shouldReportPtt(finalConfidence)
        val pttMs = if (shouldReport) pttMsRaw else null
        
        if (!shouldReport) {
            android.util.Log.w(tag, "PTT Rejected: Confidence too low (${"%.3f".format(finalConfidence)} < ${PttSqi.THRESHOLD_LOW})")
        }
        
        // 7. Generate guidance if confidence low
        val guidance = if (qualityTier == PttSqi.QualityTier.LOW || 
                          qualityTier == PttSqi.QualityTier.REJECTED) {
            generateLowConfidenceGuidance(sqiFace, sqiFinger, syncMetrics.correlation)
        } else {
            null
        }
        
        return PttOutput(
            pttMs = pttMs,
            corrScore = syncMetrics.correlation,
            confidence = finalConfidence,
            qualityTier = qualityTier,
            hrFaceBpm = hrFace.hrBpm,
            hrFingerBpm = hrFinger.hrBpm,
            sqiFace = sqiFace.sqi,
            sqiFinger = sqiFinger.sqi,
            peakSharpness = realPeakSharpness,
            facePeakCount = facePeaks.getPeakCount(),
            fingerPeakCount = fingerPeaks.getPeakCount(),
            nBeats = consensusResult.nBeats,
            guidance = guidance,
            isValid = finalConfidence > 0 && hrFace.isValid && hrFinger.isValid,
            kalmanCiMs = consensusResult.kalmanCiMs,
            meanCoherenceAtHr = consensusResult.meanCoherenceAtHr,
            beatCoverage = consensusResult.beatCoverage
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
        
        if (sqiFace.sqi < 60) {
            when {
                sqiFace.snrScore < 30 -> tips.add("Improve face lighting (reduce shadows)")
                sqiFace.regularityScore < 15 -> tips.add("Hold device steadier (reduce face motion)")
                else -> tips.add("Check face camera positioning")
            }
        }
        
        if (sqiFinger.sqi < 60) {
            when {
                sqiFinger.snrScore < 30 -> tips.add("Reduce finger pressure on lens")
                sqiFinger.regularityScore < 15 -> tips.add("Ensure finger fully covers camera")
                else -> tips.add("Check torch is enabled and finger placement")
            }
        }
        
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
 * P0.1: Now includes qualityTier (HIGH/MEDIUM/LOW/REJECTED).
 * PTT is null only if REJECTED (conf < 0.30). MEDIUM and LOW
 * results are reported with appropriate caveats.
 */
data class PttOutput(
    val pttMs: Double?,             // Pulse transit time in ms (null if REJECTED)
    val corrScore: Double,          // Cross-correlation coefficient (0-1)
    val confidence: Double,         // Combined confidence (0-1), logit-space
    val qualityTier: PttSqi.QualityTier = PttSqi.QualityTier.REJECTED,
    val hrFaceBpm: Double,          // Heart rate from face channel (bpm)
    val hrFingerBpm: Double,        // Heart rate from finger channel (bpm)
    val sqiFace: Int,               // Face channel SQI (0-100)
    val sqiFinger: Int,             // Finger channel SQI (0-100)
    val peakSharpness: Double = 0.0,// Cross-correlation peak sharpness (real)
    val facePeakCount: Int = 0,     // Number of face peaks detected
    val fingerPeakCount: Int = 0,   // Number of finger peaks detected
    val nBeats: Int = 0,            // Number of valid foot-to-foot beats for PTT
    val guidance: List<String>? = null,
    val isValid: Boolean = false,
    val kalmanCiMs: Double = Double.MAX_VALUE,     // 95% CI half-width from Kalman fusion
    val meanCoherenceAtHr: Double = 0.0,           // Mean coherence at HR harmonic bins
    val beatCoverage: Double = 0.0                 // Valid beats / expected beats (0-1)
) {
    fun isPttReportable(): Boolean = pttMs != null && qualityTier != PttSqi.QualityTier.REJECTED
    
    fun hrAgreementGood(toleranceBpm: Double = 5.0): Boolean {
        return HeartRate.checkHrAgreement(hrFaceBpm, hrFingerBpm, toleranceBpm)
    }
    
    fun getConfidenceLevel(): String {
        return qualityTier.name
    }
}
