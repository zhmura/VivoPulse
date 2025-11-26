package com.vivopulse.io.model

import android.os.Build
import com.vivopulse.signal.HarmonicFeatureExtractor

/**
 * Session metadata for export.
 * 
 * Contains anonymized session information without PII.
 */
data class SessionMetadata(
    val appVersion: String,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val sessionId: String,              // Random UUID, not user-linked
    val startTimestamp: Long,           // Epoch milliseconds
    val endTimestamp: Long,
    val durationSeconds: Double,
    val sampleRateHz: Double,
    val sampleCount: Int,
    
    // Signal quality metrics
    val faceSQI: Double,
    val fingerSQI: Double,
    val combinedSQI: Double,
    
    // PTT metrics
    val pttMs: Double,
    val pttCorrelation: Double,
    val pttStabilityMs: Double,
    val pttConfidence: Double,
    val pttQuality: String,
    
    // Camera metrics
    val faceFps: Float,
    val fingerFps: Float,
    val driftMsPerSecond: Double,
    
    // Notes
    val notes: String = "",
    
    // New Harmonic Summaries (Session Level)
    val harmonicSummaryFace: HarmonicFeatureExtractor.HarmonicFeatures? = null,
    val harmonicSummaryFinger: HarmonicFeatureExtractor.HarmonicFeatures? = null
) {
    companion object {
        /**
         * Current export schema version.
         */
        const val SCHEMA_VERSION = "1.1" // Bumped for harmonics
        
        /**
         * Create metadata from device info.
         */
        fun createDefault(
            appVersion: String,
            sessionId: String,
            startTimestamp: Long,
            endTimestamp: Long,
            durationSeconds: Double = 0.0,
            sampleRateHz: Double = 100.0,
            sampleCount: Int = 0
        ): SessionMetadata {
            return SessionMetadata(
                appVersion = appVersion,
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                androidVersion = Build.VERSION.RELEASE,
                sessionId = sessionId,
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                durationSeconds = durationSeconds,
                sampleRateHz = sampleRateHz,
                sampleCount = sampleCount,
                faceSQI = 0.0,
                fingerSQI = 0.0,
                combinedSQI = 0.0,
                pttMs = 0.0,
                pttCorrelation = 0.0,
                pttStabilityMs = 0.0,
                pttConfidence = 0.0,
                pttQuality = "UNKNOWN",
                faceFps = 0f,
                fingerFps = 0f,
                driftMsPerSecond = 0.0
            )
        }
    }
}

/**
 * GoodSync segment for export.
 */
data class ExportSegment(
    val startTimeS: Double,
    val endTimeS: Double,
    val pttMs: Double,
    val correlation: Double,
    val sqiFace: Double,
    val sqiFinger: Double,
    // Diagnostics
    val pttMeanRaw: Double? = null,
    val pttMeanDenoised: Double? = null,
    val harmonicsFace: HarmonicFeatureExtractor.HarmonicFeatures? = null,
    val harmonicsFinger: HarmonicFeatureExtractor.HarmonicFeatures? = null
)

/**
 * Signal data point for CSV export.
 */
data class SignalDataPoint(
    val timeMs: Double,
    val rawValue: Double,
    val filteredValue: Double,
    val isPeak: Boolean = false,
    val phaseTag: String? = null  // Lab mode phase tag
) {
    /**
     * Convert to CSV row.
     */
    fun toCsvRow(): String {
        val baseRow = "${String.format("%.3f", timeMs)},${String.format("%.6f", rawValue)},${String.format("%.6f", filteredValue)},${if (isPeak) 1 else 0}"
        return if (phaseTag != null) {
            "$baseRow,$phaseTag"
        } else {
            "$baseRow,"
        }
    }
    
    companion object {
        /**
         * CSV header.
         */
        const val CSV_HEADER = "time_ms,raw_value,filtered_value,is_peak,phase_tag"
    }
}
