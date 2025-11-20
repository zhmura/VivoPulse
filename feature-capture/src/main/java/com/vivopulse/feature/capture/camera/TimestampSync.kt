package com.vivopulse.feature.capture.camera

/**
 * Timestamp synchronization and resampling utility.
 * 
 * Resamples dual-camera signals to a unified timeline (100Hz) to handle:
 * - Different camera frame rates
 * - Timestamp drift
 * - Frame drops
 */
object TimestampSync {
    
    /**
     * Resample two signal streams to a unified 100Hz timeline.
     * 
     * @param faceSignal Face camera signal with timestamps
     * @param fingerSignal Finger camera signal with timestamps
     * @param targetHz Target sampling rate (default 100Hz)
     * @return Pair of resampled signals with unified timestamps
     */
    fun resampleToUnifiedTimeline(
        faceSignal: List<TimestampedValue>,
        fingerSignal: List<TimestampedValue>,
        targetHz: Double = 100.0
    ): ResampledSignals {
        if (faceSignal.isEmpty() || fingerSignal.isEmpty()) {
            return ResampledSignals(emptyList(), emptyList(), emptyList())
        }
        
        // Find common time range
        val startNs = maxOf(faceSignal.first().timestampNs, fingerSignal.first().timestampNs)
        val endNs = minOf(faceSignal.last().timestampNs, fingerSignal.last().timestampNs)
        
        if (startNs >= endNs) {
            return ResampledSignals(emptyList(), emptyList(), emptyList())
        }
        
        // Generate unified timeline
        val intervalNs = (1_000_000_000.0 / targetHz).toLong()
        val timestamps = mutableListOf<Long>()
        var t = startNs
        while (t <= endNs) {
            timestamps.add(t)
            t += intervalNs
        }
        
        // Resample both signals
        val resampledFace = resample(faceSignal, timestamps)
        val resampledFinger = resample(fingerSignal, timestamps)
        
        return ResampledSignals(
            timestamps = timestamps,
            faceValues = resampledFace,
            fingerValues = resampledFinger
        )
    }
    
    /**
     * Resample a signal to target timestamps using linear interpolation.
     */
    private fun resample(
        signal: List<TimestampedValue>,
        targetTimestamps: List<Long>
    ): List<Double> {
        return targetTimestamps.map { targetT ->
            interpolate(signal, targetT)
        }
    }
    
    /**
     * Interpolate value at target timestamp.
     */
    private fun interpolate(signal: List<TimestampedValue>, targetT: Long): Double {
        // Find surrounding points
        var idx = signal.binarySearch { it.timestampNs.compareTo(targetT) }
        
        if (idx >= 0) {
            // Exact match
            return signal[idx].value
        }
        
        // Binary search returns -(insertion point) - 1 for non-exact match
        val insertionPoint = -(idx + 1)
        
        when {
            insertionPoint == 0 -> {
                // Before first point, use first value
                return signal.first().value
            }
            insertionPoint >= signal.size -> {
                // After last point, use last value
                return signal.last().value
            }
            else -> {
                // Interpolate between two points
                val before = signal[insertionPoint - 1]
                val after = signal[insertionPoint]
                
                val t0 = before.timestampNs.toDouble()
                val t1 = after.timestampNs.toDouble()
                val v0 = before.value
                val v1 = after.value
                
                val alpha = (targetT - t0) / (t1 - t0)
                return v0 + alpha * (v1 - v0)
            }
        }
    }
    
    /**
     * Mask windows with high drift or frame drops.
     * 
     * @param driftMetrics Drift metrics for the session
     * @param windowSizeMs Window size in milliseconds
     * @return List of bad window ranges (start, end) in nanoseconds
     */
    fun maskBadWindows(
        timestamps: List<Long>,
        driftMetrics: DriftMetrics,
        windowSizeMs: Long = 1000
    ): List<Pair<Long, Long>> {
        val badWindows = mutableListOf<Pair<Long, Long>>()
        
        // If overall drift is too high, mark entire session as bad
        if (!driftMetrics.isAcceptable()) {
            if (timestamps.isNotEmpty()) {
                badWindows.add(Pair(timestamps.first(), timestamps.last()))
            }
            return badWindows
        }
        
        // Check for frame drops in sliding windows
        val windowSizeNs = windowSizeMs * 1_000_000
        var windowStart = 0
        
        for (i in timestamps.indices) {
            // Move window start forward
            while (windowStart < i && timestamps[i] - timestamps[windowStart] > windowSizeNs) {
                windowStart++
            }
            
            // Check for drops in this window
            val windowTimestamps = timestamps.subList(windowStart, i + 1)
            if (hasFrameDrops(windowTimestamps)) {
                badWindows.add(Pair(timestamps[windowStart], timestamps[i]))
            }
        }
        
        return badWindows
    }
    
    /**
     * Check if window has frame drops.
     */
    private fun hasFrameDrops(timestamps: List<Long>): Boolean {
        if (timestamps.size < 2) return false
        
        val expectedIntervalNs = 33_333_333L // ~30fps
        val threshold = expectedIntervalNs * 1.5
        
        for (i in 1 until timestamps.size) {
            val interval = timestamps[i] - timestamps[i - 1]
            if (interval > threshold) return true
        }
        
        return false
    }
}

/**
 * Timestamped value.
 */
data class TimestampedValue(
    val timestampNs: Long,
    val value: Double
)

/**
 * Resampled signals with unified timeline.
 */
data class ResampledSignals(
    val timestamps: List<Long>,
    val faceValues: List<Double>,
    val fingerValues: List<Double>
)
