package com.vivopulse.app.reporting

import android.util.Log
import com.vivopulse.feature.processing.realtime.RealTimeQualityState
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the most recent real-time quality metrics to a markdown report.
 */
class QualityIndicatorReporter(
    private val outputDir: File
) {
    private val formatter = DecimalFormat("0.0")
    private val percentFormatter = DecimalFormat("0.0")
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val samples = ArrayDeque<RealTimeQualityState>()
    private val maxSamples = 600

    fun record(state: RealTimeQualityState) {
        if (samples.size >= maxSamples) {
            samples.removeFirst()
        }
        samples.addLast(state)
    }
    
    fun reset() {
        samples.clear()
    }

    fun writeReport(tipsLog: List<String>) {
        if (samples.isEmpty()) return
        outputDir.mkdirs()
        val file = File(outputDir, REPORT_NAME)
        file.writeText(buildReport(tipsLog))
        Log.i(TAG, "Quality indicator report written to ${file.absolutePath}")
    }

    private fun buildReport(tipsLog: List<String>): String {
        val builder = StringBuilder()
        val firstTimestamp = samples.first().updatedAtMs
        builder.appendLine("# Quality Indicator Report")
        builder.appendLine()
        builder.appendLine("- Generated: ${timestampFormatter.format(Date())}")
        builder.appendLine("- Samples: ${samples.size}")
        builder.appendLine()
        builder.appendLine("## Thresholds")
        builder.appendLine()
        builder.appendLine("| Metric | Green | Yellow | Red |")
        builder.appendLine("| --- | --- | --- | --- |")
        builder.appendLine("| Finger Saturation | ≤ 5% | 5-15% | > 15% |")
        builder.appendLine("| Finger SNR | ≥ 10 dB | 6-10 dB | < 6 dB |")
        builder.appendLine("| Face SNR | ≥ 6 dB | 3-6 dB | < 3 dB |")
        builder.appendLine("| Face Motion | ≤ 0.5 px | 0.5-1.0 px | > 1.0 px |")
        builder.appendLine("| HR Agreement | ≤ 5 bpm | 5-10 bpm | > 10 bpm |")
        builder.appendLine()
        builder.appendLine("## Last Session Metrics")
        builder.appendLine()
        builder.appendLine("| t (s) | Face SNR (dB) | Finger SNR (dB) | Sat % | Motion px | ΔHR bpm | Tip |")
        builder.appendLine("| --- | --- | --- | --- | --- | --- | --- |")

        samples.forEach { state ->
            val tSec = (state.updatedAtMs - firstTimestamp) / 1000.0
            val faceSnr = state.face.snrDb?.let { formatter.format(it) } ?: "—"
            val fingerSnr = state.finger.snrDb?.let { formatter.format(it) } ?: "—"
            val sat = state.finger.saturationPct?.let { percentFormatter.format(it * 100) } ?: "—"
            val motion = state.face.motionRmsPx?.let { formatter.format(it) } ?: "—"
            val deltaHr = state.hrAgreementDeltaBpm?.let { formatter.format(it) } ?: "—"
            builder.append("| ${formatter.format(tSec)} | ")
            builder.append("$faceSnr | ")
            builder.append("$fingerSnr | ")
            builder.append("$sat | ")
            builder.append("$motion | ")
            builder.append("$deltaHr | ")
            builder.append("${state.tip ?: ""} |\n")
        }

        builder.appendLine()
        builder.appendLine("## Tips Emitted")
        builder.appendLine()
        if (tipsLog.isEmpty()) {
            builder.appendLine("_No corrective tips were emitted in this session._")
        } else {
            tipsLog.forEach { builder.appendLine("- $it") }
        }
        builder.appendLine()
        builder.appendLine("## Screenshot")
        builder.appendLine()
        builder.appendLine("![Quality Indicators](quality_indicator_screenshot.png)")
        builder.appendLine()
        builder.appendLine("_Place the latest screenshot next to this report to complete the audit trail._")
        return builder.toString()
    }

    companion object {
        private const val TAG = "QualityIndicatorReport"
        private const val REPORT_NAME = "QUALITY_INDICATOR_REPORT.md"
    }
}


