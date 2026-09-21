package com.vivopulse.io

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.vivopulse.io.model.SessionMetadata
import com.vivopulse.io.model.ExportExtras
import com.vivopulse.io.model.SignalDataPoint
import com.vivopulse.io.model.ExportFormatting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Data exporter for VivoPulse sessions.
 * 
 * Exports session data as encrypted ZIP containing:
 * - session.json (metadata)
 * - face_signal.csv (face signal data)
 * - finger_signal.csv (finger signal data)
 */
class DataExporter(private val context: Context) {
    
    /**
     * Export complete session to encrypted ZIP file.
     * 
     * @param metadata Session metadata
     * @param faceSignal Face signal data points
     * @param fingerSignal Finger signal data points
     * @return Exported file path, or null if failed
     */
    suspend fun exportSession(
        metadata: SessionMetadata,
        faceSignal: List<SignalDataPoint>,
        fingerSignal: List<SignalDataPoint>,
        extras: ExportExtras? = null,
        sourceFaceSamples: List<Pair<Long, Double>> = emptyList(),
        sourceFingerSamples: List<Pair<Long, Double>> = emptyList()
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Generate filename with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "VivoPulse-${timestamp}.zip.enc"
            
            // Get Documents directory (scoped storage)
            val documentsDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: Use scoped storage
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "VivoPulse")
            } else {
                // Android 9: Use legacy external storage
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "VivoPulse")
            }
            
            // Create directory if it doesn't exist
            if (!documentsDir.exists()) {
                documentsDir.mkdirs()
            }
            
            val outFile = File(documentsDir, filename)

            // Build ZIP bytes in-memory
            val zipBytes = java.io.ByteArrayOutputStream()
            ZipOutputStream(zipBytes).use { zip ->
                // Add session.json
                zip.putNextEntry(ZipEntry("session.json"))
                zip.write(createSessionJson(metadata, extras, sourceFaceSamples.isNotEmpty(), sourceFingerSamples.isNotEmpty()).toByteArray())
                zip.closeEntry()

                for ((name, samples) in listOf("source_face.csv" to sourceFaceSamples, "source_finger.csv" to sourceFingerSamples)) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(ExportFormatting.sourceCsv(samples).toByteArray())
                    zip.closeEntry()
                }
                
                // Add face_signal.csv
                zip.putNextEntry(ZipEntry("face_signal.csv"))
                zip.write(createSignalCsv(faceSignal).toByteArray())
                zip.closeEntry()
                
                // Add finger_signal.csv
                zip.putNextEntry(ZipEntry("finger_signal.csv"))
                zip.write(createSignalCsv(fingerSignal).toByteArray())
                zip.closeEntry()
                
                // Add combined session-{timestamp}.csv (time, face, finger)
                val combinedCsv = createCombinedCsv(faceSignal, fingerSignal)
                zip.putNextEntry(ZipEntry("session-${timestamp}.csv"))
                zip.write(combinedCsv.toByteArray())
                zip.closeEntry()
            }

            // Also save combined CSV as a separate file for easy access
            try {
                val csvFile = File(documentsDir, "session-${timestamp}.csv")
                csvFile.writeText(createCombinedCsv(faceSignal, fingerSignal))
            } catch (e: Exception) {
                e.printStackTrace() // Non-fatal
            }

            // Encrypt ZIP bytes to file at rest
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encryptedFile = EncryptedFile.Builder(
                context,
                outFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            encryptedFile.openFileOutput().use { output ->
                output.write(zipBytes.toByteArray())
            }

            outFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Create session JSON content.
     */
    internal fun createSessionJson(metadata: SessionMetadata, extras: ExportExtras? = null,
        sourceFaceAvailable: Boolean = false, sourceFingerAvailable: Boolean = false): String {
        val json = JSONObject().apply {
            put("schema_version", SessionMetadata.SCHEMA_VERSION)
            put("export_type", "research_signals")
            ExportJson.addAudit(this, metadata, sourceFaceAvailable, sourceFingerAvailable)
            put("exported_at", System.currentTimeMillis())
            
            // Device info (anonymized)
            put("device", JSONObject().apply {
                put("manufacturer", metadata.deviceManufacturer)
                put("model", metadata.deviceModel)
                put("android_version", metadata.androidVersion)
            })
            
            // App info
            put("app", JSONObject().apply {
                put("version", metadata.appVersion)
                put("name", "VivoPulse")
            })
            
            // Session info
            put("session", JSONObject().apply {
                put("id", metadata.sessionId)
                put("start_timestamp", metadata.startTimestamp.takeIf { it > 0L } ?: JSONObject.NULL)
                put("end_timestamp", metadata.endTimestamp.takeIf { it > 0L } ?: JSONObject.NULL)
                put("duration_seconds", ExportJson.value(metadata.durationSeconds))
            })
            
            // Signal info
            put("signal", JSONObject().apply {
                put("sample_rate_hz", ExportJson.value(metadata.sampleRateHz))
                put("sample_count", metadata.sampleCount)
                put("duration_seconds", ExportJson.value(metadata.durationSeconds))
            })
            
            // Quality metrics
            put("quality", JSONObject().apply {
                put("face_sqi", ExportJson.value(metadata.faceSQI))
                put("finger_sqi", ExportJson.value(metadata.fingerSQI))
                put("combined_sqi", ExportJson.value(metadata.combinedSQI))
            })
            
            // PTT metrics
            put("ptt", JSONObject().apply {
                put("valid", metadata.hasReportablePtt)
                put("value_ms", ExportJson.pttValue(metadata, metadata.pttMs))
                put("correlation", ExportJson.pttValue(metadata, metadata.pttCorrelation))
                put("stability_ms", ExportJson.pttValue(metadata, metadata.pttStabilityMs))
                put("confidence_percent", ExportJson.pttValue(metadata, metadata.pttConfidence.takeIf { it in 0.0..100.0 }))
                put("quality", if (metadata.hasReportablePtt) metadata.pttQuality else "UNAVAILABLE")
            })
            
            // Camera metrics
            put("camera", JSONObject().apply {
                put("face_fps", ExportJson.value(metadata.faceFps))
                put("finger_fps", ExportJson.value(metadata.fingerFps))
                put("native_face_rate_hz", ExportJson.value(metadata.nativeFaceRateHz))
                put("native_finger_rate_hz", ExportJson.value(metadata.nativeFingerRateHz))
                put("drift_ms_per_second", ExportJson.value(metadata.driftMsPerSecond))
            })
            
            ExportJson.addExtras(this, metadata, extras)
            
            // Notes
            if (metadata.notes.isNotEmpty()) {
                put("notes", metadata.notes)
            }
        }
        
        return json.toString(2) // Pretty print with 2-space indent
    }
    
    /**
     * Create signal CSV content.
     */
    private fun createSignalCsv(signalData: List<SignalDataPoint>): String {
        val csv = StringBuilder()
        
        // Header
        csv.appendLine(SignalDataPoint.CSV_HEADER)
        
        // Data rows
        signalData.forEach { point ->
            csv.appendLine(point.toCsvRow())
        }
        
        return csv.toString()
    }
    
    /**
     * Create combined CSV (timestamp, face, finger).
     */
    private fun createCombinedCsv(face: List<SignalDataPoint>, finger: List<SignalDataPoint>): String {
        val csv = StringBuilder()
        csv.appendLine("time_ms,face_signal,finger_signal,finger_time_ms,face_timestamp_ns,finger_timestamp_ns,face_interpolated,finger_interpolated")
        
        val size = maxOf(face.size, finger.size)
        for (i in 0 until size) {
            val f = face.getOrNull(i)
            val p = finger.getOrNull(i)
            csv.appendLine(listOf(ExportFormatting.number(f?.timeMs, 3),
                ExportFormatting.number(f?.filteredValue), ExportFormatting.number(p?.filteredValue),
                ExportFormatting.number(p?.timeMs, 3), f?.timestampNs?.toString().orEmpty(),
                p?.timestampNs?.toString().orEmpty(), f?.interpolated?.toString().orEmpty(),
                p?.interpolated?.toString().orEmpty()).joinToString(","))
        }
        return csv.toString()
    }
}

