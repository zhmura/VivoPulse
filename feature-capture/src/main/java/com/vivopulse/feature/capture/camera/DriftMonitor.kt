package com.vivopulse.feature.capture.camera

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors timestamp drift between dual cameras.
 * 
 * Tracks:
 * - Per-camera FPS and jitter
 * - Inter-camera drift (ms/s)
 * - Frame drops
 */
class DriftMonitor {
    private val tag = "DriftMonitor"
    
    // Drift metrics
    private val _driftMetrics = MutableStateFlow(DriftMetrics())
    val driftMetrics: StateFlow<DriftMetrics> = _driftMetrics.asStateFlow()
    
    // Timestamp buffers (last 300 frames ~ 10s at 30fps)
    private val frontTimestamps = ArrayDeque<Long>(300)
    private val backTimestamps = ArrayDeque<Long>(300)
    
    // FPS tracking
    private var lastFrontFpsCalc = 0L
    private var lastBackFpsCalc = 0L
    
    /**
     * Record front camera timestamp.
     */
    fun recordFrontTimestamp(timestampNs: Long) {
        if (frontTimestamps.size >= 300) frontTimestamps.removeFirst()
        frontTimestamps.add(timestampNs)
        
        updateMetrics()
    }
    
    /**
     * Record back camera timestamp.
     */
    fun recordBackTimestamp(timestampNs: Long) {
        if (backTimestamps.size >= 300) backTimestamps.removeFirst()
        backTimestamps.add(timestampNs)
        
        updateMetrics()
    }
    
    /**
     * Update drift metrics.
     */
    private fun updateMetrics() {
        if (frontTimestamps.size < 30 || backTimestamps.size < 30) return
        
        val now = System.currentTimeMillis()
        
        // Calculate FPS every 1 second
        if (now - lastFrontFpsCalc > 1000) {
            val frontFps = calculateFps(frontTimestamps)
            val frontJitter = calculateJitter(frontTimestamps)
            val backFps = calculateFps(backTimestamps)
            val backJitter = calculateJitter(backTimestamps)
            val drift = calculateDrift()
            
            _driftMetrics.value = DriftMetrics(
                frontFps = frontFps,
                backFps = backFps,
                frontJitterMs = frontJitter,
                backJitterMs = backJitter,
                interCameraDriftMsPerSec = drift,
                frontDrops = countDrops(frontTimestamps, frontFps),
                backDrops = countDrops(backTimestamps, backFps)
            )
            
            lastFrontFpsCalc = now
            lastBackFpsCalc = now
            
            if (drift > 5.0) {
                Log.w(tag, "High inter-camera drift: ${drift}ms/s")
            }
        }
    }
    
    /**
     * Calculate FPS from timestamps.
     */
    private fun calculateFps(timestamps: ArrayDeque<Long>): Double {
        if (timestamps.size < 2) return 0.0
        
        val durationNs = timestamps.last() - timestamps.first()
        val durationSec = durationNs / 1_000_000_000.0
        
        return if (durationSec > 0) timestamps.size / durationSec else 0.0
    }
    
    /**
     * Calculate jitter (standard deviation of frame intervals).
     */
    private fun calculateJitter(timestamps: ArrayDeque<Long>): Double {
        if (timestamps.size < 2) return 0.0
        
        val intervals = mutableListOf<Double>()
        for (i in 1 until timestamps.size) {
            val intervalMs = (timestamps[i] - timestamps[i - 1]) / 1_000_000.0
            intervals.add(intervalMs)
        }
        
        val mean = intervals.average()
        val variance = intervals.map { (it - mean) * (it - mean) }.average()
        
        return kotlin.math.sqrt(variance)
    }
    
    /**
     * Calculate inter-camera drift (ms/s).
     * 
     * Measures how much the clocks drift apart over time.
     */
    private fun calculateDrift(): Double {
        if (frontTimestamps.size < 30 || backTimestamps.size < 30) return 0.0
        
        // Compare first and last timestamp differences
        val initialDiff = (backTimestamps.first() - frontTimestamps.first()) / 1_000_000.0
        val finalDiff = (backTimestamps.last() - frontTimestamps.last()) / 1_000_000.0
        
        val durationSec = (frontTimestamps.last() - frontTimestamps.first()) / 1_000_000_000.0
        
        return if (durationSec > 0) {
            (finalDiff - initialDiff) / durationSec
        } else {
            0.0
        }
    }
    
    /**
     * Count frame drops (intervals > 1.5x expected).
     */
    private fun countDrops(timestamps: ArrayDeque<Long>, fps: Double): Int {
        if (timestamps.size < 2 || fps <= 0) return 0
        
        val expectedIntervalMs = 1000.0 / fps
        val threshold = expectedIntervalMs * 1.5
        
        var drops = 0
        for (i in 1 until timestamps.size) {
            val intervalMs = (timestamps[i] - timestamps[i - 1]) / 1_000_000.0
            if (intervalMs > threshold) drops++
        }
        
        return drops
    }
    
    /**
     * Reset monitor.
     */
    fun reset() {
        frontTimestamps.clear()
        backTimestamps.clear()
        _driftMetrics.value = DriftMetrics()
    }
}

/**
 * Drift metrics data class.
 */
data class DriftMetrics(
    val frontFps: Double = 0.0,
    val backFps: Double = 0.0,
    val frontJitterMs: Double = 0.0,
    val backJitterMs: Double = 0.0,
    val interCameraDriftMsPerSec: Double = 0.0,
    val frontDrops: Int = 0,
    val backDrops: Int = 0
) {
    /**
     * Check if drift is acceptable (< 5ms/s).
     */
    fun isAcceptable(): Boolean = kotlin.math.abs(interCameraDriftMsPerSec) < 5.0
}
