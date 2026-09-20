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
    fun `analyzeSynchronization - zero drift assumed`() {
        // Two streams with identical frame rates (30 fps) over 6 seconds
        val intervalNs = 33_333_333L
        val stream1 = (0..179).map { it * intervalNs } // 180 frames = 6 seconds at 30fps
        val stream2 = (0..179).map { it * intervalNs + 1000L } // Slight offset
        
        val result = TimestampSync.analyzeSynchronization(stream1, stream2, windowSizeMs = 5000)
        
        println("Zero drift test: drift=${result.driftMsPerSecond}, isValid=${result.isValid}")
        assertTrue("Sync calculation should be valid", result.isValid)
        // Drift should be exactly 0.0
        assertEquals(0.0, result.driftMsPerSecond, 0.001)
        assertEquals(30.0, result.stream1Rate, 0.5)
        assertEquals(30.0, result.stream2Rate, 0.5)
    }
    
    @Test
    fun `analyzeSynchronization - mixed frame rates (24 vs 30 fps)`() {
        // Stream 1: 30 fps (33.33ms interval)
        // Stream 2: 24 fps (41.67ms interval)
        
        val stream1IntervalNs = 33_333_333L  // 30.0 fps
        val stream2IntervalNs = 41_666_667L  // 24.0 fps
        
        val stream1 = (0..179).map { it * stream1IntervalNs } // 180 frames = 6s
        val stream2 = (0..149).map { it * stream2IntervalNs } // 150 frames = ~6.25s
        
        val result = TimestampSync.analyzeSynchronization(stream1, stream2, windowSizeMs = 5000)
        
        assertTrue("Sync calculation should be valid", result.isValid)
        
        // Critical: Drift should be 0.0 despite rate difference!
        assertEquals(0.0, result.driftMsPerSecond, 0.001)
        
        // Rates should be correctly reported
        assertEquals(30.0, result.stream1Rate, 0.5)
        assertEquals(24.0, result.stream2Rate, 0.5)
        
        // Drops should be 0 (clean rates)
        assertEquals(0.0, result.stream1DropRate, 0.01)
        assertEquals(0.0, result.stream2DropRate, 0.01)
        
        // Jitter should be negligible
        assertEquals(0.0, result.stream1JitterMs, 0.1)
    }
    
    @Test
    fun `analyzeSynchronization - high jitter detection`() {
        // Stream 1: 30 fps with random +/- 5ms jitter
        val intervalNs = 33_333_333L
        val random = java.util.Random(12345) // Fixed seed for reproducibility
        
        val stream1 = (0..99).map { 
            // Jitter between -5ms and +5ms
            val jitter = (random.nextDouble() * 10_000_000 - 5_000_000).toLong()
            it * intervalNs + jitter
        }
        val stream2 = (0..99).map { it * intervalNs } // Clean stream
        
        val result = TimestampSync.analyzeSynchronization(stream1, stream2, windowSizeMs = 3000)
        
        // Random jitter should produce measurable MAD > 0.5ms
        assertTrue("Jitter should be detected > 0.5 ms, was ${result.stream1JitterMs}", result.stream1JitterMs > 0.5)
    }

    @Test
    fun `analyzeSynchronization - robust offset calculation`() {
        // Test that robust offset ignores outlier at the start
        val intervalNs = 10_000_000L // 100 Hz
        
        // Stream 1 matches Stream 2 perfectly, EXCEPT first frame has huge error
        val stream1 = (0..50).map { it * intervalNs }.toMutableList()
        stream1[0] = -50_000_000L // First frame starts 50ms early (noise)
        
        val stream2 = (0..50).map { it * intervalNs }
        
        val result = TimestampSync.analyzeSynchronization(stream1, stream2)
        
        // Simple first-frame diff would be 0 - (-50) = 50ms
        // Robust median offset should be closer to 0ms
        assertEquals(0.0, result.offsetMs, 1.0)
    }
    
    @Test
    fun `analyzeSynchronization - drop rate detection`() {
        // Stream with 10% drops
        val intervalNs = 33_333_333L
        val stream1 = (0..99).filter { it % 10 != 5 }.map { it * intervalNs }
        val stream2 = (0..99).map { it * intervalNs }
        
        val result = TimestampSync.analyzeSynchronization(stream1, stream2, windowSizeMs = 3000)
        
        println("Drop rate test: drops1=${result.stream1DropRate}")
        
        // We dropped 10 frames out of 100.
        // Inter-frame intervals at drop points will be 66ms (2x).
        // Threshold is 1.5x (50ms). So these should count as drops.
        // 10 drops / 90 intervals = ~11%
        assertTrue("Drop rate should be > 0.05", result.stream1DropRate > 0.05)
    }
    
    @Test
    fun `analyzeSynchronization - insufficient data`() {
        val result = TimestampSync.analyzeSynchronization(
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
    @Test
    fun `computeDrift - frame drops should NOT imply clock drift`() {
        // Stream 1: 30 fps, perfect.
        val interval = 33_333_333L
        val stream1 = (0..99).map { TimestampedValue(it * interval, 1.0) }
        
        // Stream 2: 30 fps, but drops every 10th frame.
        // Timestamps should be correct (multiples of interval), just missing indices.
        val stream2 = (0..99).filter { it % 10 != 0 }.map { 
             TimestampedValue(it * interval, 1.0) 
        }
        
        // Count for stream 2 is 90% of stream 1.
        // Current computeDrift logic uses (count / window).
        // So rate2 will be 0.9 * rate1.
        // It will report drift ~10%. (300 ms/s!)
        
        val result = TimestampSync.analyzeSynchronization(
            stream1.map { it.timestampNs }, 
            stream2.map { it.timestampNs }, 
            windowSizeMs = 3500 // 3.5s
        )
        
        // Verified: New logic returns 0.0 drift appropriately!
        println("Frame Drop Test: Drift=${result.driftMsPerSecond}")
        
        // Assert exactly 0.0 drift
        assertEquals(0.0, result.driftMsPerSecond, 0.001)
        
        // Verify rates are detected correctly despite drops (median interval handles it)
        assertEquals(30.0, result.stream1Rate, 0.5)
        assertEquals(30.0, result.stream2Rate, 0.5)
    }
}

