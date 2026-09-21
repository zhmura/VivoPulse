package com.vivopulse.feature.processing.timestamp

import kotlin.math.abs
import kotlin.math.roundToInt

/** Timestamp integrity and bounded resampling. These operations do not establish clock synchronization. */
object TimestampSync {
    fun setDebugEnabled(enabled: Boolean) = Unit

    fun validateMonotonicity(timestamps: List<Long>): ValidationResult {
        val violations = (1 until timestamps.size).filter { timestamps[it] <= timestamps[it - 1] }
        return ValidationResult(violations.isEmpty(), violations.size,
            if (violations.isEmpty()) "Monotonic timestamps" else "NONMONOTONIC_TIMESTAMPS", violations)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        return if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2 else sorted[sorted.size / 2]
    }

    fun estimateFrameInterval(timestamps: List<Long>): Double? {
        if (timestamps.size < 2 || !validateMonotonicity(timestamps).isValid) return null
        return median(timestamps.zipWithNext { a, b -> (b - a) / 1e6 })
    }

    fun analyzeSynchronization(stream1Timestamps: List<Long>, stream2Timestamps: List<Long>, windowSizeMs: Long = 5000): DriftResult {
        if (stream1Timestamps.size < 2 || stream2Timestamps.size < 2)
            return DriftResult(Double.NaN, false, message = "INSUFFICIENT_TIMESTAMPS", offsetValid = false)
        if (!validateMonotonicity(stream1Timestamps).isValid || !validateMonotonicity(stream2Timestamps).isValid)
            return DriftResult(Double.NaN, false, message = "NONMONOTONIC_TIMESTAMPS", offsetValid = false)
        val start = maxOf(stream1Timestamps.first(), stream2Timestamps.first())
        val end = minOf(stream1Timestamps.last(), stream2Timestamps.last())
        val dt1 = estimateFrameInterval(stream1Timestamps)!!
        val dt2 = estimateFrameInterval(stream2Timestamps)!!
        fun jitter(stream: List<Long>, dt: Double) = median(stream.zipWithNext { a, b -> abs((b - a) / 1e6 - dt) })
        fun drops(stream: List<Long>, dt: Double): Double {
            val missing = stream.zipWithNext { a, b -> maxOf(0, (((b - a) / 1e6) / dt).roundToInt() - 1) }.sum()
            return missing.toDouble() / (stream.size - 1 + missing)
        }
        // Frame phase/start offsets and unequal acquisition spans are not clock drift.
        // Independent clocks cannot be calibrated by pairing arbitrary nearest frames.
        return DriftResult(
            driftMsPerSecond = Double.NaN, isValid = end > start,
            stream1Rate = 1000 / dt1, stream2Rate = 1000 / dt2,
            stream1JitterMs = jitter(stream1Timestamps, dt1), stream2JitterMs = jitter(stream2Timestamps, dt2),
            stream1DropRate = drops(stream1Timestamps, dt1), stream2DropRate = drops(stream2Timestamps, dt2),
            offsetMs = Double.NaN, offsetValid = false, offsetPairs = 0, offsetStdMs = Double.NaN,
            overlapDurationMs = maxOf(0.0, (end - start) / 1e6), rateRatio = Double.NaN,
            message = if (end > start) "Timeline overlap; shared clock must be verified by acquisition metadata" else "NO_TEMPORAL_OVERLAP")
    }

    fun resampleToUnifiedTimeline(
        stream1Data: List<TimestampedValue>, stream2Data: List<TimestampedValue>,
        targetFrequencyHz: Double = 100.0, maxGapMs: Double = 100.0
    ): ResampledData {
        fun invalid(reason: String) = ResampledData(emptyList(), emptyList(), emptyList(), false, message = reason)
        if (stream1Data.isEmpty() || stream2Data.isEmpty()) return invalid("MISSING_CHANNEL")
        if (!targetFrequencyHz.isFinite() || targetFrequencyHz <= 0 || targetFrequencyHz > 10000 || !maxGapMs.isFinite() || maxGapMs <= 0)
            return invalid("INVALID_RESAMPLING_CONFIGURATION")
        if (!validateMonotonicity(stream1Data.map { it.timestampNs }).isValid || !validateMonotonicity(stream2Data.map { it.timestampNs }).isValid)
            return invalid("NONMONOTONIC_TIMESTAMPS")
        if ((stream1Data + stream2Data).any { !it.value.isFinite() }) return invalid("NONFINITE_SAMPLE")
        val start = maxOf(stream1Data.first().timestampNs, stream2Data.first().timestampNs)
        val end = minOf(stream1Data.last().timestampNs, stream2Data.last().timestampNs)
        if (end <= start) return invalid("NO_TEMPORAL_OVERLAP")
        val count = ((end - start) / 1e9 * targetFrequencyHz).toLong() + 1
        if (count !in 2..2_000_000) return invalid("INVALID_TIMELINE_SIZE")
        val times = List(count.toInt()) { start + (it * (1e9 / targetFrequencyHz)).toLong() }
        val first = interpolateStream(stream1Data, times, maxGapMs)
        val second = interpolateStream(stream2Data, times, maxGapMs)
        val mask = times.indices.map { first[it].isFinite() && second[it].isFinite() }
        val valid = mask.all { it }
        return ResampledData(times, first, second, valid, targetFrequencyHz,
            if (valid) "Resampled with bounded interpolation" else "INTERPOLATION_GAP", mask)
    }

    /** No extrapolation and no reconstruction across gaps longer than maxGapMs. */
    fun interpolateStream(data: List<TimestampedValue>, targetTimestamps: List<Long>, maxGapMs: Double = 100.0): List<Double> {
        if (data.isEmpty() || !validateMonotonicity(data.map { it.timestampNs }).isValid)
            return List(targetTimestamps.size) { Double.NaN }
        var index = 0
        return targetTimestamps.map { time ->
            while (index + 1 < data.size && data[index + 1].timestampNs <= time) index++
            val left = data[index]
            when {
                time < data.first().timestampNs || time > data.last().timestampNs -> Double.NaN
                time == left.timestampNs -> left.value
                index + 1 >= data.size -> Double.NaN
                else -> {
                    val right = data[index + 1]
                    val gap = right.timestampNs - left.timestampNs
                    if (gap <= 0 || gap / 1e6 > maxGapMs || !left.value.isFinite() || !right.value.isFinite()) Double.NaN
                    else left.value + (right.value - left.value) * ((time - left.timestampNs).toDouble() / gap)
                }
            }
        }
    }

    fun createSampleTuples(resampledData: ResampledData): List<SampleTuple> =
        if (!resampledData.isValid) emptyList() else resampledData.unifiedTimestamps.indices.map {
            SampleTuple(resampledData.unifiedTimestamps[it] / 1e6, resampledData.stream1Values[it], resampledData.stream2Values[it])
        }
}
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
    val offsetStdMs: Double = 0.0,     // Offset stability (stddev of paired diffs)
    val overlapDurationMs: Double = 0.0,
    val rateRatio: Double = Double.NaN, // Unknown: acquisition spans do not establish clock drift.
    val message: String
)

/**
 * Internal result from calculateRobustOffset.
 */
data class OffsetResult(
    val medianMs: Double?,
    val pairCount: Int,
    val rateRatio: Double,
    val stdMs: Double
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
    val message: String,
    val validityMask: List<Boolean> = emptyList()
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

