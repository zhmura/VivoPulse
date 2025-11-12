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
     * Computes relative drift between two streams over a time window.
     * 
     * Drift is measured as the change in offset between streams per second.
     * 
     * @param stream1Timestamps First stream timestamps (nanoseconds)
     * @param stream2Timestamps Second stream timestamps (nanoseconds)
     * @param windowSizeMs Time window for drift calculation (default 5000ms)
     * @return DriftResult with drift rate and statistics
     */
    fun computeDrift(
        stream1Timestamps: List<Long>,
        stream2Timestamps: List<Long>,
        windowSizeMs: Long = 5000
    ): DriftResult {
        if (stream1Timestamps.size < 2 || stream2Timestamps.size < 2) {
            return DriftResult(
                driftMsPerSecond = 0.0,
                isValid = false,
                message = "Insufficient timestamps for drift calculation"
            )
        }
        
        // Convert window to nanoseconds
        val windowNs = windowSizeMs * 1_000_000L
        
        // Find overlapping time range
        val minStart = maxOf(stream1Timestamps.first(), stream2Timestamps.first())
        val maxEnd = minOf(stream1Timestamps.last(), stream2Timestamps.last())
        
        if (maxEnd - minStart < windowNs) {
            return DriftResult(
                driftMsPerSecond = 0.0,
                isValid = false,
                message = "Insufficient overlap between streams"
            )
        }
        
        // Calculate drift by comparing frame counts over time windows
        val windowStart = minStart
        val windowEnd = minStart + windowNs
        
        val stream1Count = stream1Timestamps.count { it in windowStart..windowEnd }
        val stream2Count = stream2Timestamps.count { it in windowStart..windowEnd }
        
        // Drift is the difference in frame rate between streams
        val stream1Rate = stream1Count.toDouble() / (windowSizeMs / 1000.0)
        val stream2Rate = stream2Count.toDouble() / (windowSizeMs / 1000.0)
        
        // Calculate relative drift (frames per second difference)
        val frameDrift = abs(stream1Rate - stream2Rate)
        
        // Estimate interval to convert frame drift to time drift
        val interval1 = estimateFrameInterval(stream1Timestamps) ?: 33.33
        val interval2 = estimateFrameInterval(stream2Timestamps) ?: 33.33
        val avgInterval = (interval1 + interval2) / 2.0
        
        // Drift in ms/s = frame rate difference * interval
        val driftMsPerSecond = frameDrift * avgInterval
        
        logD("Drift calculation: stream1=$stream1Rate fps, stream2=$stream2Rate fps, drift=${"%.2f".format(driftMsPerSecond)} ms/s")
        
        return DriftResult(
            driftMsPerSecond = driftMsPerSecond,
            isValid = true,
            stream1Rate = stream1Rate,
            stream2Rate = stream2Rate,
            message = "Drift: ${"%.2f".format(driftMsPerSecond)} ms/s"
        )
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
    private fun interpolateStream(
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

