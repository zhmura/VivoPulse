package com.vivopulse.feature.capture.util

import org.junit.Assert.*
import org.junit.Test

class BufferPoolTest {
    
    @Test
    fun `pool provides buffers of correct size`() {
        val pool = BufferPool(bufferSize = 1024, poolSize = 5)
        
        val buffer = pool.acquire()
        
        assertEquals("Buffer should have correct size", 1024, buffer.size)
    }
    
    @Test
    fun `pool reuses released buffers`() {
        val pool = BufferPool(bufferSize = 1024, poolSize = 5)
        
        // Acquire all buffers to empty the pool
        val buffers = mutableListOf<ByteArray>()
        repeat(5) { buffers.add(pool.acquire()) }
        
        assertEquals("Pool should be empty", 0, pool.getStats().availableBuffers)
        
        // Release one
        pool.release(buffers[0])
        
        assertEquals("Pool should have 1 buffer after release", 1, pool.getStats().availableBuffers)
        
        // Acquire again - should get the released buffer
        val reused = pool.acquire()
        assertSame("Should reuse released buffer", buffers[0], reused)
    }
    
    @Test
    fun `pool handles exhaustion gracefully`() {
        val pool = BufferPool(bufferSize = 1024, poolSize = 2)
        
        @Suppress("UNUSED_VARIABLE")
        val buf1 = pool.acquire()
        @Suppress("UNUSED_VARIABLE")
        val buf2 = pool.acquire()
        val buf3 = pool.acquire() // Pool exhausted, should allocate new
        
        assertNotNull("Should allocate new buffer when pool exhausted", buf3)
        assertEquals("New buffer should have correct size", 1024, buf3.size)
        
        val stats = pool.getStats()
        assertTrue("Should have allocated more than pool size", stats.totalAllocated > stats.poolSize)
    }
    
    @Test
    fun `pool statistics are accurate`() {
        val pool = BufferPool(bufferSize = 512, poolSize = 5)
        
        val initialStats = pool.getStats()
        assertEquals("Initial pool size", 5, initialStats.poolSize)
        assertEquals("Initial available", 5, initialStats.availableBuffers)
        assertEquals("Initial allocated", 5, initialStats.totalAllocated)
        
        val buf = pool.acquire()
        val afterAcquire = pool.getStats()
        assertEquals("Available after acquire", 4, afterAcquire.availableBuffers)
        
        pool.release(buf)
        val afterRelease = pool.getStats()
        assertEquals("Available after release", 5, afterRelease.availableBuffers)
    }
    
    @Test
    fun `pool utilization calculation`() {
        val pool = BufferPool(bufferSize = 1024, poolSize = 10)
        
        repeat(5) { pool.acquire() }
        
        val stats = pool.getStats()
        assertEquals("Utilization should be 50%", 50.0, stats.getUtilization(), 0.1)
    }
    
    @Test
    fun `pool health check`() {
        val pool = BufferPool(bufferSize = 1024, poolSize = 5)
        
        assertTrue("Initial pool should be healthy", pool.getStats().isHealthy())
        
        // Exhaust pool multiple times
        repeat(10) { pool.acquire() }
        
        val stats = pool.getStats()
        assertFalse("Pool with excessive allocations should be unhealthy", stats.isHealthy())
    }
    
    @Test
    fun `pool rejects wrong-sized buffers`() {
        val pool = BufferPool(bufferSize = 1024, poolSize = 5)
        
        val wrongBuffer = ByteArray(512) // Wrong size
        pool.release(wrongBuffer)
        
        val stats = pool.getStats()
        assertEquals("Should not accept wrong-sized buffer", 5, stats.availableBuffers)
    }
}

