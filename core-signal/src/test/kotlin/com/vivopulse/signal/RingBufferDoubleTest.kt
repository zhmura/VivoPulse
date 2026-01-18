package com.vivopulse.signal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RingBufferDoubleTest {

    @Test
    fun snapshotReturnsChronologicalOrder() {
        val buffer = RingBufferDouble(8)
        val startNs = 1_000_000_000L
        // Add 6 samples: 0,1,2,3,4,5 at timestamps 0ms,10ms,20ms,30ms,40ms,50ms
        repeat(6) { idx ->
            buffer.add(idx.toDouble(), startNs + idx * 10_000_000L)
        }
        // Request 40ms window from newest (50ms)
        // Cutoff = 50ms - 40ms = 10ms
        // Samples >= 10ms: 1(10ms),2(20ms),3(30ms),4(40ms),5(50ms) = 5 samples
        val window = buffer.snapshot(40_000_000L)!!
        // Should return samples from index 1 to 5 (chronological order)
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), window.values, 1e-6)
        assertEquals(5, window.values.size)
    }

    @Test
    fun capacityDoesNotGrowBeyondLimit() {
        val buffer = RingBufferDouble(10)
        val startNs = 0L
        repeat(25) { idx ->
            buffer.add(idx.toDouble(), startNs + idx * 1_000_000L)
        }
        assertEquals(10, buffer.size())
        val window = buffer.snapshot(50_000_000L)!!
        assertEquals(10, window.values.size)
    }
}


