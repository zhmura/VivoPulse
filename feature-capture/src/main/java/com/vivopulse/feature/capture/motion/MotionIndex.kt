package com.vivopulse.feature.capture.motion

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Motion index calculator for face ROI.
 * 
 * Computes optical flow magnitude to detect motion that degrades signal quality.
 */
class MotionIndex {
    
    companion object {
        const val LOW_MOTION_THRESHOLD = 2.0    // <2 px = stable
        const val HIGH_MOTION_THRESHOLD = 5.0   // >5 px = significant motion
        const val SPIKE_DURATION_MS = 300       // Motion spike must last >300ms
    }
    
    private val recentMagnitudes = mutableListOf<MotionSample>()
    private val maxHistorySize = 60 // 2 seconds at 30 fps
    
    /**
     * Add motion sample.
     * 
     * @param timestampMs Timestamp in milliseconds
     * @param magnitude Optical flow magnitude in pixels
     */
    fun addSample(timestampMs: Long, magnitude: Double) {
        recentMagnitudes.add(MotionSample(timestampMs, magnitude))
        
        while (recentMagnitudes.size > maxHistorySize) {
            recentMagnitudes.removeAt(0)
        }
    }
    
    /**
     * Get current motion level.
     */
    fun getCurrentMotionLevel(): MotionLevel {
        if (recentMagnitudes.isEmpty()) return MotionLevel.UNKNOWN
        
        val recentAvg = recentMagnitudes.takeLast(10).map { it.magnitude }.average()
        
        return when {
            recentAvg < LOW_MOTION_THRESHOLD -> MotionLevel.STABLE
            recentAvg < HIGH_MOTION_THRESHOLD -> MotionLevel.LOW
            else -> MotionLevel.HIGH
        }
    }
    
    /**
     * Check if currently in motion spike.
     * 
     * Motion spike = magnitude > HIGH_MOTION_THRESHOLD for > SPIKE_DURATION_MS
     */
    fun isInMotionSpike(): Boolean {
        if (recentMagnitudes.size < 10) return false
        
        val now = System.currentTimeMillis()
        val recent = recentMagnitudes.filter { now - it.timestampMs < SPIKE_DURATION_MS }
        
        if (recent.isEmpty()) return false
        
        val avgMagnitude = recent.map { it.magnitude }.average()
        return avgMagnitude > HIGH_MOTION_THRESHOLD
    }
    
    /**
     * Get motion penalty for SQI (0-30 points reduction).
     */
    fun getMotionPenalty(): Int {
        val level = getCurrentMotionLevel()
        
        return when (level) {
            MotionLevel.STABLE -> 0
            MotionLevel.LOW -> 10
            MotionLevel.MODERATE -> 20
            MotionLevel.HIGH -> 30
            MotionLevel.UNKNOWN -> 0
        }
    }
    
    /**
     * Get current average magnitude.
     */
    fun getCurrentMagnitude(): Double {
        if (recentMagnitudes.isEmpty()) return 0.0
        return recentMagnitudes.takeLast(10).map { it.magnitude }.average()
    }
    
    /**
     * Reset index.
     */
    fun reset() {
        recentMagnitudes.clear()
    }
}

/**
 * Motion sample.
 */
data class MotionSample(
    val timestampMs: Long,
    val magnitude: Double
)

