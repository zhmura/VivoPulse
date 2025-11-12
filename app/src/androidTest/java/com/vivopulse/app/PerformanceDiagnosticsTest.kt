package com.vivopulse.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivopulse.feature.processing.PttCalculator
import com.vivopulse.feature.processing.SignalPipeline
import com.vivopulse.feature.processing.simulation.SimulatedFrameSource
import com.vivopulse.feature.processing.simulation.SimulationConfig
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PerformanceDiagnosticsTest {

    @Test
    fun generateDiagnosticsAndPlots() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        val cfg = SimulationConfig(
            heartRateBpm = 72.0,
            pttMs = 110.0,
            noiseLevel = 0.03,
            driftMsPerSecond = 0.0,
            durationSeconds = 30.0,
            sampleRateHz = 100.0
        )

        val plotsDir = File(ctx.getExternalFilesDir(null), "verification/plots")
        plotsDir.mkdirs()
        val diagDir = File(ctx.getExternalFilesDir(null), "verification")
        diagDir.mkdirs()

        val simulator = SimulatedFrameSource(cfg)
        val pipeline = SignalPipeline(targetSampleRateHz = 100.0)

        val t0 = System.nanoTime()
        val raw = simulator.generateSignals()
        val t1 = System.nanoTime()
        val series = pipeline.process(raw)
        val t2 = System.nanoTime()
        val ptt = PttCalculator.computePtt(series)
        val t3 = System.nanoTime()

        val genMs = (t1 - t0) / 1e6
        val dspMs = (t2 - t1) / 1e6
        val xcorrMs = (t3 - t2) / 1e6
        val rssMb = Debug.getPss() / 1024.0

        // Write diagnostics markdown
        val diag = File(diagDir, "diagnostics.md")
        diag.writeText(
            """
            |# Session Diagnostics (Instrumented)
            |
            |- Frames generated: ${raw.face.size}
            |- Generate time: ${"%.2f".format(genMs)} ms
            |- DSP time: ${"%.2f".format(dspMs)} ms
            |- XCorr time: ${"%.2f".format(xcorrMs)} ms
            |- RSS (approx): ${"%.1f".format(rssMb)} MB
            |- corrScore: ${"%.3f".format(ptt.corrScore)}
            |- PTT (ms): ${"%.1f".format(ptt.lagMs)} (SD ${"%.1f".format(ptt.stabilitySdMs)})
            |
            |Artifacts saved under: ${plotsDir.absolutePath}
            |""".trimMargin()
        )

        // Generate simple plots
        fun plot(signal: DoubleArray, name: String) {
            val w = 1200
            val h = 400
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLUE
                strokeWidth = 2f
            }
            val min = signal.minOrNull() ?: 0.0
            val max = signal.maxOrNull() ?: 1.0
            val scaleY = if (max - min < 1e-9) 1.0 else (h * 0.8) / (max - min)
            val offsetY = h / 2.0
            val stepX = w.toDouble() / signal.size
            var xPrev = 0.0
            var yPrev = offsetY
            for (i in signal.indices) {
                val x = i * stepX
                val y = offsetY - ((signal[i] - (min + max) / 2.0) * scaleY)
                c.drawLine(xPrev.toFloat(), yPrev.toFloat(), x.toFloat(), y.toFloat(), paint)
                xPrev = x
                yPrev = y
            }
            File(plotsDir, "$name.png").outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        plot(series.face, "face_filtered")
        plot(series.finger, "finger_filtered")
    }
}


