package com.vivopulse.feature.processing.coach

import com.vivopulse.feature.processing.ptt.PeakDetect
import com.vivopulse.feature.processing.ptt.PttSqi
import com.vivopulse.signal.DspFunctions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Smart capture coach for real-time quality monitoring and guidance.
 * 
 * Computes rolling SQI every 0.5s and provides actionable tips to improve signal quality.
 */
class SmartCaptureCoach(
    private val fsHz: Double = 30.0, // Camera frame rate
    private val updateIntervalMs: Long = 500 // Update tips every 500ms
) {
    
    companion object {
        const val SQI_GOOD_THRESHOLD = 70
        const val SQI_FAIR_THRESHOLD = 50
        const val SATURATION_THRESHOLD_PERCENT = 5.0
    }
    
    private val _faceSqi = MutableStateFlow(0)
    val faceSqi: StateFlow<Int> = _faceSqi.asStateFlow()
    
    private val _fingerSqi = MutableStateFlow(0)
    val fingerSqi: StateFlow<Int> = _fingerSqi.asStateFlow()
    
    private val _combinedSqi = MutableStateFlow(0)
    val combinedSqi: StateFlow<Int> = _combinedSqi.asStateFlow()
    
    private val _trafficLight = MutableStateFlow(TrafficLight.RED)
    val trafficLight: StateFlow<TrafficLight> = _trafficLight.asStateFlow()
    
    private val _topTip = MutableStateFlow<String?>(null)
    val topTip: StateFlow<String?> = _topTip.asStateFlow()
    
    private val _canStartRecording = MutableStateFlow(false)
    val canStartRecording: StateFlow<Boolean> = _canStartRecording.asStateFlow()
    
    private var lastUpdateTime = 0L
    private var goodQualityStartTime = 0L
    private val goodQualityDurationMs = 2000L // Need 2s of good quality to enable start
    
    /**
     * Update coach with new samples.
     * 
     * Call for each frame or periodically with buffered samples.
     * 
     * @param faceLumas Recent face luma values (e.g., last 15 samples = 0.5s @ 30fps)
     * @param fingerLumas Recent finger luma values
     * @param motionMagnitude Current optical flow magnitude (pixels)
     * @param saturationPercent Percentage of saturated pixels in finger ROI
     * @param torchEnabled Whether torch is currently on
     */
    fun update(
        faceLumas: List<Double>,
        fingerLumas: List<Double>,
        motionMagnitude: Double,
        saturationPercent: Double,
        torchEnabled: Boolean
    ) {
        val now = System.currentTimeMillis()
        
        // Throttle updates
        if (now - lastUpdateTime < updateIntervalMs) {
            return
        }
        lastUpdateTime = now
        
        // Compute rolling SQI
        val (faceSqiValue, fingerSqiValue) = computeRollingSqi(
            faceLumas, fingerLumas, motionMagnitude, saturationPercent
        )
        
        _faceSqi.value = faceSqiValue
        _fingerSqi.value = fingerSqiValue
        _combinedSqi.value = minOf(faceSqiValue, fingerSqiValue)
        
        // Update traffic light
        _trafficLight.value = when {
            _combinedSqi.value >= SQI_GOOD_THRESHOLD -> TrafficLight.GREEN
            _combinedSqi.value >= SQI_FAIR_THRESHOLD -> TrafficLight.YELLOW
            else -> TrafficLight.RED
        }
        
        // Generate top tip
        _topTip.value = generateTopTip(
            faceSqiValue, fingerSqiValue, motionMagnitude, saturationPercent, torchEnabled
        )
        
        // Update start gating
        updateStartGating()
    }
    
    /**
     * Compute rolling SQI from recent samples.
     */
    private fun computeRollingSqi(
        faceLumas: List<Double>,
        fingerLumas: List<Double>,
        motionMagnitude: Double,
        saturationPercent: Double
    ): Pair<Int, Int> {
        if (faceLumas.isEmpty() || fingerLumas.isEmpty()) {
            return Pair(0, 0)
        }
        
        // Base SQI from signal variance (proxy for pulsatility)
        val faceVariance = computeVariance(faceLumas)
        val fingerVariance = computeVariance(fingerLumas)
        
        // Convert variance to base score (0-70)
        val faceBaseScore = computeVarianceScore(faceVariance).toInt()
        val fingerBaseScore = computeVarianceScore(fingerVariance).toInt()
        
        // Motion penalty for face (0-30 points reduction)
        val motionPenalty = computeMotionPenalty(motionMagnitude)
        
        // Saturation penalty for finger (0-30 points reduction)
        val saturationPenalty = computeSaturationPenalty(saturationPercent)
        
        val faceSqi = (faceBaseScore - motionPenalty).coerceIn(0, 100)
        val fingerSqi = (fingerBaseScore - saturationPenalty).coerceIn(0, 100)
        
        return Pair(faceSqi, fingerSqi)
    }
    
    /**
     * Compute variance of signal.
     */
    private fun computeVariance(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return values.map { (it - mean) * (it - mean) }.average()
    }
    
    /**
     * Convert variance to SQI score.
     * Higher variance = better pulsatility = higher score
     */
    private fun computeVarianceScore(variance: Double): Double {
        // Variance of 50-200 (typical luma variance for good PPG) = 70 points
        // Variance < 10 (flat signal) = 0 points
        return when {
            variance >= 50.0 -> 70.0
            variance >= 10.0 -> (variance - 10.0) / 40.0 * 70.0
            else -> 0.0
        }.coerceIn(0.0, 70.0)
    }
    
    /**
     * Compute motion penalty.
     * 
     * 0-2 px = no penalty
     * 5 px = 15 point penalty
     * 10+ px = 30 point penalty
     */
    private fun computeMotionPenalty(motionMagnitude: Double): Int {
        return when {
            motionMagnitude < 2.0 -> 0
            motionMagnitude < 10.0 -> ((motionMagnitude - 2.0) / 8.0 * 30.0).toInt()
            else -> 30
        }.coerceIn(0, 30)
    }
    
    /**
     * Compute saturation penalty.
     * 
     * <2% saturated = no penalty
     * 5% saturated = 15 point penalty
     * >10% saturated = 30 point penalty
     */
    private fun computeSaturationPenalty(saturationPercent: Double): Int {
        return when {
            saturationPercent < 2.0 -> 0
            saturationPercent < 10.0 -> ((saturationPercent - 2.0) / 8.0 * 30.0).toInt()
            else -> 30
        }.coerceIn(0, 30)
    }
    
    /**
     * Generate top actionable tip.
     */
    private fun generateTopTip(
        faceSqi: Int,
        fingerSqi: Int,
        motionMagnitude: Double,
        saturationPercent: Double,
        torchEnabled: Boolean
    ): String? {
        // Prioritize tips by impact
        
        // Critical: torch needed for finger
        if (!torchEnabled && fingerSqi < 50) {
            return "Turn on torch for finger camera"
        }
        
        // Face issues (higher priority as it's harder to get right)
        if (faceSqi < SQI_FAIR_THRESHOLD) {
            return when {
                faceSqi < 30 -> "Increase face lighting (avoid shadows)"
                motionMagnitude > 5.0 -> "Hold phone steadier"
                else -> "Ensure face is centered and well-lit"
            }
        }
        
        // Finger issues
        if (fingerSqi < SQI_FAIR_THRESHOLD) {
            return when {
                saturationPercent > 5.0 -> "Reduce finger pressure slightly"
                fingerSqi < 30 -> "Ensure finger fully covers camera"
                else -> "Check torch is on and finger placement"
            }
        }
        
        // Motion warning
        if (motionMagnitude > 5.0) {
            return "Hold phone steadier"
        }
        
        // All good
        if (faceSqi >= SQI_GOOD_THRESHOLD && fingerSqi >= SQI_GOOD_THRESHOLD) {
            return "Quality good - ready to record"
        }
        
        return null
    }
    
    /**
     * Update start recording gating.
     * 
     * Requires combined SQI ≥60 for at least 2 seconds.
     */
    private fun updateStartGating() {
        val now = System.currentTimeMillis()
        val isGoodQuality = _combinedSqi.value >= 60
        
        if (isGoodQuality) {
            if (goodQualityStartTime == 0L) {
                goodQualityStartTime = now
            }
            
            val duration = now - goodQualityStartTime
            _canStartRecording.value = duration >= goodQualityDurationMs
        } else {
            goodQualityStartTime = 0L
            _canStartRecording.value = false
        }
    }
    
    /**
     * Reset coach state.
     */
    fun reset() {
        _faceSqi.value = 0
        _fingerSqi.value = 0
        _combinedSqi.value = 0
        _trafficLight.value = TrafficLight.RED
        _topTip.value = null
        _canStartRecording.value = false
        goodQualityStartTime = 0L
        lastUpdateTime = 0L
    }
}

/**
 * Traffic light enum for quality indication.
 */
enum class TrafficLight {
    RED,     // SQI < 50
    YELLOW,  // SQI 50-69
    GREEN    // SQI ≥ 70
}




