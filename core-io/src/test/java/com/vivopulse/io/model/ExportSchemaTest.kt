package com.vivopulse.io.model

import org.junit.Assert.*
import org.junit.Test

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
            saturation = 85.5,
            imu = 0.0098,
            phaseTag = "systolic"
        )
        
        val csv = point.toCsvRow()
        
        // Expected format: time_ms,raw_value,filtered_value,is_peak,r,g,b,motion_rms,saturation_pct,imu_rms_g,phase_tag
        assertTrue("CSV should contain time", csv.startsWith("123.456"))
        assertTrue("CSV should contain isPeak=1", csv.contains(",1,"))
        assertTrue("CSV should contain RGB", csv.contains("0.500,0.600,0.700"))
        assertTrue("CSV should contain motion", csv.contains("0.0012"))
        assertTrue("CSV should contain saturation", csv.contains("85.50"))
        assertTrue("CSV should contain imu", csv.contains("0.0098"))
        assertTrue("CSV should contain phase tag", csv.endsWith(",systolic"))
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
            saturation = 90.0,
            imu = 0.02
        )
        
        val csv = point.toCsvRow()
        
        // Should have empty RGB columns
        assertTrue("CSV should have empty RGB fields", csv.contains(",,,"))
        assertTrue("CSV should contain motion", csv.contains("0.0100"))
    }

    @Test
    fun `SignalDataPoint toCsvRow handles null metrics as zeros`() {
        val point = SignalDataPoint(
            timeMs = 50.0,
            rawValue = 0.1,
            filteredValue = 0.2,
            motion = null,
            saturation = null,
            imu = null
        )
        
        val csv = point.toCsvRow()
        
        // Null metrics should be formatted as 0.0
        assertTrue("CSV should contain default motion 0", csv.contains("0.0000"))
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
        assertTrue("Header should contain saturation_pct", header.contains("saturation_pct"))
        assertTrue("Header should contain imu_rms_g", header.contains("imu_rms_g"))
        assertTrue("Header should contain phase_tag", header.contains("phase_tag"))
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
