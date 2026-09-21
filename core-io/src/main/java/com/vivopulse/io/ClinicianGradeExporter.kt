package com.vivopulse.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.vivopulse.io.model.SessionMetadata
import com.vivopulse.io.model.SignalDataPoint
import com.vivopulse.io.model.ExportSegment
import com.vivopulse.io.model.ExportExtras
import com.vivopulse.io.model.ExportFormatting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.vivopulse.signal.TimeFrequencyTooling

/**
 * Research export manager. The class name is retained for API compatibility.
 * 
 * Produces comprehensive encrypted ZIP with:
 * - session.json (full metadata)
 * - face_signal.csv & finger_signal.csv
 * - plots/ directory with PNG visualizations
 * This export format does not establish clinical validity.
 */
class ClinicianGradeExporter(private val context: Context) {
    
    private val tag = "ClinicianExporter"
    
    /**
     * Export complete session with plots.
     * 
     * @param metadata Session metadata
     * @param faceSignal Face signal data
     * @param fingerSignal Finger signal data
     * @param thermalTimeline Thermal state timeline (optional)
     * @param threeAState 3A lock state timeline (optional)
     * @return Export file path
     */
    suspend fun exportSession(
        metadata: SessionMetadata,
        faceSignal: List<SignalDataPoint>,
        fingerSignal: List<SignalDataPoint>,
        thermalTimeline: List<ThermalEvent>? = null,
        threeAState: ThreeATimeline? = null,
        includeTimeFrequency: Boolean = false,
        segments: List<ExportSegment> = emptyList(),
        extras: ExportExtras? = null,
        sourceFaceSamples: List<Pair<Long, Double>> = emptyList(),
        sourceFingerSamples: List<Pair<Long, Double>> = emptyList()
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Generate filename
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "VivoPulse_Clinical_${timestamp}.zip.enc"
            
            // Get Documents directory
            val documentsDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "VivoPulse/Clinical")
            } else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "VivoPulse/Clinical")
            }
            documentsDir.mkdirs()
            
            val outFile = File(documentsDir, filename)
            
            // Build ZIP in-memory
            val zipBytes = ByteArrayOutputStream()
            ZipOutputStream(zipBytes).use { zip ->
                // 1. session.json (comprehensive metadata)
                zip.putNextEntry(ZipEntry("session.json"))
                val sessionJson = createClinicianJson(metadata, thermalTimeline, threeAState, segments, extras,
                    sourceFaceSamples.isNotEmpty(), sourceFingerSamples.isNotEmpty())
                zip.write(sessionJson.toByteArray())
                zip.closeEntry()

                for ((name, samples) in listOf("source_face.csv" to sourceFaceSamples, "source_finger.csv" to sourceFingerSamples)) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(ExportFormatting.sourceCsv(samples).toByteArray())
                    zip.closeEntry()
                }
                
                // 1b. device_capabilities.json (extracted from session.json)
                zip.putNextEntry(ZipEntry("device_capabilities.json"))
                val deviceCaps = JSONObject(sessionJson).getJSONObject("device")
                zip.write(deviceCaps.toString(2).toByteArray())
                zip.closeEntry()
                
                // 1c. fps_report.json (extracted from session.json)
                zip.putNextEntry(ZipEntry("fps_report.json"))
                val cameraStats = JSONObject(sessionJson).getJSONObject("camera")
                zip.write(cameraStats.toString(2).toByteArray())
                zip.closeEntry()
                
                // 1d. thermal_timeline.json
                if (thermalTimeline != null) {
                    zip.putNextEntry(ZipEntry("thermal_timeline.json"))
                    val thermalJson = JSONObject().apply {
                        put("events", JSONArray().apply {
                            thermalTimeline.forEach { event ->
                                put(JSONObject().apply {
                                    put("time_s", ExportJson.value(event.timeS))
                                    put("state", event.state)
                                })
                            }
                        })
                    }
                    zip.write(thermalJson.toString(2).toByteArray())
                    zip.closeEntry()
                }
                
                // 1e. TF tooling exports (if enabled)
                if (includeTimeFrequency) {
                    // Convert SignalDataPoint list to DoubleArray
                    val faceArray = faceSignal.map { it.filteredValue }.toDoubleArray()
                    val fingerArray = fingerSignal.map { it.filteredValue }.toDoubleArray()
                    val fs = metadata.sampleRateHz
                    
                    // Compute STFT
                    val faceStft = TimeFrequencyTooling.computeSTFT(faceArray, fs)
                    val fingerStft = TimeFrequencyTooling.computeSTFT(fingerArray, fs)
                    
                    // Write CSVs
                    zip.putNextEntry(ZipEntry("tf/tf_face_stft.csv"))
                    zip.write(faceStft.toCsv().toByteArray())
                    zip.closeEntry()
                    
                    zip.putNextEntry(ZipEntry("tf/tf_finger_stft.csv"))
                    zip.write(fingerStft.toCsv().toByteArray())
                    zip.closeEntry()
                }
                
                // 2. face_signal.csv
                zip.putNextEntry(ZipEntry("face_signal.csv"))
                zip.write(createSignalCsv(faceSignal).toByteArray())
                zip.closeEntry()
                
                // 3. finger_signal.csv
                zip.putNextEntry(ZipEntry("finger_signal.csv"))
                zip.write(createSignalCsv(fingerSignal).toByteArray())
                zip.closeEntry()
                
                // 4. plots/raw_vs_filtered.png (10s window)
                zip.putNextEntry(ZipEntry("plots/raw_vs_filtered.png"))
                zip.write(generateRawVsFilteredPlot(faceSignal, fingerSignal))
                zip.closeEntry()
                
                // 5. plots/peaks_overlay.png
                zip.putNextEntry(ZipEntry("plots/peaks_overlay.png"))
                zip.write(generatePeaksOverlayPlot(faceSignal, fingerSignal))
                zip.closeEntry()
                
                // A real cross-correlation curve is not supplied by this API; do not fabricate one.
            }
            
            // Encrypt ZIP
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
            
            Log.d(tag, "Clinician-grade export complete: ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(tag, "Export failed", e)
            null
        }
    }
    
    /**
     * Create comprehensive clinician JSON.
     */
    internal fun createClinicianJson(
        metadata: SessionMetadata,
        thermalTimeline: List<ThermalEvent>?,
        threeAState: ThreeATimeline?,
        segments: List<ExportSegment>,
        extras: ExportExtras?,
        sourceFaceAvailable: Boolean = false,
        sourceFingerAvailable: Boolean = false
    ): String {
        return JSONObject().apply {
            put("schema_version", SessionMetadata.SCHEMA_VERSION)
            put("export_type", "research_with_plots")
            ExportJson.addAudit(this, metadata, sourceFaceAvailable, sourceFingerAvailable)
            put("exported_at_iso", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()))
            
            // Device info
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
                put("start_ts_iso", if (metadata.startTimestamp > 0L) SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(metadata.startTimestamp)) else JSONObject.NULL)
                put("end_ts_iso", if (metadata.endTimestamp > 0L) SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(metadata.endTimestamp)) else JSONObject.NULL)
                put("duration_s", ExportJson.value(metadata.durationSeconds))
            })
            
            // Camera metrics
            put("camera", JSONObject().apply {
                put("fps_face", ExportJson.value(metadata.faceFps))
                put("fps_finger", ExportJson.value(metadata.fingerFps))
                put("native_face_rate_hz", ExportJson.value(metadata.nativeFaceRateHz))
                put("native_finger_rate_hz", ExportJson.value(metadata.nativeFingerRateHz))
                put("drift_ms_per_second", ExportJson.value(metadata.driftMsPerSecond))
            })
            
            // PTT metrics
            put("ptt", JSONObject().apply {
                put("valid", metadata.hasReportablePtt)
                put("ptt_ms_mean", ExportJson.pttValue(metadata, metadata.pttMs))
                put("ptt_ms_sd", ExportJson.pttValue(metadata, metadata.pttStabilityMs))
                put("corr_score", ExportJson.pttValue(metadata, metadata.pttCorrelation))
                put("confidence_0_to_1", ExportJson.pttValue(metadata, metadata.pttConfidence.takeIf { it in 0.0..100.0 }?.div(100.0)))
                put("confidence_percent", ExportJson.pttValue(metadata, metadata.pttConfidence.takeIf { it in 0.0..100.0 }))
                put("quality", if (metadata.hasReportablePtt) metadata.pttQuality else "UNAVAILABLE")
            })
            
            // Quality metrics
            put("quality", JSONObject().apply {
                put("sqi_face", ExportJson.value(metadata.faceSQI))
                put("sqi_finger", ExportJson.value(metadata.fingerSQI))
                put("sqi_combined", ExportJson.value(metadata.combinedSQI))
            })
            
            // Harmonics (Session Level)
            if (metadata.harmonicSummaryFace != null || metadata.harmonicSummaryFinger != null) {
                put("harmonics_summary", JSONObject().apply {
                    metadata.harmonicSummaryFace?.let { h ->
                        put("face", JSONObject().apply {
                            put("fundamental_hz", ExportJson.value(h.fundamentalHz))
                            put("h2_h1_ratio", ExportJson.value(h.h2ToH1Ratio))
                            put("spectral_entropy", ExportJson.value(h.spectralEntropy))
                        })
                    }
                    metadata.harmonicSummaryFinger?.let { h ->
                        put("finger", JSONObject().apply {
                            put("fundamental_hz", ExportJson.value(h.fundamentalHz))
                            put("h2_h1_ratio", ExportJson.value(h.h2ToH1Ratio))
                            put("spectral_entropy", ExportJson.value(h.spectralEntropy))
                        })
                    }
                })
            }
            
            // Segments (GoodSync windows)
            put("segments", JSONArray().apply {
                segments.forEach { seg ->
                    put(JSONObject().apply {
                        put("start_time_s", ExportJson.value(seg.startTimeS))
                        put("end_time_s", ExportJson.value(seg.endTimeS))
                        put("ptt_ms", ExportJson.pttValue(metadata, seg.pttMs))
                        put("correlation", ExportJson.pttValue(metadata, seg.correlation))
                        put("sqi_face", ExportJson.value(seg.sqiFace))
                        put("sqi_finger", ExportJson.value(seg.sqiFinger))
                        
                        // Diagnostics
                        if (seg.pttMeanDenoised != null) {
                            put("ptt_mean_denoised", ExportJson.pttValue(metadata, seg.pttMeanDenoised))
                            put("ptt_mean_raw", ExportJson.pttValue(metadata, seg.pttMeanRaw))
                        }
                        
                        if (seg.harmonicsFace != null) {
                            put("harmonics_face", JSONObject().apply {
                                put("fundamental_hz", ExportJson.value(seg.harmonicsFace.fundamentalHz))
                                put("h2_h1_ratio", ExportJson.value(seg.harmonicsFace.h2ToH1Ratio))
                                put("spectral_entropy", ExportJson.value(seg.harmonicsFace.spectralEntropy))
                            })
                        }
                    })
                }
            })
            
            // Processing parameters
            put("processing_params", JSONObject().apply {
                put("algorithm_revision", metadata.algorithmRevision)
                put("processing_grid_hz", ExportJson.value(metadata.sampleRateHz))
                put("filter_parameters", JSONObject.NULL)
                put("roi_geometry", JSONObject.NULL)
                put("configuration_note", "Exact filter parameters and ROI geometry are not supplied to this exporter.")
            })
            
            // 3A state
            threeAState?.let { state ->
                put("three_a_state", JSONObject().apply {
                    put("ae_locked_at_s", ExportJson.value(state.aeLockedAtS))
                    put("awb_locked_at_s", ExportJson.value(state.awbLockedAtS))
                    put("af_mode", state.afMode)
                })
            }
            
            // Thermal timeline
            thermalTimeline?.let { timeline ->
                put("thermal_events", JSONArray().apply {
                    timeline.forEach { event ->
                        put(JSONObject().apply {
                            put("time_s", ExportJson.value(event.timeS))
                            put("state", event.state)
                        })
                    }
                })
            }
            
            ExportJson.addExtras(this, metadata, extras)
        }.toString(2)
    }
    
    /**
     * Create signal CSV.
     */
    private fun createSignalCsv(signalData: List<SignalDataPoint>): String {
        return buildString {
            appendLine(SignalDataPoint.CSV_HEADER)
            signalData.forEach { point ->
                appendLine(point.toCsvRow())
            }
        }
    }
    
    /**
     * Generate raw vs filtered plot (10s window).
     */
    private fun generateRawVsFilteredPlot(
        faceSignal: List<SignalDataPoint>,
        fingerSignal: List<SignalDataPoint>
    ): ByteArray {
        val width = 1200
        val height = 800
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        
        // Take first 10s of data (1000 samples @ 100 Hz)
        val windowSize = minOf(1000, faceSignal.size)
        
        // Plot face (top half)
        plotSignalComparison(
            canvas,
            faceSignal.take(windowSize),
            0, 0, width, height / 2,
            "Face (Raw vs Filtered)"
        )
        
        // Plot finger (bottom half)
        plotSignalComparison(
            canvas,
            fingerSignal.take(windowSize),
            0, height / 2, width, height / 2,
            "Finger (Raw vs Filtered)"
        )
        
        return bitmapToBytes(bitmap)
    }
    
    /**
     * Generate peaks overlay plot.
     */
    private fun generatePeaksOverlayPlot(
        faceSignal: List<SignalDataPoint>,
        fingerSignal: List<SignalDataPoint>
    ): ByteArray {
        val width = 1200
        val height = 800
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        
        val windowSize = minOf(1000, faceSignal.size)
        
        plotSignalWithPeaks(
            canvas,
            faceSignal.take(windowSize),
            0, 0, width, height / 2,
            "Face Signal with Peaks"
        )
        
        plotSignalWithPeaks(
            canvas,
            fingerSignal.take(windowSize),
            0, height / 2, width, height / 2,
            "Finger Signal with Peaks"
        )
        
        return bitmapToBytes(bitmap)
    }
    
    /**
     * Plot signal comparison (raw vs filtered).
     */
    private fun plotSignalComparison(
        canvas: Canvas,
        data: List<SignalDataPoint>,
        x: Int, y: Int, width: Int, height: Int,
        title: String
    ) {
        // Simple line plot
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = 30f
        canvas.drawText(title, x + 20f, y + 40f, paint)
        
        if (data.isEmpty()) return
        
        // Plot area
        val plotX = x + 50
        val plotY = y + 60
        val plotW = width - 100
        val plotH = height - 80
        
        // Axes
        paint.color = Color.GRAY
        canvas.drawLine(plotX.toFloat(), (plotY + plotH).toFloat(), (plotX + plotW).toFloat(), (plotY + plotH).toFloat(), paint)
        
        // Plot signals
        val stepX = plotW.toFloat() / data.size
        
        // Raw (blue)
        paint.color = Color.BLUE
        paint.strokeWidth = 2f
        for (i in 1 until data.size) {
            val x1 = plotX + (i - 1) * stepX
            val x2 = plotX + i * stepX
            val y1 = (plotY + plotH / 2 - data[i - 1].rawValue * plotH / 6).toFloat()
            val y2 = (plotY + plotH / 2 - data[i].rawValue * plotH / 6).toFloat()
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        
        // Filtered (red)
        paint.color = Color.RED
        for (i in 1 until data.size) {
            val x1 = plotX + (i - 1) * stepX
            val x2 = plotX + i * stepX
            val y1 = (plotY + plotH / 2 - data[i - 1].filteredValue * plotH / 6).toFloat()
            val y2 = (plotY + plotH / 2 - data[i].filteredValue * plotH / 6).toFloat()
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
    }
    
    /**
     * Plot signal with peak markers.
     */
    private fun plotSignalWithPeaks(
        canvas: Canvas,
        data: List<SignalDataPoint>,
        x: Int, y: Int, width: Int, height: Int,
        title: String
    ) {
        plotSignalComparison(canvas, data, x, y, width, height, title)
        
        // Add peak markers
        val plotX = x + 50
        val plotY = y + 60
        val plotW = width - 100
        val plotH = height - 80
        val stepX = plotW.toFloat() / data.size
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.GREEN
        paint.strokeWidth = 8f
        
        data.forEachIndexed { i, point ->
            if (point.isPeak) {
                val px = plotX + i * stepX
                val py = (plotY + plotH / 2 - point.filteredValue * plotH / 6).toFloat()
                canvas.drawCircle(px, py, 10f, paint)
            }
        }
    }
    
    /**
     * Convert bitmap to PNG bytes.
     */
    private fun bitmapToBytes(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}

/**
 * Thermal event for timeline.
 */
data class ThermalEvent(
    val timeS: Double,
    val state: String
)

/**
 * 3A state timeline.
 */
data class ThreeATimeline(
    val aeLockedAtS: Double?,
    val awbLockedAtS: Double?,
    val afMode: String
)
