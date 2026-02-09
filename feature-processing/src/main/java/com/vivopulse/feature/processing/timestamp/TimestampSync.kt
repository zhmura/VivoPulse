package com.vivopulse.feature.processing.timestamp

import kotlin.math.abs

/**
 * Timestamp synchronization and drift detection utilities.
 * 
 * Ensures timestamp integrity between dual camera streams and provides
 * drift detection, normalization, and resampling to a unified timeline.
 */
object TimestampSync {
    private const val TAG = "TimestampSync"
    private var debugEnabled = false
    
    /**
     * Enable debug logging (for testing).
     */
    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
    }
    
    private fun logD(message: String) {
        if (debugEnabled) {
            println("$TAG: $message")
        }
    }
    
    /**
     * Validates monotonicity of timestamps in a stream.
     * 
     * @param timestamps List of timestamps in nanoseconds
     * @return ValidationResult with status and details
     */
    fun validateMonotonicity(timestamps: List<Long>): ValidationResult {
        if (timestamps.isEmpty()) {
            return ValidationResult(
                isValid = true,
                violations = 0,
                message = "Empty timestamp list"
            )
        }
        
        var violations = 0
        val violationIndices = mutableListOf<Int>()
        
        for (i in 1 until timestamps.size) {
            if (timestamps[i] <= timestamps[i - 1]) {
                violations++
                violationIndices.add(i)
            }
        }
        
        return ValidationResult(
            isValid = violations == 0,
            violations = violations,
            message = if (violations == 0) {
                "All timestamps monotonically increasing"
            } else {
                "Found $violations non-monotonic timestamps at indices: ${violationIndices.take(5)}"
            },
            violationIndices = violationIndices
        )
    }
    
    /**
     * Estimates the median frame interval for a stream.
     * 
     * @param timestamps List of timestamps in nanoseconds
     * @return Median interval in milliseconds, or null if insufficient data
     */
    fun estimateFrameInterval(timestamps: List<Long>): Double? {
        if (timestamps.size < 2) return null
        
        val intervals = mutableListOf<Long>()
        for (i in 1 until timestamps.size) {
            val interval = timestamps[i] - timestamps[i - 1]
            if (interval > 0) { // Only valid intervals
                intervals.add(interval)
            }
        }
        
        if (intervals.isEmpty()) return null
        
        intervals.sort()
        val median = if (intervals.size % 2 == 0) {
            (intervals[intervals.size / 2 - 1] + intervals[intervals.size / 2]) / 2.0
        } else {
            intervals[intervals.size / 2].toDouble()
        }
        
        // Convert nanoseconds to milliseconds
        return median / 1_000_000.0
    }
    
    /**
     * Analyzes synchronization between two streams.
     * 
     * Checks for:
     * 1. Temporal overlap
     * 2. Frame rate consistency
     * 3. Start time offset
     * 
     * NOTE: Does NOT calculate "clock drift" based on frame rate differences, as
     * SENSOR_TIMESTAMP (BOOTTIME) is shared ground truth. Rate differences are expected.
     * 
     * @param stream1Timestamps First stream timestamps (nanoseconds)
     * @param stream2Timestamps Second stream timestamps (nanoseconds)
     * @param windowSizeMs Time window for analysis (default 5000ms)
     * @return DriftResult with sync statistics
     */
    fun analyzeSynchronization(
        stream1Timestamps: List<Long>,
        stream2Timestamps: List<Long>,
        windowSizeMs: Long = 5000
    ): DriftResult {
        if (stream1Timestamps.size < 2 || stream2Timestamps.size < 2) {
            return DriftResult(
                driftMsPerSecond = 0.0,
                isValid = false,
                message = "Insufficient timestamps for sync analysis"
            )
        }
        
        // Convert window to nanoseconds
        val windowNs = windowSizeMs * 1_000_000L
        
        // Find overlapping time range
        val minStart = maxOf(stream1Timestamps.first(), stream2Timestamps.first())
        val maxEnd = minOf(stream1Timestamps.last(), stream2Timestamps.last())
        
        if (maxEnd - minStart < windowNs) {
             // Just warn if overlap is small but strictly positive
             if (maxEnd > minStart) {
                 logD("Short overlap: ${(maxEnd - minStart)/1e6} ms")
             } else {
                return DriftResult(
                    driftMsPerSecond = 0.0,
                    isValid = false,
                    message = "Insufficient overlap between streams"
                )
             }
        }
        
        // Analyze frame rates using Median Interval (robust to drops)
        val interval1Ms = estimateFrameInterval(stream1Timestamps)
        val interval2Ms = estimateFrameInterval(stream2Timestamps)
        
        val rate1 = if (interval1Ms != null && interval1Ms > 0) 1000.0 / interval1Ms else 0.0
        val rate2 = if (interval2Ms != null && interval2Ms > 0) 1000.0 / interval2Ms else 0.0
        
        // Calculate Jitter (MAD of intervals)
        val jitter1 = calculateJitterMs(stream1Timestamps, interval1Ms)
        val jitter2 = calculateJitterMs(stream2Timestamps, interval2Ms)
        
        // Calculate Drop Rate (fraction of intervals > 1.5 * median)
        val drops1 = calculateDropRate(stream1Timestamps, interval1Ms)
        val drops2 = calculateDropRate(stream2Timestamps, interval2Ms)
        
        // Calculate Robust Offset using median of first N pairs
        val (robustOffsetMs, offsetPairs) = calculateRobustOffset(stream1Timestamps, stream2Timestamps)
        val offsetMs = robustOffsetMs ?: 0.0
        val offsetValid = robustOffsetMs != null
        
        // Start Overlap Duration
        val overlapDurationMs = (maxEnd - minStart) / 1_000_000.0
        
        logD("Sync Analysis: Rates ${"%.1f".format(rate1)}/${"%.1f".format(rate2)} fps, " +
             "Offset ${"%.1f".format(offsetMs)} ms (valid=$offsetValid, n=$offsetPairs), " +
             "Jitter ${"%.1f".format(jitter1)}/${"%.1f".format(jitter2)} ms, " +
             "Drops ${"%.1f".format(drops1 * 100)}%/${"%.1f".format(drops2 * 100)}%")
        
        return DriftResult(
            driftMsPerSecond = 0.0, // Assumed 0 for shared clock (SENSOR_TIMESTAMP)
            isValid = overlapDurationMs > 0 && offsetValid,
            stream1Rate = rate1,
            stream2Rate = rate2,
            stream1JitterMs = jitter1,
            stream2JitterMs = jitter2,
            stream1DropRate = drops1,
            stream2DropRate = drops2,
            offsetMs = offsetMs,
            offsetValid = offsetValid,
            offsetPairs = offsetPairs,
            overlapDurationMs = overlapDurationMs,
            message = if (offsetValid) {
                "Sync: Offset ${"%.0f".format(offsetMs)}ms (n=$offsetPairs), Rates ${"%.1f".format(rate1)}/${"%.1f".format(rate2)} fps"
            } else {
                "Sync: FAILED (Invalid Offset, n=$offsetPairs), Rates ${"%.1f".format(rate1)}/${"%.1f".format(rate2)} fps"
            }
        )
    }
    
    private fun calculateJitterMs(timestamps: List<Long>, medianIntervalMs: Double?): Double {
        if (timestamps.size < 3 || medianIntervalMs == null) return 0.0
        
        val intervalsMs = mutableListOf<Double>()
        for (i in 1 until timestamps.size) {
            val dt = (timestamps[i] - timestamps[i - 1]) / 1_000_000.0
            intervalsMs.add(dt)
        }
        
        // Calculate deviations from median
        val deviations = intervalsMs.map { abs(it - medianIntervalMs) }.sorted()
        
        // Return Median Absolute Deviation
        val mid = deviations.size / 2
        return if (deviations.size % 2 == 0) {
            (deviations[mid - 1] + deviations[mid]) / 2.0
        } else {
            deviations[mid]
        }
    }

    /**
     * Calculates a robust offset between two streams using the median difference
     * of temporally closest pairs, with monotonic pairing constraint.
     *
     * Hardening:
     * 1. Warmup skip: ignores first 0.5s of each stream (cadence/latency stabilization).
     * 2. Monotonic constraint: cursor j only advances forward, preventing re-pairing.
     * 3. Adaptive tolerance: max(50ms, 1.5 * max(medianDt1, medianDt2)) — scales with cadence.
     * 4. Median aggregation: robust to remaining outliers.
     *
     * @return Pair of (offset in ms or null, number of valid pairs used)
     */
    private fun calculateRobustOffset(stream1: List<Long>, stream2: List<Long>, n: Int = 30): Pair<Double?, Int> {
        if (stream1.isEmpty() || stream2.isEmpty()) return null to 0

        // 1. Skip warmup frames (first 0.5s — cadence/latency stabilization)
        val warmupNs = 500_000_000L
        val s1Start = stream1.first()
        val s2Start = stream2.first()
        val s1 = stream1.filter { it >= s1Start + warmupNs }
        val s2 = stream2.filter { it >= s2Start + warmupNs }

        // Fall back to original streams if warmup skip removed everything
        val effectiveS1 = if (s1.size >= 5) s1 else stream1
        val effectiveS2 = if (s2.size >= 5) s2 else stream2

        // 3. Adaptive tolerance: max(50ms, 1.5 * max(medianDt1, medianDt2))
        //    At 30fps (33ms dt) → 50ms. At 15fps (66ms dt) → 99ms.
        val medianDt1Ns = medianIntervalNs(effectiveS1)
        val medianDt2Ns = medianIntervalNs(effectiveS2)
        val maxMedianDtNs = maxOf(medianDt1Ns, medianDt2Ns)
        val adaptiveToleranceNs = maxOf(50_000_000L, (1.5 * maxMedianDtNs).toLong())

        val diffs = mutableListOf<Double>()
        val maxIter = minOf(n, effectiveS1.size)

        // 2. Monotonic cursor: j only advances forward
        var j = 0
        for (i in 0 until maxIter) {
            if (j >= effectiveS2.size) break
            val t1 = effectiveS1[i]

            // Advance j to the closest point to t1 (monotonically)
            while (j + 1 < effectiveS2.size &&
                   abs(effectiveS2[j + 1] - t1) < abs(effectiveS2[j] - t1)) {
                j++
            }

            // Adaptive tolerance check
            val diff = effectiveS2[j] - t1
            if (abs(diff) <= adaptiveToleranceNs) {
                diffs.add(diff / 1_000_000.0) // ns → ms
            }

            // Advance j past this match to enforce one-to-one pairing
            j++
        }

        if (diffs.isEmpty()) return null to 0

        diffs.sort()
        val mid = diffs.size / 2
        val median = if (diffs.size % 2 == 0) {
            (diffs[mid - 1] + diffs[mid]) / 2.0
        } else {
            diffs[mid]
        }
        return median to diffs.size
    }

    /** Compute median inter-frame interval in nanoseconds. */
    private fun medianIntervalNs(timestamps: List<Long>): Long {
        if (timestamps.size < 2) return 33_333_333L // default ~30fps
        val intervals = LongArray(timestamps.size - 1) { i -> timestamps[i + 1] - timestamps[i] }
        intervals.sort()
        val mid = intervals.size / 2
        return if (intervals.size % 2 == 0) {
            (intervals[mid - 1] + intervals[mid]) / 2
        } else {
            intervals[mid]
        }
    }
    
    private fun calculateDropRate(timestamps: List<Long>, medianIntervalMs: Double?): Double {
        if (timestamps.size < 2 || medianIntervalMs == null || medianIntervalMs <= 0.0) return 0.0
        
        val thresholdMs = medianIntervalMs * 1.5
        var dropCount = 0
        var totalIntervals = 0
        
        for (i in 1 until timestamps.size) {
            val dt = (timestamps[i] - timestamps[i - 1]) / 1_000_000.0
            if (dt > thresholdMs) {
                dropCount++
            }
            totalIntervals++
        }
        
        return if (totalIntervals > 0) dropCount.toDouble() / totalIntervals else 0.0
    }
    
    /**
     * Resamples two streams to a unified timeline at specified frequency.
     * 
     * Uses linear interpolation to align streams to common sample points.
     * 
     * @param stream1Data List of (timestamp, value) pairs for stream 1
     * @param stream2Data List of (timestamp, value) pairs for stream 2
     * @param targetFrequencyHz Target sampling frequency (default 100 Hz)
     * @return ResampledData with unified timeline and interpolated values
     */
    fun resampleToUnifiedTimeline(
        stream1Data: List<TimestampedValue>,
        stream2Data: List<TimestampedValue>,
        targetFrequencyHz: Double = 100.0
    ): ResampledData {
        if (stream1Data.isEmpty() || stream2Data.isEmpty()) {
            return ResampledData(
                unifiedTimestamps = emptyList(),
                stream1Values = emptyList(),
                stream2Values = emptyList(),
                isValid = false,
                message = "Empty input streams"
            )
        }
        
        // Find overlapping time range
        val minStart = maxOf(stream1Data.first().timestampNs, stream2Data.first().timestampNs)
        val maxEnd = minOf(stream1Data.last().timestampNs, stream2Data.last().timestampNs)
        
        if (maxEnd <= minStart) {
            return ResampledData(
                unifiedTimestamps = emptyList(),
                stream1Values = emptyList(),
                stream2Values = emptyList(),
                isValid = false,
                message = "No temporal overlap between streams"
            )
        }
        
        // Calculate sample interval in nanoseconds
        val sampleIntervalNs = (1_000_000_000.0 / targetFrequencyHz).toLong()
        
        // Generate unified timeline
        val unifiedTimestamps = mutableListOf<Long>()
        var currentTime = minStart
        while (currentTime <= maxEnd) {
            unifiedTimestamps.add(currentTime)
            currentTime += sampleIntervalNs
        }
        
        // Interpolate stream 1
        val stream1Interpolated = interpolateStream(stream1Data, unifiedTimestamps)
        
        // Interpolate stream 2
        val stream2Interpolated = interpolateStream(stream2Data, unifiedTimestamps)
        
        logD("Resampled to ${unifiedTimestamps.size} samples at ${targetFrequencyHz} Hz")
        
        return ResampledData(
            unifiedTimestamps = unifiedTimestamps,
            stream1Values = stream1Interpolated,
            stream2Values = stream2Interpolated,
            isValid = true,
            sampleRate = targetFrequencyHz,
            message = "Successfully resampled to ${unifiedTimestamps.size} samples"
        )
    }
    
    /**
     * Interpolates stream values at specified timestamps using linear interpolation.
     */
    /**
     * Interpolates stream values at specified timestamps using linear interpolation.
     */
    fun interpolateStream(
        data: List<TimestampedValue>,
        targetTimestamps: List<Long>
    ): List<Double> {
        val result = mutableListOf<Double>()
        var dataIndex = 0
        
        for (targetTime in targetTimestamps) {
            // Find surrounding data points
            while (dataIndex < data.size - 1 && data[dataIndex + 1].timestampNs < targetTime) {
                dataIndex++
            }
            
            if (dataIndex >= data.size - 1) {
                // Use last value if beyond range
                result.add(data.last().value)
            } else if (targetTime <= data[dataIndex].timestampNs) {
                // Use first value if before range
                result.add(data[dataIndex].value)
            } else {
                // Linear interpolation
                val t0 = data[dataIndex].timestampNs
                val t1 = data[dataIndex + 1].timestampNs
                val v0 = data[dataIndex].value
                val v1 = data[dataIndex + 1].value
                
                val fraction = (targetTime - t0).toDouble() / (t1 - t0).toDouble()
                val interpolated = v0 + fraction * (v1 - v0)
                
                result.add(interpolated)
            }
        }
        
        return result
    }
    
    /**
     * Creates a unified sample tuple from resampled data.
     * 
     * @param resampledData Resampled stream data
     * @return List of SampleTuple with (time, stream1, stream2) values
     */
    fun createSampleTuples(resampledData: ResampledData): List<SampleTuple> {
        if (!resampledData.isValid) return emptyList()
        
        return resampledData.unifiedTimestamps.indices.map { i ->
            SampleTuple(
                timeMillis = resampledData.unifiedTimestamps[i] / 1_000_000.0,
                stream1Value = resampledData.stream1Values[i],
                stream2Value = resampledData.stream2Values[i]
            )
        }
    }
}

