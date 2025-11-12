package com.vivopulse.io

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.vivopulse.io.model.SessionMetadata
import com.vivopulse.io.model.ExportExtras
import com.vivopulse.io.model.SignalDataPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
        extras: ExportExtras? = null
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
                zip.write(createSessionJson(metadata, extras).toByteArray())
                zip.closeEntry()
                
                // Add face_signal.csv
                zip.putNextEntry(ZipEntry("face_signal.csv"))
                zip.write(createSignalCsv(faceSignal).toByteArray())
                zip.closeEntry()
                
                // Add finger_signal.csv
                zip.putNextEntry(ZipEntry("finger_signal.csv"))
                zip.write(createSignalCsv(fingerSignal).toByteArray())
                zip.closeEntry()
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
    private fun createSessionJson(metadata: SessionMetadata, extras: ExportExtras? = null): String {
        val json = JSONObject().apply {
            put("schema_version", SessionMetadata.SCHEMA_VERSION)
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
                put("start_timestamp", metadata.startTimestamp)
                put("end_timestamp", metadata.endTimestamp)
                put("duration_seconds", metadata.durationSeconds)
            })
            
            // Signal info
            put("signal", JSONObject().apply {
                put("sample_rate_hz", metadata.sampleRateHz)
                put("sample_count", metadata.sampleCount)
                put("duration_seconds", metadata.durationSeconds)
            })
            
            // Quality metrics
            put("quality", JSONObject().apply {
                put("face_sqi", metadata.faceSQI)
                put("finger_sqi", metadata.fingerSQI)
                put("combined_sqi", metadata.combinedSQI)
            })
            
            // PTT metrics
            put("ptt", JSONObject().apply {
                put("value_ms", metadata.pttMs)
                put("correlation", metadata.pttCorrelation)
                put("stability_ms", metadata.pttStabilityMs)
                put("confidence_percent", metadata.pttConfidence)
                put("quality", metadata.pttQuality)
            })
            
            // Camera metrics
            put("camera", JSONObject().apply {
                put("face_fps", metadata.faceFps)
                put("finger_fps", metadata.fingerFps)
                put("drift_ms_per_second", metadata.driftMsPerSecond)
            })
            
            // Optional enrichment
            extras?.vascularWaveProfile?.let { map ->
                put("vascularWaveProfile", JSONObject().apply {
                    map.forEach { (k, v) -> put(k, v) }
                })
            }
            extras?.vascularTrendSummary?.let { map ->
                put("vascularTrendSummary", JSONObject().apply {
                    map.forEach { (k, v) -> put(k, v) }
                })
            }
            extras?.biomarkerPanel?.let { map ->
                put("biomarkerPanel", JSONObject().apply {
                    map.forEach { (k, v) -> put(k, v) }
                })
            }
            extras?.reactivityProtocol?.let { map ->
                put("reactivityProtocol", JSONObject().apply {
                    map.forEach { (k, v) -> put(k, v) }
                })
            }
            
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
}


