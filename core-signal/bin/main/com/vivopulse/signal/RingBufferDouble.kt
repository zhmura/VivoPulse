package com.vivopulse.signal

import kotlin.math.max

/**
 * Fixed-capacity ring buffer for continuous double streams with timestamps.
 *
 * Stores up to [capacity] samples and supports extracting chronological
 * windows from the most recent data without additional allocations.
 */
class RingBufferDouble(
    private val capacity: Int
 ) {
    private val values = DoubleArray(capacity)
    private val timestamps = LongArray(capacity)
    private var start = 0
    private var size = 0

    /**
     * Add a new sample to the buffer, dropping the oldest when full.
     */
    @Synchronized
    fun add(value: Double, timestampNs: Long) {
        if (capacity == 0) return
        val endIndex = (start + size) % capacity
        values[endIndex] = value
        timestamps[endIndex] = timestampNs

        if (size < capacity) {
            size++
        } else {
            start = (start + 1) % capacity
        }
    }

    /**
     * Snapshot the most recent [windowNs] samples (chronological order).
     *
     * @return [SignalWindow] or null when insufficient samples.
     */
    @Synchronized
    fun snapshot(windowNs: Long): SignalWindow? {
        if (size == 0) return null

        val newestIndex = (start + size - 1 + capacity) % capacity
        val newestTimestamp = timestamps[newestIndex]
        val cutoff = newestTimestamp - windowNs

        // Determine how many samples fall inside the window.
        var count = 0
        for (i in 0 until size) {
            val index = (newestIndex - i + capacity) % capacity
            if (timestamps[index] >= cutoff) {
                count++
            } else {
                break
            }
        }
        if (count == 0) return null

        val valuesOut = DoubleArray(count)
        val timestampsOut = LongArray(count)

        for (i in 0 until count) {
            val index = (start + size - count + i) % capacity
            valuesOut[i] = values[index]
            timestampsOut[i] = timestamps[index]
        }
        return SignalWindow(valuesOut, timestampsOut)
    }

    /**
     * Current number of stored samples.
     */
    @Synchronized
    fun size(): Int = size

    /**
     * Immutable window of samples with helper utilities.
     */
    data class SignalWindow(
        val values: DoubleArray,
        val timestampsNs: LongArray
    ) {
        fun durationSeconds(): Double {
            if (values.size < 2) return 0.0
            val first = timestampsNs.first()
            val last = timestampsNs.last()
            return max(0L, last - first) / 1_000_000_000.0
        }

        fun sampleRateHz(): Double {
            val duration = durationSeconds()
            if (duration <= 0.0) return 0.0
            return values.size / duration
        }

        fun normalized(decimation: Int = 80): List<Double> {
            if (values.isEmpty()) return emptyList()
            val minValue = values.minOrNull() ?: 0.0
            val maxValue = values.maxOrNull() ?: 1.0
            val range = max(1e-9, maxValue - minValue)

            val step = max(1, values.size / decimation)
            val normalized = ArrayList<Double>()
            for (i in values.indices step step) {
                normalized.add((values[i] - minValue) / range)
            }
            // Ensure at least 2 points for plotting
            if (normalized.size < 2 && values.isNotEmpty()) {
                normalized.add((values.last() - minValue) / range)
            }
            return normalized
        }
    }
}


