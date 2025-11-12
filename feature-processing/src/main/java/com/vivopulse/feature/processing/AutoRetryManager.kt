package com.vivopulse.feature.processing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Auto-retry manager for poor quality sessions.
 * 
 * Monitors SQI during capture and prompts user to retry with contextual tips
 * if quality remains poor (SQI < 60) for extended period (>5 seconds).
 */
class AutoRetryManager {
    
    companion object {
        const val LOW_SQI_THRESHOLD = 60.0
        const val LOW_SQI_DURATION_MS = 5000L // 5 seconds
    }
    
    private var lowSQIStartTime: Long = 0
    private var isInLowSQIPeriod = false
    
    private val _shouldRetry = MutableStateFlow(false)
    val shouldRetry: StateFlow<Boolean> = _shouldRetry.asStateFlow()
    
    private val _retryReason = MutableStateFlow<RetryReason?>(null)
    val retryReason: StateFlow<RetryReason?> = _retryReason.asStateFlow()
    
    private val _contextualTips = MutableStateFlow<List<String>>(emptyList())
    val contextualTips: StateFlow<List<String>> = _contextualTips.asStateFlow()
    
    /**
     * Update with current SQI values.
     * 
     * Call periodically during capture (e.g., every 500ms).
     * 
     * @param faceSQI Face channel SQI (0-100)
     * @param fingerSQI Finger channel SQI (0-100)
     * @param combinedSQI Combined SQI (0-100)
     * @param correlation Cross-correlation (0-1)
     */
    fun update(
        faceSQI: Double,
        fingerSQI: Double,
        combinedSQI: Double,
        correlation: Double
    ) {
        val now = System.currentTimeMillis()
        
        // Check if in low SQI state
        if (combinedSQI < LOW_SQI_THRESHOLD) {
            if (!isInLowSQIPeriod) {
                // Entering low SQI period
                lowSQIStartTime = now
                isInLowSQIPeriod = true
            } else {
                // Already in low SQI period, check duration
                val duration = now - lowSQIStartTime
                if (duration >= LOW_SQI_DURATION_MS) {
                    // Trigger retry recommendation
                    triggerRetry(faceSQI, fingerSQI, combinedSQI, correlation)
                }
            }
        } else {
            // Good SQI, reset
            if (isInLowSQIPeriod) {
                isInLowSQIPeriod = false
                lowSQIStartTime = 0
            }
        }
    }
    
    /**
     * Trigger retry recommendation with contextual tips.
     */
    private fun triggerRetry(
        faceSQI: Double,
        fingerSQI: Double,
        combinedSQI: Double,
        correlation: Double
    ) {
        if (_shouldRetry.value) return // Already triggered
        
        // Determine primary issue
        val reason = when {
            faceSQI < 50 && fingerSQI >= 60 -> RetryReason.POOR_FACE_QUALITY
            fingerSQI < 50 && faceSQI >= 60 -> RetryReason.POOR_FINGER_QUALITY
            correlation < 0.6 -> RetryReason.LOW_CORRELATION
            faceSQI < 60 && fingerSQI < 60 -> RetryReason.BOTH_CHANNELS_POOR
            else -> RetryReason.GENERAL_LOW_QUALITY
        }
        
        // Generate contextual tips
        val tips = generateTips(reason, faceSQI, fingerSQI, correlation)
        
        _shouldRetry.value = true
        _retryReason.value = reason
        _contextualTips.value = tips
    }
    
    /**
     * Generate contextual tips based on reason.
     */
    private fun generateTips(
        reason: RetryReason,
        faceSQI: Double,
        fingerSQI: Double,
        correlation: Double
    ): List<String> {
        return when (reason) {
            RetryReason.POOR_FACE_QUALITY -> listOf(
                "Improve face lighting (avoid shadows)",
                "Hold phone steadier",
                "Ensure face is centered in frame"
            )
            RetryReason.POOR_FINGER_QUALITY -> listOf(
                "Reduce finger pressure on lens",
                "Ensure finger fully covers camera",
                "Check torch is on"
            )
            RetryReason.LOW_CORRELATION -> listOf(
                "Hold both cameras steady",
                "Avoid movement during capture",
                "Ensure finger is placed correctly"
            )
            RetryReason.BOTH_CHANNELS_POOR -> listOf(
                "Find better lighting conditions",
                "Hold device very steady",
                "Relax and breathe normally"
            )
            RetryReason.GENERAL_LOW_QUALITY -> listOf(
                "Hold device steady",
                "Ensure good lighting",
                "Relax and try again"
            )
        }
    }
    
    /**
     * Reset retry state.
     */
    fun reset() {
        _shouldRetry.value = false
        _retryReason.value = null
        _contextualTips.value = emptyList()
        isInLowSQIPeriod = false
        lowSQIStartTime = 0
    }
    
    /**
     * Acknowledge retry (user dismissed or acted on it).
     */
    fun acknowledge() {
        reset()
    }
}

/**
 * Retry reason enum.
 */
enum class RetryReason {
    POOR_FACE_QUALITY,
    POOR_FINGER_QUALITY,
    LOW_CORRELATION,
    BOTH_CHANNELS_POOR,
    GENERAL_LOW_QUALITY
}