/** JSON numbers must be finite; JSON null is explicit rather than an omitted key. */
internal object ExportJson {
    fun value(input: Any?): Any = when (input) {
        null -> JSONObject.NULL
        is Double -> if (input.isFinite()) input else JSONObject.NULL
        is Float -> if (input.isFinite()) input else JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { input.forEach { (key, item) -> put(key.toString(), value(item)) } }
        is Iterable<*> -> JSONArray().apply { input.forEach { put(value(it)) } }
        is Array<*> -> JSONArray().apply { input.forEach { put(value(it)) } }
        is DoubleArray -> JSONArray().apply { input.forEach { put(value(it)) } }
        else -> input
    }

    fun pttValue(metadata: SessionMetadata, number: Double?): Any =
        if (metadata.hasReportablePtt) value(number) else JSONObject.NULL

    fun addExtras(json: JSONObject, metadata: SessionMetadata, extras: ExportExtras?) {
        if (extras == null) return
        if (!metadata.hasReportablePtt || metadata.measurementProvenance != "REAL") {
            json.put("derived_analysis_omitted", true)
            return
        }
        json.put("experimental_analysis", JSONObject().apply {
            put("clinically_validated", false)
            extras.vascularWaveProfile?.let { put("vascular_wave_profile", value(it)) }
            extras.vascularTrendSummary?.let { put("vascular_trend_summary", value(it)) }
            extras.biomarkerPanel?.let { put("biomarker_panel", value(it)) }
            extras.reactivityProtocol?.let { put("reactivity_protocol", value(it)) }
        })
    }

    fun addAudit(json: JSONObject, metadata: SessionMetadata, faceSource: Boolean, fingerSource: Boolean) {
        json.put("measurement_provenance", metadata.measurementProvenance)
        json.put("timing_verified", metadata.timingVerified)
        json.put("ptt_valid", metadata.hasReportablePtt)
        json.put("rejection_reasons", JSONArray(metadata.rejectionReasons))
        json.put("algorithm_revision", metadata.algorithmRevision)
        json.put("measurement_type", "experimental_inter_site_optical_delay")
        json.put("source_samples", JSONObject().apply {
            put("face_available", faceSource)
            put("finger_available", fingerSource)
            put("timestamp_unit", "monotonic_nanoseconds")
        })
        json.put("export_limitations", JSONArray(listOf(
            "Processing-grid timestamps are marked interpolated; they are not native camera observations.",
            "source_face.csv and source_finger.csv preserve supplied native samples; header-only means unavailable.",
            "Raw values in processed signal CSVs may have been resampled; use source CSVs for acquisition audit.",
            "No cross-correlation curve is exported because the measured curve is not supplied.",
            "Confidence is an engineering score, not a calibrated clinical probability."
        )))
    }
}


