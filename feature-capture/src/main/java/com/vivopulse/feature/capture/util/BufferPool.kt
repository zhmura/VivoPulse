package com.vivopulse.feature.capture.util

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Memory pool for reusable byte buffers.
 * 
 * Eliminates per-frame allocations by pre-allocating a pool of buffers
 * and reusing them across camera frames.
 * 
 * Thread-safe for concurrent camera access.
 */
class BufferPool(
    private val bufferSize: Int,
    private val poolSize: Int = 10
) {
    private val tag = "BufferPool"
    private val pool = ConcurrentLinkedQueue<ByteArray>()
    private var allocatedCount = 0
    
    init {
        // Pre-allocate pool
        repeat(poolSize) {
            pool.offer(ByteArray(bufferSize))
            allocatedCount++
        }
        // Log only if Android Log is available (not in unit tests)
        try {
            Log.d(tag, "Buffer pool initialized: $poolSize buffers of $bufferSize bytes each")
        } catch (e: RuntimeException) {
            // Android Log not available in unit tests, skip
        }
    }
    
    /**
     * Acquire a buffer from the pool.
     * 
     * If pool is empty, allocates a new buffer (logged as pool miss).
     * 
     * @return ByteArray buffer (may be reused, caller must not retain reference)
     */
    fun acquire(): ByteArray {
        val buffer = pool.poll()
        
        return if (buffer != null) {
            buffer
        } else {
            // Pool exhausted, allocate new (should be rare)
            allocatedCount++
            try {
                Log.w(tag, "Pool miss, allocated new buffer (total: $allocatedCount)")
            } catch (e: RuntimeException) {
                // Android Log not available in unit tests
            }
            ByteArray(bufferSize)
        }
    }
    
    /**
     * Return a buffer to the pool for reuse.
     * 
     * @param buffer Buffer to return (must match pool buffer size)
     */
    fun release(buffer: ByteArray) {
        if (buffer.size != bufferSize) {
            try {
                Log.w(tag, "Buffer size mismatch: expected $bufferSize, got ${buffer.size}")
            } catch (e: RuntimeException) {
                // Android Log not available in unit tests
            }
            return
        }
        
        // Only return to pool if we haven't exceeded pool size significantly
        if (pool.size < poolSize * 2) {
            pool.offer(buffer)
        }
        // else: let GC collect excess buffers
    }
    
    /**
     * Get pool statistics.
     */
    fun getStats(): PoolStats {
        return PoolStats(
            poolSize = poolSize,
            availableBuffers = pool.size,
            totalAllocated = allocatedCount,
            bufferSize = bufferSize
        )
    }
    
    /**
     * Clear the pool (for cleanup).
     */
    fun clear() {
        pool.clear()
        try {
            Log.d(tag, "Buffer pool cleared")
        } catch (e: RuntimeException) {
            // Android Log not available in unit tests
        }
    }
}

/**
 * Buffer pool statistics.
 */
data class PoolStats(
    val poolSize: Int,
    val availableBuffers: Int,
    val totalAllocated: Int,
    val bufferSize: Int
) {
    /**
     * Get pool utilization percentage.
     */
    fun getUtilization(): Double {
        return ((poolSize - availableBuffers).toDouble() / poolSize) * 100.0
    }
    
    /**
     * Check if pool is healthy (no excessive misses).
     */
    fun isHealthy(): Boolean {
        return totalAllocated <= poolSize * 1.5
    }
}

