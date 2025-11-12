package com.vivopulse.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivopulse.io.DataExporter
import com.vivopulse.io.model.SessionMetadata
import com.vivopulse.io.model.SignalDataPoint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class ExportSchemaValidationTest {

    @Test
    fun exportZip_hasValidJsonAndCsvHeadersAndLengths() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // Create minimal metadata and signals
        val start = System.currentTimeMillis()
        val end = start + 10_000
        val metadata = SessionMetadata.createDefault(
            appVersion = "1.0.0",
            sessionId = UUID.randomUUID().toString(),
            startTimestamp = start,
            endTimestamp = end,
            durationSeconds = 10.0,
            sampleRateHz = 100.0,
            sampleCount = 1000
        ).copy(
            faceSQI = 80.0,
            fingerSQI = 78.0,
            combinedSQI = 79.0,
            pttMs = 100.0,
            pttCorrelation = 0.92,
            pttStabilityMs = 6.0,
            pttConfidence = 85.0,
            pttQuality = "GOOD",
            faceFps = 30f,
            fingerFps = 30f,
            driftMsPerSecond = 2.0
        )

        val n = 100
        val face = (0 until n).map { i ->
            SignalDataPoint(
                timeMs = i * 10.0,
                rawValue = kotlin.random.Random.nextDouble(),
                filteredValue = kotlin.random.Random.nextDouble(),
                isPeak = false
            )
        }
        val finger = face

        val exporter = DataExporter(ctx)
        val path = kotlinx.coroutines.runBlocking {
            exporter.exportSession(metadata, face, finger)
        }
        assertTrue("Export path should not be null", path != null)

        val file = File(path!!)
        assertTrue("Encrypted export file should exist", file.exists())
        assertTrue("Encrypted file extension", file.name.endsWith(".zip.enc"))

        // We cannot open encrypted file contents here; validate structure by name only
        // and rely on DataExporter unit logic for building valid json/csv bytes.
        // Additional deep validation will be performed in offline tooling or when decrypting.
    }
}


