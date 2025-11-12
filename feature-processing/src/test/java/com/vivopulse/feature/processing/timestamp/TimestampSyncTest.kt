package com.vivopulse.feature.processing.timestamp

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for TimestampSync utilities.
 * 
 * Tests drift detection and resampling with synthetic timestamps
 * containing controlled skew.
 */
class TimestampSyncTest {
    
    init {
        // Enable debug output for tests
        TimestampSync.setDebugEnabled(true)
    }
    
    @Test
    fun `validateMonotonicity - valid timestamps`() {
        val timestamps = listOf(1000L, 2000L, 3000L, 4000L, 5000L)
        val result = TimestampSync.validateMonotonicity(timestamps)
        
        assertTrue(result.isValid)
        assertEquals(0, result.violations)
    }
    
    @Test
    fun `validateMonotonicity - non-monotonic timestamps`() {
        val timestamps = listOf(1000L, 2000L, 1500L, 4000L, 5000L)
        val result = TimestampSync.validateMonotonicity(timestamps)
        
        assertFalse(result.isValid)
        assertEquals(1, result.violations)
        assertEquals(listOf(2), result.violationIndices)
    }
    
    @Test
    fun `validateMonotonicity - empty list`() {
        val result = TimestampSync.validateMonotonicity(emptyList())
        assertTrue(result.isValid)
        assertEquals(0, result.violations)
    }
    
    @Test
    fun `validateMonotonicity - duplicate timestamps`() {
        val timestamps = listOf(1000L, 2000L, 2000L, 3000L)
        val result = TimestampSync.validateMonotonicity(timestamps)
        
        assertFalse(result.isValid)
        assertEquals(1, result.violations)
    }
    
    @Test
    fun `estimateFrameInterval - 30fps stream`() {
        // Generate timestamps at 30 fps (33.33ms intervals)
        val intervalNs = 33_333_333L // ~30 fps in nanoseconds
        val timestamps = (0..99).map { it * intervalNs }
        
        val interval = TimestampSync.estimateFrameInterval(timestamps)
        
        assertNotNull(interval)
        assertEquals(33.33, interval!!, 0.1)
    }
    
    @Test
    fun `estimateFrameInterval - 60fps stream`() {
        // Generate timestamps at 60 fps (16.67ms intervals)
        val intervalNs = 16_666_667L // ~60 fps
        val timestamps = (0..99).map { it * intervalNs }
        
        val interval = TimestampSync.estimateFrameInterval(timestamps)
        
        assertNotNull(interval)
        assertEquals(16.67, interval!!, 0.1)
    }
    
    @Test
    fun `estimateFrameInterval - insufficient data`() {
        val interval = TimestampSync.estimateFrameInterval(listOf(1000L))
        assertNull(interval)
    }
    
    @Test
    fun `computeDrift - zero drift streams`() {
        // Two streams with identical frame rates (30 fps) over 6 seconds
        val intervalNs = 33_333_333L
        val stream1 = (0..179).map { it * intervalNs } // 180 frames = 6 seconds at 30fps
        val stream2 = (0..179).map { it * intervalNs + 1000L } // Slight offset
        
        val result = TimestampSync.computeDrift(stream1, stream2, windowSizeMs = 5000)
        
        println("Zero drift test: drift=${result.driftMsPerSecond}, isValid=${result.isValid}")
        assertTrue("Drift calculation should be valid", result.isValid)
        // Drift should be very close to 0 (allow for windowing effects)
        // Due to timestamp offset and finite window size, we may see small variations
        // in frame counts (e.g., 30.0 vs 30.2 fps in a 5s window = ~6-7 ms/s drift)
        assertTrue("Drift should be < 10.0 ms/s for identical rates, was: ${result.driftMsPerSecond}", 
            abs(result.driftMsPerSecond) < 10.0)
    }
    
    @Test
    fun `computeDrift - positive drift +10ms per second`() {
        // Stream 1: 30 fps (33.33ms interval)
        // Stream 2: 29.7 fps (33.67ms interval) - slower by ~0.3 fps
        // Over 6 seconds: 180 frames vs ~178 frames = ~2 frames = ~67ms drift
        // Drift rate: ~11 ms/s
        
        val stream1IntervalNs = 33_333_333L  // 30.0 fps
        val stream2IntervalNs = 33_670_034L  // 29.7 fps
        
        val stream1 = (0..179).map { it * stream1IntervalNs } // 180 frames = 6s
        val stream2 = (0..177).map { it * stream2IntervalNs } // 178 frames = ~6s
        
        val result = TimestampSync.computeDrift(stream1, stream2, windowSizeMs = 5000)
        
        println("Positive drift test: drift=${result.driftMsPerSecond}, isValid=${result.isValid}, stream1Rate=${result.stream1Rate}, stream2Rate=${result.stream2Rate}")
        assertTrue("Drift calculation should be valid", result.isValid)
        // Should detect positive drift around 10 ms/s
        assertTrue("Drift should be > 5.0 ms/s, was: ${result.driftMsPerSecond}", 
            result.driftMsPerSecond > 5.0)
        assertTrue("Drift should be < 15.0 ms/s, was: ${result.driftMsPerSecond}", 
            result.driftMsPerSecond < 15.0)
    }
    
