package com.vivopulse.io.model

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for ExportSchema classes.
 */
class ExportSchemaTest {

    @Test
    fun `SignalDataPoint toCsvRow formats all fields correctly`() {
        val point = SignalDataPoint(
            timeMs = 123.456,
            rawValue = 0.123456,
            filteredValue = 0.654321,
            isPeak = true,
            rgb = Triple(0.5, 0.6, 0.7),
            motion = 0.0012,
            saturation = 0.855,
            imu = 0.0098,
            phaseTag = "systolic"
        )
        
        val csv = point.toCsvRow()
        
        // Expected format: time_ms,raw_value,filtered_value,is_peak,r,g,b,motion_rms,saturation_pct,imu_rms_g,phase_tag
        assertTrue("CSV should contain time", csv.startsWith("123.456"))
        assertTrue("CSV should contain isPeak=1", csv.contains(",1,"))
        assertTrue("CSV should contain RGB", csv.contains("0.500,0.600,0.700"))
        assertTrue("CSV should contain motion", csv.contains("0.0012"))
        assertTrue("CSV should contain saturation fraction", csv.contains("0.8550"))
        assertTrue("CSV should contain imu", csv.contains("0.0098"))
        assertTrue("CSV should contain phase tag and empty timing metadata", csv.endsWith(",systolic,,"))
    }

    @Test
    fun `SignalDataPoint toCsvRow handles null RGB gracefully`() {
        val point = SignalDataPoint(
            timeMs = 100.0,
            rawValue = 0.5,
            filteredValue = 0.6,
            isPeak = false,
            rgb = null,
            motion = 0.01,
            saturation = 0.90,
            imu = 0.02
        )
        
        val csv = point.toCsvRow()
        
        // Should have empty RGB columns
        assertTrue("CSV should have empty RGB fields", csv.contains(",,,"))
        assertTrue("CSV should contain motion", csv.contains("0.0100"))
    }

    @Test
    fun `missing quality metrics remain blank instead of certifying zero motion`() {
        val point = SignalDataPoint(
            timeMs = 50.0,
            rawValue = 0.1,
            filteredValue = 0.2,
            motion = null,
            saturation = null,
            imu = null
        )
        
        val csv = point.toCsvRow()
        
        val columns = csv.split(',')
        assertEquals(13, columns.size)
        assertEquals("", columns[7])
        assertEquals("", columns[8])
        assertEquals("", columns[9])
    }

    @Test
    fun `CSV_HEADER contains all expected columns`() {
        val header = SignalDataPoint.CSV_HEADER
        
        assertTrue("Header should contain time_ms", header.contains("time_ms"))
        assertTrue("Header should contain raw_value", header.contains("raw_value"))
        assertTrue("Header should contain filtered_value", header.contains("filtered_value"))
        assertTrue("Header should contain is_peak", header.contains("is_peak"))
        assertTrue("Header should contain r,g,b", header.contains("r,g,b"))
        assertTrue("Header should contain motion_rms", header.contains("motion_rms"))
        assertTrue("Header should identify saturation units", header.contains("saturation_fraction"))
        assertTrue("Header should contain imu_rms_g", header.contains("imu_rms_g"))
        assertTrue("Header should contain phase_tag", header.contains("phase_tag"))
        assertTrue("Header should preserve timestamp provenance", header.endsWith("timestamp_ns,interpolated"))
    }

    @Test
    fun `CSV is locale independent and preserves exact nanoseconds`() {
        val oldLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val point = SignalDataPoint(123.456, 0.123456, 0.654321,
                timestampNs = 9_123_456_789_012_345L, interpolated = false)
            val columns = point.toCsvRow().split(',')
            assertEquals(13, columns.size)
            assertEquals("123.456", columns[0])
            assertEquals("0.123456", columns[1])
            assertEquals("9123456789012345", columns[11])
            assertEquals("false", columns[12])
        } finally {
            Locale.setDefault(oldLocale)
        }
    }

    @Test
    fun `nonfinite values are missing and CSV text is escaped`() {
        val point = SignalDataPoint(1.0, Double.NaN, Double.POSITIVE_INFINITY,
            phaseTag = "phase,\"uncertain\"", motion = Double.NEGATIVE_INFINITY)
        val csv = point.toCsvRow()
        assertTrue(csv.startsWith("1.000,,,0,"))
        assertFalse(csv.contains("NaN"))
        assertFalse(csv.contains("Infinity"))
        assertTrue(csv.contains("\"phase,\"\"uncertain\"\"\""))
    }

    @Test
    fun `source CSV does not interpolate gaps or invent missing observations`() {
        val csv = ExportFormatting.sourceCsv(listOf(10_000_000_001L to 1.5, 10_500_000_002L to Double.NaN))
        val rows = csv.trimEnd().lines()
        assertEquals(3, rows.size)
        assertEquals("10000000001,1.500000000,false", rows[1])
        assertEquals("10500000002,,false", rows[2])
    }

    @Test
    fun `stale numerical PTT cannot override rejection or unverified timing`() {
        val measured = SessionMetadata(
            appVersion = "test", deviceManufacturer = "test", deviceModel = "test", androidVersion = "test",
            sessionId = "fixture", startTimestamp = 0L, endTimestamp = 1000L, durationSeconds = 1.0,
            sampleRateHz = 100.0, sampleCount = 100, faceSQI = 90.0, fingerSQI = 90.0, combinedSQI = 90.0,
            pttMs = 100.0, pttCorrelation = 0.95, pttStabilityMs = 2.0, pttConfidence = 85.0,
            pttQuality = "HIGH", faceFps = 30f, fingerFps = 30f, driftMsPerSecond = Double.NaN,
            timingVerified = true, pttValid = true
        )
        assertTrue(measured.hasReportablePtt)
        assertFalse(measured.copy(pttValid = false).hasReportablePtt)
        assertFalse(measured.copy(timingVerified = false).hasReportablePtt)
        assertFalse(measured.copy(rejectionReasons = listOf("MOTION")).hasReportablePtt)
        assertFalse(measured.copy(pttMs = Double.NaN).hasReportablePtt)
    }

    @Test
    fun `SignalDataPoint isPeak formats as 0 or 1`() {
        val peakPoint = SignalDataPoint(timeMs = 1.0, rawValue = 1.0, filteredValue = 1.0, isPeak = true)
        val nonPeakPoint = SignalDataPoint(timeMs = 1.0, rawValue = 1.0, filteredValue = 1.0, isPeak = false)
        
        assertTrue("Peak should be 1", peakPoint.toCsvRow().contains(",1,"))
        assertTrue("Non-peak should be 0", nonPeakPoint.toCsvRow().contains(",0,"))
    }

    @Test
    fun `ExportSegment contains PTT and SQI data`() {
        val segment = ExportSegment(
            startTimeS = 0.0,
            endTimeS = 30.0,
            pttMs = 85.5,
            correlation = 0.92,
            sqiFace = 88.0,
            sqiFinger = 90.0
        )
        
        assertEquals(0.0, segment.startTimeS, 0.01)
        assertEquals(30.0, segment.endTimeS, 0.01)
        assertEquals(85.5, segment.pttMs, 0.1)
        assertEquals(0.92, segment.correlation, 0.01)
        assertTrue("SQI Face should be high", segment.sqiFace >= 80)
    }
}
