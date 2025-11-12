package com.vivopulse.signal

/**
 * Core signal processing module for VivoPulse.
 * Pure Kotlin implementation for DSP operations on PPG signals.
 */
interface SignalProcessor {
    /**
     * Process raw PPG signal data.
     * @param rawData Raw signal values
     * @return Processed signal data
     */
    suspend fun processSignal(rawData: FloatArray): ProcessedSignal
}

/**
 * Represents a processed PPG signal with extracted features.
 */
data class ProcessedSignal(
    val heartRate: Float,
    val signalQuality: Float,
    val timestamp: Long,
    val processedData: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProcessedSignal

        if (heartRate != other.heartRate) return false
        if (signalQuality != other.signalQuality) return false
        if (timestamp != other.timestamp) return false
        if (!processedData.contentEquals(other.processedData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = heartRate.hashCode()
        result = 31 * result + signalQuality.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + processedData.contentHashCode()
        return result
    }
}


