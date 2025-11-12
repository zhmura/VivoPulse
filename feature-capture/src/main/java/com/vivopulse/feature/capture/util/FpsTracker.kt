package com.vivopulse.feature.capture.util

import android.util.Log
import com.vivopulse.feature.capture.model.Source
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks FPS and dropped frames for camera streams.
 */
class FpsTracker(private val source: Source) {
    private val tag = "FpsTracker_${source.name}"
    private val frameTimestamps = ArrayDeque<Long>(100)
    private var totalFramesReceived = 0
    private var totalFramesDropped = 0
    private var lastLogTime = 0L
    private val logIntervalMs = 5000L // Log every 5 seconds

    @Synchronized
    fun onFrameReceived(timestampNs: Long) {
        totalFramesReceived++
        val currentTime = System.currentTimeMillis()
        frameTimestamps.addLast(currentTime)

        // Keep only last second of timestamps
        while (frameTimestamps.isNotEmpty() && 
               currentTime - frameTimestamps.first() > 1000) {
            frameTimestamps.removeFirst()
        }

        // Log stats periodically
        if (currentTime - lastLogTime > logIntervalMs) {
            logStats()
            lastLogTime = currentTime
        }
    }

    @Synchronized
    fun onFrameDropped() {
        totalFramesDropped++
    }

    @Synchronized
    fun getCurrentFps(): Float {
        if (frameTimestamps.size < 2) return 0f
        val timeSpan = frameTimestamps.last() - frameTimestamps.first()
        return if (timeSpan > 0) {
            (frameTimestamps.size - 1) * 1000f / timeSpan
        } else {
            0f
        }
    }

    @Synchronized
    fun getStats(): Triple<Int, Int, Float> {
        return Triple(totalFramesReceived, totalFramesDropped, getCurrentFps())
    }

    @Synchronized
    fun reset() {
        frameTimestamps.clear()
        totalFramesReceived = 0
        totalFramesDropped = 0
        lastLogTime = System.currentTimeMillis()
    }

    private fun logStats() {
        val fps = getCurrentFps()
        val dropRate = if (totalFramesReceived + totalFramesDropped > 0) {
            totalFramesDropped.toFloat() / (totalFramesReceived + totalFramesDropped) * 100
        } else {
            0f
        }
        Log.d(tag, "Stats: ${totalFramesReceived} frames, ${totalFramesDropped} dropped (${String.format("%.1f", dropRate)}%), FPS: ${String.format("%.1f", fps)}")
    }
}

