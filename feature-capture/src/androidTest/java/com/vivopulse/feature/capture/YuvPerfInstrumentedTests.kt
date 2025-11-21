package com.vivopulse.feature.capture

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.system.measureNanoTime

@RunWith(AndroidJUnit4::class)
class YuvPerfInstrumentedTests {

    @Test
    fun meanRoiComputationStaysUnderSixMilliseconds() {
        val width = 720
        val height = 1280
        val buffer = ByteBuffer.allocate(width * height)
        val random = Random(42)
        val raw = ByteArray(buffer.capacity())
        random.nextBytes(raw)
        buffer.put(raw)
        val roi = Rect(80, 120, 360, 520)

        val runs = 60
        val timings = LongArray(runs)
        repeat(runs) { idx ->
            buffer.position(0)
            timings[idx] = measureNanoTime {
                LumaExtractor.extractAverageLuma(buffer, roi, width, width, height)
            }
        }
        val avgMs = timings.average() / 1_000_000.0
        assertTrue("ROI mean avg ${"%.2f".format(avgMs)} ms", avgMs < 6.0)
    }
}