/**
 * Result of timestamp validation.
 */
data class ValidationResult(
    val isValid: Boolean,
    val violations: Int = 0,
    val message: String,
    val violationIndices: List<Int> = emptyList()
)

/**
 * Result of drift calculation.
 */
data class DriftResult(
    val driftMsPerSecond: Double,
    val isValid: Boolean,
    val stream1Rate: Double = 0.0,
    val stream2Rate: Double = 0.0,
    val stream1JitterMs: Double = 0.0,
    val stream2JitterMs: Double = 0.0,
    val stream1DropRate: Double = 0.0,
    val stream2DropRate: Double = 0.0,
    val offsetMs: Double = 0.0,
    val offsetValid: Boolean = true,
    val offsetPairs: Int = 0,
    val overlapDurationMs: Double = 0.0,
    val message: String
)

/**
 * Timestamped value for resampling.
 */
data class TimestampedValue(
    val timestampNs: Long,
    val value: Double
)

/**
 * Result of resampling to unified timeline.
 */
data class ResampledData(
    val unifiedTimestamps: List<Long>,
    val stream1Values: List<Double>,
    val stream2Values: List<Double>,
    val isValid: Boolean,
    val sampleRate: Double = 0.0,
    val message: String
)

/**
 * Sample tuple with unified timeline.
 * 
 * Represents a single synchronized sample point across both streams.
 * For now, values are stubs - will be filled with luma values later.
 */
data class SampleTuple(
    val timeMillis: Double,
    val stream1Value: Double,  // Will be faceLuma
    val stream2Value: Double   // Will be fingerLuma
)

