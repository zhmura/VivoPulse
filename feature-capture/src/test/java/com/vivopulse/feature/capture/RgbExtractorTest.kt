package com.vivopulse.feature.capture

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class RgbExtractorTest {

    @Test
    fun `extractAverageRgb computes correct rgb from gray yuv`() {
        val width = 2
        val height = 2
        
        val yBuffer = createByteBuffer(width * height, 128.toByte())
        val uBuffer = createByteBuffer(width * height / 4, 128.toByte())
        val vBuffer = createByteBuffer(width * height / 4, 128.toByte())
        
        val image = mockImage(width, height, yBuffer, uBuffer, vBuffer)
        val roi = Rect(0, 0, 2, 2)
        
        val result = RgbExtractor.extractAverageRgb(image, roi)
        
        assertEquals(128.0, result!!.r, 0.1)
        assertEquals(128.0, result.g, 0.1)
        assertEquals(128.0, result.b, 0.1)
    }

    @Test
    fun `extractAverageRgb computes redish pixel`() {
        val width = 2
        val height = 2
        
        val yBuffer = createByteBuffer(4, 100.toByte())
        val uBuffer = createByteBuffer(1, 100.toByte())
        val vBuffer = createByteBuffer(1, 200.toByte())
        
        val image = mockImage(width, height, yBuffer, uBuffer, vBuffer)
        val roi = Rect(0, 0, 2, 2)
        
        val result = RgbExtractor.extractAverageRgb(image, roi)
        
        assertEquals(200.9, result!!.r, 1.0)
        assertEquals(58.2, result.g, 1.0)
        assertEquals(50.4, result.b, 1.0)
    }

    private fun createByteBuffer(size: Int, value: Byte): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(size)
        for (i in 0 until size) {
            buffer.put(value)
        }
        buffer.flip()
        return buffer
    }
    
    private fun mockImage(
        w: Int, h: Int,
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer
    ): ImageProxy {
        val image = mockk<ImageProxy>(relaxed = true)
        every { image.width } returns w
        every { image.height } returns h
        
        val p0 = mockk<ImageProxy.PlaneProxy>(relaxed = true)
        every { p0.buffer } returns y
        every { p0.rowStride } returns w
        every { p0.pixelStride } returns 1
        
        val p1 = mockk<ImageProxy.PlaneProxy>(relaxed = true)
        every { p1.buffer } returns u
        every { p1.rowStride } returns w/2
        every { p1.pixelStride } returns 1

        val p2 = mockk<ImageProxy.PlaneProxy>(relaxed = true)
        every { p2.buffer } returns v
        every { p2.rowStride } returns w/2
        every { p2.pixelStride } returns 1
        
        every { image.planes } returns arrayOf(p0, p1, p2)
        return image
    }
}
