package com.vivopulse.feature.capture

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.ByteBuffer

class RgbExtractorTest {

    @Test
    fun `extractAverageRgb computes correct rgb from gray yuv`() {
        // Gray pixel: Y=128, U=128, V=128 (offset by 128 for signed byte -> U=0, V=0)
        // R = Y + 1.402*V = 128 + 0 = 128
        // G = Y - ... = 128
        // B = Y + 1.772*U = 128
        
        val width = 2
        val height = 2
        
        val yBuffer = createByteBuffer(width * height, 128.toByte())
        val uBuffer = createByteBuffer(width * height / 4, 128.toByte()) // 2x2 -> 1 UV pixel
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
        // Red pixel approximation: Y=76, U=85 (approx -43), V=255 (approx +127)
        // Let's use simpler math values:
        // Y=100
        // U=100 (diff -28)
        // V=200 (diff +72)
        
        // R = 100 + 1.402 * 72 = 100 + 100.944 = 200.9
        // G = 100 - 0.344*(-28) - 0.714*72 = 100 + 9.6 - 51.4 = 58.2
        // B = 100 + 1.772*(-28) = 100 - 49.6 = 50.4
        
        val width = 2
        val height = 2
        
        val yBuffer = createByteBuffer(4, 100.toByte())
        val uBuffer = createByteBuffer(1, 100.toByte())
        val vBuffer = createByteBuffer(1, 200.toByte()) // 200 is -56 in signed byte? No, Java byte is -128..127.
        // 200 unsigned byte is -56 signed byte.
        // Wait, standard Buffer.get() returns byte.
        // In RgbExtractor: val uVal = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
        // So putting 100.toByte() -> 100 -> uVal = -28.
        // Putting 200.toByte() -> -56 -> 0xFF -> 200 -> vVal = 72.
        
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
        val image = mock(ImageProxy::class.java)
        `when`(image.width).thenReturn(w)
        `when`(image.height).thenReturn(h)
        
        val p0 = mock(ImageProxy.PlaneProxy::class.java)
        `when`(p0.buffer).thenReturn(y)
        `when`(p0.rowStride).thenReturn(w)
        `when`(p0.pixelStride).thenReturn(1)
        
        val p1 = mock(ImageProxy.PlaneProxy::class.java)
        `when`(p1.buffer).thenReturn(u)
        `when`(p1.rowStride).thenReturn(w/2) // usually width for stride even if subsampled, but depends on packing. Simplest case: w/2
        `when`(p1.pixelStride).thenReturn(1)

        val p2 = mock(ImageProxy.PlaneProxy::class.java)
        `when`(p2.buffer).thenReturn(v)
        `when`(p2.rowStride).thenReturn(w/2)
        `when`(p2.pixelStride).thenReturn(1)
        
        `when`(image.planes).thenReturn(arrayOf(p0, p1, p2))
        return image
    }
}
