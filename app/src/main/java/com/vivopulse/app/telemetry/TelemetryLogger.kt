package com.vivopulse.app.telemetry

import android.content.Context
import android.os.Build
import android.util.Log
import com.vivopulse.feature.capture.device.DeviceCategory
import com.vivopulse.feature.capture.device.DeviceWhitelist
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Telemetry logger for accuracy monitoring.
 * 
 * Logs session metrics to local file for offline analysis.
 * No network permissions - all data stays on device.
 */
class TelemetryLogger(private val context: Context) {
    
    private val tag = "TelemetryLogger"
    
    /**
     * Log session telemetry.
     */
    fun logSession(event: SessionEvent) {
        try {
            val logDir = File(context.getExternalFilesDir(null), "telemetry")
            logDir.mkdirs()
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val logFile = File(logDir, "session_$timestamp.json")
            
            val json = buildSessionJson(event)
            logFile.writeText(json.toString(2))
            
            Log.d(tag, "Session logged: ${logFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to log session", e)
        }
    }
    
    /**
     * Build session JSON.
     */
    private fun buildSessionJson(event: SessionEvent): JSONObject {
        return JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("app_version", "1.0.0")
            
            // Device info
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("android_version", Build.VERSION.SDK_INT)
                put("category", DeviceWhitelist.getDeviceCategory().name)
                put("is_whitelisted", DeviceWhitelist.isDeviceWhitelisted())
            })
            
            // Session metrics
            put("session", JSONObject().apply {
                put("duration_s", event.durationSeconds)
                put("face_fps", event.faceFps)
                put("finger_fps", event.fingerFps)
                put("drift_ms_per_s", event.driftMsPerSecond)
            })
            
            // Quality metrics
            put("quality", JSONObject().apply {
                put("face_sqi", event.faceSQI)
                put("finger_sqi", event.fingerSQI)
                put("combined_sqi", event.combinedSQI)
                put("is_usable", event.combinedSQI >= 70.0)
            })
            
            // PTT metrics
            put("ptt", JSONObject().apply {
                put("lag_ms", event.pttMs)
                put("correlation", event.correlation)
                put("stability_sd_ms", event.pttStabilitySdMs)
            })
            
            // Issues
            if (event.issues.isNotEmpty()) {
                put("issues", event.issues)
            }
            
            // Thermal/torch
            put("thermal_state", event.thermalState)
            put("torch_duration_ms", event.torchDurationMs)
        }
    }
    
    /**
     * Get telemetry summary for last N sessions.
     */
    fun getSummary(lastN: Int = 10): SessionSummary {
        try {
            val logDir = File(context.getExternalFilesDir(null), "telemetry")
            if (!logDir.exists()) return SessionSummary.empty()
            
            val files = logDir.listFiles()?.filter { it.name.startsWith("session_") }?.sortedByDescending { it.lastModified() }?.take(lastN) ?: emptyList()
            
            if (files.isEmpty()) return SessionSummary.empty()
            
            val events = files.mapNotNull { file ->
                try {
                    val json = JSONObject(file.readText())
                    parseSessionEvent(json)
                } catch (e: Exception) {
                    null
                }
            }
            
            return computeSummary(events)
        } catch (e: Exception) {
            Log.e(tag, "Failed to compute summary", e)
            return SessionSummary.empty()
        }
    }
    
    /**
     * Parse session event from JSON.
     */
    private fun parseSessionEvent(json: JSONObject): SessionEvent {
        val quality = json.getJSONObject("quality")
        val ptt = json.getJSONObject("ptt")
        val session = json.getJSONObject("session")
        
        return SessionEvent(
            durationSeconds = session.getDouble("duration_s"),
            faceFps = session.getDouble("face_fps").toFloat(),
            fingerFps = session.getDouble("finger_fps").toFloat(),
            driftMsPerSecond = session.getDouble("drift_ms_per_s"),
            faceSQI = quality.getDouble("face_sqi"),
            fingerSQI = quality.getDouble("finger_sqi"),
            combinedSQI = quality.getDouble("combined_sqi"),
            pttMs = ptt.getDouble("lag_ms"),
            correlation = ptt.getDouble("correlation"),
            pttStabilitySdMs = ptt.getDouble("stability_sd_ms"),
            issues = emptyList(),
            thermalState = json.optString("thermal_state", "NORMAL"),
            torchDurationMs = json.optLong("torch_duration_ms", 0L)
        )
    }
    
    /**
     * Compute summary statistics.
     */
    private fun computeSummary(events: List<SessionEvent>): SessionSummary {
        if (events.isEmpty()) return SessionSummary.empty()
        
        val usableSessions = events.count { it.combinedSQI >= 70.0 && it.correlation >= 0.70 && it.pttStabilitySdMs <= 25.0 }
        val usableRate = usableSessions.toDouble() / events.size
        
        return SessionSummary(
            totalSessions = events.size,
            usableSessions = usableSessions,
            usableRate = usableRate,
            avgCombinedSQI = events.map { it.combinedSQI }.average(),
            avgCorrelation = events.map { it.correlation }.average(),
            avgPttStability = events.map { it.pttStabilitySdMs }.average()
        )
    }
}

/**
 * Session telemetry event.
 */
data class SessionEvent(
    val durationSeconds: Double,
    val faceFps: Float,
    val fingerFps: Float,
    val driftMsPerSecond: Double,
    val faceSQI: Double,
    val fingerSQI: Double,
    val combinedSQI: Double,
    val pttMs: Double,
    val correlation: Double,
    val pttStabilitySdMs: Double,
    val issues: List<String>,
    val thermalState: String,
    val torchDurationMs: Long
)

/**
 * Session summary statistics.
 */
data class SessionSummary(
    val totalSessions: Int,
    val usableSessions: Int,
    val usableRate: Double,
    val avgCombinedSQI: Double,
    val avgCorrelation: Double,
    val avgPttStability: Double
) {
    companion object {
        fun empty() = SessionSummary(0, 0, 0.0, 0.0, 0.0, 0.0)
    }
}

