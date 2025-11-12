package com.vivopulse.signal

import kotlin.math.max

/**
 * Performance metrics tracker for signal processing.
 * 
 * Tracks processing times, memory usage, and performance statistics.
 */
class PerformanceMetrics {
    
    private val processingTimes = mutableListOf<Long>()
    private var totalFramesProcessed = 0L
    private var startMemoryBytes = 0L
    private var peakMemoryBytes = 0L
    
    /**
     * Record start of processing.
     */
    fun recordStart() {
        startMemoryBytes = Runtime.getRuntime().let { 
            it.totalMemory() - it.freeMemory() 
        }
    }
    
    /**
     * Record processing time for a frame/operation.
     * 
     * @param durationMs Processing duration in milliseconds
     */
    fun recordProcessingTime(durationMs: Long) {
        processingTimes.add(durationMs)
        totalFramesProcessed++
        updateMemoryPeak()
    }
    
    /**
     * Update peak memory usage.
     */
    private fun updateMemoryPeak() {
        val currentMemory = Runtime.getRuntime().let { 
            it.totalMemory() - it.freeMemory() 
        }
        peakMemoryBytes = max(peakMemoryBytes, currentMemory)
    }
    
    /**
     * Get mean processing time.
     */
    fun getMeanProcessingTimeMs(): Double {
        return if (processingTimes.isNotEmpty()) {
            processingTimes.average()
        } else {
            0.0
        }
    }
    
    /**
     * Get 95th percentile processing time.
     */
    fun get95thPercentileMs(): Double {
        if (processingTimes.isEmpty()) return 0.0
        
        val sorted = processingTimes.sorted()
        val index = (sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index].toDouble()
    }
    
    /**
     * Get maximum processing time.
     */
    fun getMaxProcessingTimeMs(): Long {
        return processingTimes.maxOrNull() ?: 0L
    }
    
    /**
     * Get memory footprint in MB.
     */
    fun getMemoryFootprintMB(): Double {
        return (peakMemoryBytes - startMemoryBytes) / (1024.0 * 1024.0)
    }
    
    /**
     * Get peak memory usage in MB.
     */
    fun getPeakMemoryMB(): Double {
        return peakMemoryBytes / (1024.0 * 1024.0)
    }
    
    /**
     * Get total frames processed.
     */
    fun getTotalFrames(): Long = totalFramesProcessed
    
    /**
     * Check if within performance budget.
     * 
     * @param maxAvgMs Maximum average time (default 8ms)
     * @param maxMemoryMB Maximum memory (default 200 MB)
     * @return true if within budget
     */
    fun isWithinBudget(maxAvgMs: Double = 8.0, maxMemoryMB: Double = 200.0): Boolean {
        val avgTime = getMeanProcessingTimeMs()
        val peakMem = getPeakMemoryMB()
        
        return avgTime <= maxAvgMs && peakMem <= maxMemoryMB
    }
    
    /**
     * Reset all metrics.
     */
    fun reset() {
        processingTimes.clear()
        totalFramesProcessed = 0
        startMemoryBytes = 0
        peakMemoryBytes = 0
    }
    
    /**
     * Get performance report.
     */
    fun getReport(): PerformanceReport {
        return PerformanceReport(
            meanProcessingMs = getMeanProcessingTimeMs(),
            p95ProcessingMs = get95thPercentileMs(),
            maxProcessingMs = getMaxProcessingTimeMs(),
            totalFrames = totalFramesProcessed,
            memoryFootprintMB = getMemoryFootprintMB(),
            peakMemoryMB = getPeakMemoryMB(),
            isWithinBudget = isWithinBudget()
        )
    }
}

/**
 * Performance report data.
 */
data class PerformanceReport(
    val meanProcessingMs: Double,
    val p95ProcessingMs: Double,
    val maxProcessingMs: Long,
    val totalFrames: Long,
    val memoryFootprintMB: Double,
    val peakMemoryMB: Double,
    val isWithinBudget: Boolean
) {
    /**
     * Get budget status message.
     */
    fun getBudgetStatus(): String {
        val issues = mutableListOf<String>()
        
        if (meanProcessingMs > 8.0) {
            issues.add("High CPU (${String.format("%.1f", meanProcessingMs)}ms avg, target <8ms)")
        }
        
        if (peakMemoryMB > 200.0) {
            issues.add("High memory (${String.format("%.0f", peakMemoryMB)}MB, target <200MB)")
        }
        
        return if (issues.isEmpty()) {
            "Within performance budget ✓"
        } else {
            issues.joinToString("; ")
        }
    }
}

