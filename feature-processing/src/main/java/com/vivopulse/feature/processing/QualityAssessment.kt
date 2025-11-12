package com.vivopulse.feature.processing

import com.vivopulse.signal.ChannelSQI
import com.vivopulse.signal.SignalQuality

/**
 * Quality assessment and suggestions for PPG capture.
 */
object QualityAssessment {
    
    /**
     * Assess signal quality and generate actionable suggestions.
     * 
     * @param processedSeries Processed signal data
     * @param pttResult PTT calculation result
     * @param faceMotionScore Optional face motion score (0-100)
     * @return QualityReport with scores and suggestions
     */
    fun assessQuality(
        processedSeries: ProcessedSeries,
        pttResult: PttResult,
        faceMotionScore: Double? = null
    ): QualityReport {
        if (!processedSeries.isValid) {
            return QualityReport(
                faceSQI = ChannelSQI(0.0, 0.0, 0.0, 0.0, 100.0, 0),
                fingerSQI = ChannelSQI(0.0, 0.0, 0.0, 0.0, 100.0, 0),
                combinedScore = 0.0,
                pttConfidence = 0.0,
                isGoodQuality = false,
                suggestions = listOf("No valid signal data"),
                shouldRetry = true
            )
        }
        
        // Compute per-channel SQI
        val faceSQI = SignalQuality.computeChannelSQI(
            processedSeries.faceSignal,
            processedSeries.sampleRateHz,
            motionScore = faceMotionScore
        )
        
        val fingerSQI = SignalQuality.computeChannelSQI(
            processedSeries.fingerSignal,
            processedSeries.sampleRateHz,
            motionScore = null // No motion tracking for finger
        )
        
        // Compute PTT confidence
        val pttConfidence = SignalQuality.computePttConfidence(
            faceSQI,
            fingerSQI,
            pttResult.correlationScore
        )
        
        // Combined score (average of channels)
        val combinedScore = (faceSQI.score + fingerSQI.score) / 2.0
        
        // Generate suggestions
        val suggestions = generateSuggestions(
            faceSQI,
            fingerSQI,
            pttResult,
            combinedScore,
            faceMotionScore
        )
        
        // Determine if quality is good enough
        val isGoodQuality = combinedScore >= 70.0 && pttConfidence >= 60.0
        val shouldRetry = combinedScore < 60.0 || pttConfidence < 50.0
        
        return QualityReport(
            faceSQI = faceSQI,
            fingerSQI = fingerSQI,
            combinedScore = combinedScore,
            pttConfidence = pttConfidence,
            isGoodQuality = isGoodQuality,
            shouldRetry = shouldRetry,
            suggestions = suggestions
        )
    }
    
    /**
     * Generate actionable suggestions based on quality metrics.
     */
    private fun generateSuggestions(
        faceSQI: ChannelSQI,
        fingerSQI: ChannelSQI,
        pttResult: PttResult,
        combinedScore: Double,
        faceMotionScore: Double?
    ): List<String> {
        val suggestions = mutableListOf<String>()
        
        // Face-specific issues
        if (faceSQI.snrScore < 50) {
            suggestions.add("Increase lighting on your face")
            suggestions.add("Position face camera toward a light source")
        }
        
        if (faceMotionScore != null && faceMotionScore < 70) {
            suggestions.add("Hold phone steadier")
            suggestions.add("Rest phone on a stable surface")
        }
        
        if (faceSQI.peakRegularity < 60) {
            suggestions.add("Stay still and breathe normally")
            suggestions.add("Reduce movement during capture")
        }
        
        if (faceSQI.peakCount < 5) {
            suggestions.add("Capture for a longer duration (30+ seconds)")
        }
        
        // Finger-specific issues
        if (fingerSQI.snrScore < 50) {
            suggestions.add("Turn on torch for finger camera")
            suggestions.add("Cover back camera lens completely with fingertip")
        }
        
        if (fingerSQI.peakRegularity < 60) {
            suggestions.add("Press finger gently and consistently")
            suggestions.add("Avoid varying finger pressure")
        }
        
        if (fingerSQI.peakCount < 5) {
            suggestions.add("Ensure finger fully covers camera lens")
        }
        
        // Correlation issues
        if (pttResult.correlationScore < 0.5) {
            suggestions.add("Signals are not well correlated")
            suggestions.add("Ensure both cameras capturing simultaneously")
        }
        
        // Stability issues
        if (!pttResult.isStable && pttResult.stabilityMs > 25.0) {
            suggestions.add("PTT is variable - minimize movement")
            suggestions.add("Maintain steady position throughout capture")
        }
        
        // Plausibility issues
        if (!pttResult.isPlausible) {
            suggestions.add("PTT value outside expected range")
            suggestions.add("Check that both cameras have clear view")
        }
        
        // General low quality
        if (combinedScore < 50) {
            suggestions.add("Overall signal quality is low")
            suggestions.add("Retry in better conditions")
        }
        
        // Remove duplicates and limit suggestions
        return suggestions.distinct().take(5)
    }
}

/**
 * Quality assessment report.
 */
data class QualityReport(
    val faceSQI: ChannelSQI,
    val fingerSQI: ChannelSQI,
    val combinedScore: Double,
    val pttConfidence: Double,
    val isGoodQuality: Boolean,
    val shouldRetry: Boolean,
    val suggestions: List<String>
) {
    /**
     * Get overall quality level.
     */
    fun getOverallQuality(): String {
        return when {
            combinedScore >= 80 -> "EXCELLENT"
            combinedScore >= 70 -> "GOOD"
            combinedScore >= 50 -> "FAIR"
            else -> "POOR"
        }
    }
    
    /**
     * Get quality color for UI.
     */
    fun getQualityColor(): Long {
        return when {
            combinedScore >= 80 -> 0xFF4CAF50 // Green
            combinedScore >= 70 -> 0xFF2196F3 // Blue
            combinedScore >= 50 -> 0xFFFFC107 // Yellow
            else -> 0xFFF44336 // Red
        }
    }
}

