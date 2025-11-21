package com.vivopulse.signal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RingBufferDoubleTest {

    @Test
    fun snapshotReturnsChronologicalOrder() {
        val buffer = RingBufferDouble(8)
        val startNs = 1_000_000_000L
        repeat(6) { idx ->
            buffer.add(idx.toDouble(), startNs + idx * 10_000_000L)
        }
        val window = buffer.snapshot(40_000_000L)!!
        assertArrayEquals(doubleArrayOf(2.0, 3.0, 4.0, 5.0), window.values, 1e-6)
        assertEquals(4, window.values.size)
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