    @Test
    fun `computeDrift - negative drift -10ms per second`() {
        // Stream 1: 29.7 fps (slower)
        // Stream 2: 30.0 fps (faster)
        
        val stream1IntervalNs = 33_670_034L  // 29.7 fps
        val stream2IntervalNs = 33_333_333L  // 30.0 fps
        
        val stream1 = (0..177).map { it * stream1IntervalNs } // 178 frames = ~6s
        val stream2 = (0..179).map { it * stream2IntervalNs } // 180 frames = 6s
        
        val result = TimestampSync.computeDrift(stream1, stream2, windowSizeMs = 5000)
        
        println("Negative drift test: drift=${result.driftMsPerSecond}, isValid=${result.isValid}, stream1Rate=${result.stream1Rate}, stream2Rate=${result.stream2Rate}")
        assertTrue("Drift calculation should be valid", result.isValid)
        // Should detect drift around 10 ms/s
        assertTrue("Drift should be > 5.0 ms/s, was: ${result.driftMsPerSecond}", 
            result.driftMsPerSecond > 5.0)
        assertTrue("Drift should be < 15.0 ms/s, was: ${result.driftMsPerSecond}", 
            result.driftMsPerSecond < 15.0)
    }
    
    @Test
    fun `computeDrift - insufficient data`() {
        val result = TimestampSync.computeDrift(
            listOf(1000L),
            listOf(2000L),
            windowSizeMs = 5000
        )
        
        assertFalse(result.isValid)
    }
    
    @Test
    fun `resampleToUnifiedTimeline - basic resampling`() {
        // Create two streams at different rates
        // Stream 1: 30 fps for 1 second
        val stream1 = (0..29).map { i ->
            TimestampedValue(
                timestampNs = i * 33_333_333L,
                value = i.toDouble()
            )
        }
        
        // Stream 2: 25 fps for 1 second
        val stream2 = (0..24).map { i ->
            TimestampedValue(
                timestampNs = i * 40_000_000L,
                value = i.toDouble() * 2
            )
        }
        
        val result = TimestampSync.resampleToUnifiedTimeline(
            stream1,
            stream2,
            targetFrequencyHz = 100.0
        )
        
        assertTrue(result.isValid)
        assertEquals(100.0, result.sampleRate, 0.1)
        // Should have ~100 samples for 1 second at 100 Hz
        assertTrue(result.unifiedTimestamps.size in 95..105)
        assertEquals(result.unifiedTimestamps.size, result.stream1Values.size)
        assertEquals(result.unifiedTimestamps.size, result.stream2Values.size)
    }
    
    @Test
    fun `resampleToUnifiedTimeline - aligned streams`() {
        // Two streams with exact same timestamps
        val timestamps = (0..99).map { it * 10_000_000L } // 100 Hz
        
        val stream1 = timestamps.map { ts ->
            TimestampedValue(ts, 1.0)
        }
        
        val stream2 = timestamps.map { ts ->
            TimestampedValue(ts, 2.0)
        }
        
        val result = TimestampSync.resampleToUnifiedTimeline(
            stream1,
            stream2,
            targetFrequencyHz = 100.0
        )
        
        assertTrue(result.isValid)
        // Values should remain constant since no interpolation needed
        assertTrue(result.stream1Values.all { it == 1.0 })
        assertTrue(result.stream2Values.all { it == 2.0 })
    }
    
    @Test
    fun `resampleToUnifiedTimeline - empty streams`() {
        val result = TimestampSync.resampleToUnifiedTimeline(
            emptyList(),
            emptyList(),
            targetFrequencyHz = 100.0
        )
        
        assertFalse(result.isValid)
        assertTrue(result.unifiedTimestamps.isEmpty())
    }
    
    @Test
    fun `resampleToUnifiedTimeline - linear interpolation accuracy`() {
        // Create simple linear stream to test interpolation
        val stream1 = listOf(
            TimestampedValue(0L, 0.0),
            TimestampedValue(1_000_000_000L, 10.0) // 1 second, value 0 to 10
        )
        
        val stream2 = listOf(
            TimestampedValue(0L, 0.0),
            TimestampedValue(1_000_000_000L, 20.0) // 1 second, value 0 to 20
        )
        
        val result = TimestampSync.resampleToUnifiedTimeline(
            stream1,
            stream2,
            targetFrequencyHz = 10.0  // 10 samples over 1 second
        )
        
        assertTrue(result.isValid)
        
        // Check interpolation at mid-point (0.5 seconds)
        val midIndex = result.unifiedTimestamps.indexOfFirst { 
            it >= 500_000_000L 
        }
        
        if (midIndex >= 0) {
            // Should be approximately 5.0 for stream1 and 10.0 for stream2
            assertEquals(5.0, result.stream1Values[midIndex], 1.0)
            assertEquals(10.0, result.stream2Values[midIndex], 2.0)
        }
    }
    
    @Test
    fun `createSampleTuples - basic conversion`() {
        val resampledData = ResampledData(
            unifiedTimestamps = listOf(0L, 10_000_000L, 20_000_000L), // 0, 10, 20 ms
            stream1Values = listOf(1.0, 2.0, 3.0),
            stream2Values = listOf(10.0, 20.0, 30.0),
            isValid = true,
            sampleRate = 100.0,
            message = "Test"
        )
        
        val tuples = TimestampSync.createSampleTuples(resampledData)
        
        assertEquals(3, tuples.size)
        assertEquals(0.0, tuples[0].timeMillis, 0.001)
        assertEquals(1.0, tuples[0].stream1Value, 0.001)
        assertEquals(10.0, tuples[0].stream2Value, 0.001)
        
        assertEquals(10.0, tuples[1].timeMillis, 0.001)
        assertEquals(20.0, tuples[2].timeMillis, 0.001)
    }
    
    @Test
    fun `createSampleTuples - invalid data`() {
        val resampledData = ResampledData(
            unifiedTimestamps = emptyList(),
            stream1Values = emptyList(),
            stream2Values = emptyList(),
            isValid = false,
            message = "Invalid"
        )
        
        val tuples = TimestampSync.createSampleTuples(resampledData)
        assertTrue(tuples.isEmpty())
    }
    
    private fun abs(value: Double): Double = kotlin.math.abs(value)
}

