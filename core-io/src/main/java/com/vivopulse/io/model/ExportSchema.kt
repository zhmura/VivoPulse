package com.vivopulse.io.model

import android.os.Build
import com.vivopulse.signal.HarmonicFeatureExtractor
import java.util.Locale

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
    val pttConfidence: Double,          // Percentage 0..100; not a calibrated probability
    val pttQuality: String,
    
    // Camera metrics
    val faceFps: Float,
    val fingerFps: Float,
    val driftMsPerSecond: Double,
    
    // Notes
    val notes: String = "",
    
    // New Harmonic Summaries (Session Level)
    val harmonicSummaryFace: HarmonicFeatureExtractor.HarmonicFeatures? = null,
    val harmonicSummaryFinger: HarmonicFeatureExtractor.HarmonicFeatures? = null,
    val measurementProvenance: String = "UNKNOWN",
    val timingVerified: Boolean = false,
    val pttValid: Boolean = false,
    val rejectionReasons: List<String> = emptyList(),
    val nativeFaceRateHz: Double? = null,
    val nativeFingerRateHz: Double? = null,
    val algorithmRevision: String = "measurement-validity-v1"
) {
    /** A stale numerical field must never override missing/failed measurement validity. */
    val hasReportablePtt: Boolean
        get() = pttValid && timingVerified && rejectionReasons.isEmpty() && pttMs.isFinite()

    companion object {
        /**
         * Current export schema version.
         */
        const val SCHEMA_VERSION = "1.2"
        
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
                pttMs = Double.NaN,
                pttCorrelation = Double.NaN,
                pttStabilityMs = Double.NaN,
                pttConfidence = Double.NaN,
                pttQuality = "UNKNOWN",
                faceFps = 0f,
                fingerFps = 0f,
                driftMsPerSecond = Double.NaN
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
    val phaseTag: String? = null,
    val rgb: Triple<Double, Double, Double>? = null,
    // Detailed metrics
    val motion: Double? = null,
    val saturation: Double? = null,
    val imu: Double? = null,
    // Actual monotonic source timestamp, or processing-grid timestamp when interpolated=true.
    // Null means unknown: never reconstruct an acquisition timestamp from the sample index.
    val timestampNs: Long? = null,
    val interpolated: Boolean? = null
) {
    /**
     * Convert to CSV row.
     */
    fun toCsvRow(): String {
        return listOf(
            ExportFormatting.number(timeMs, 3), ExportFormatting.number(rawValue, 6),
            ExportFormatting.number(filteredValue, 6), if (isPeak) "1" else "0",
            ExportFormatting.number(rgb?.first, 3), ExportFormatting.number(rgb?.second, 3),
            ExportFormatting.number(rgb?.third, 3), ExportFormatting.number(motion, 4),
            ExportFormatting.number(saturation, 4), ExportFormatting.number(imu, 4),
            ExportFormatting.text(phaseTag), timestampNs?.toString().orEmpty(),
            interpolated?.toString().orEmpty()
        ).joinToString(",")
    }
    
    companion object {
        /**
         * CSV header.
         */
        const val CSV_HEADER = "time_ms,raw_value,filtered_value,is_peak,r,g,b,motion_rms,saturation_fraction,imu_rms_g,phase_tag,timestamp_ns,interpolated"
    }
}

/** Locale-independent CSV formatting; missing/nonfinite observations stay missing. */
object ExportFormatting {
    fun number(value: Double?, decimals: Int = 6): String =
        if (value == null || !value.isFinite()) "" else String.format(Locale.US, "%.${decimals}f", value)

    fun text(value: String?): String {
        val text = value.orEmpty()
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"${text.replace("\"", "\"\"")}\"" else text
    }

    fun sourceCsv(samples: List<Pair<Long, Double>>): String = buildString {
        appendLine("timestamp_ns,raw_value,interpolated")
        samples.forEach { (timestamp, value) -> appendLine("$timestamp,${number(value, 9)},false") }
    }
}
