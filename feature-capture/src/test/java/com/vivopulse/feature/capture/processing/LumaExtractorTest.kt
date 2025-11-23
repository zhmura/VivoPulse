package com.vivopulse.feature.capture.processing

import android.graphics.Rect
import com.vivopulse.feature.capture.LumaExtractor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
class LumaExtractorTest {

    @Test
    fun `extractAverageLuma calculates correct average for ROI`() {
        // 4x4 image, Y-plane only
        // 10 10 10 10
        // 10 20 20 10
        // 10 20 20 10
        // 10 10 10 10
        
        val width = 4
        val height = 4
        val data = byteArrayOf(
            10, 10, 10, 10,
            10, 20, 20, 10,
            10, 20, 20, 10,
            10, 10, 10, 10
        )
        val buffer = ByteBuffer.wrap(data)
        
        // ROI covering the center 2x2 area (values 20)
        val roi = Rect(1, 1, 3, 3) // left, top, right, bottom (exclusive)
        
        val average = LumaExtractor.extractAverageLuma(
            yPlane = buffer,
            roi = roi,
            rowStride = width, // Stride equals width for packed buffer
            width = width,
            height = height
        )
        
        assertEquals(20.0, average, 0.01)
    }

    @Test
    fun `extractAverageLuma handles rowStride larger than width`() {
        // 2x2 ROI in a 4x4 image with stride 5 (padding 1 byte)
        // 10 10 10 10 X
        // 10 20 20 10 X
        // 10 20 20 10 X
        // 10 10 10 10 X
        
        val width = 4
        val height = 4
        val stride = 5
        val data = byteArrayOf(
            10, 10, 10, 10, 0,
            10, 20, 20, 10, 0,
            10, 20, 20, 10, 0,
            10, 10, 10, 10, 0
        )
        val buffer = ByteBuffer.wrap(data)
        
        val roi = Rect(1, 1, 3, 3)
        
        val average = LumaExtractor.extractAverageLuma(
            yPlane = buffer,
            roi = roi,
            rowStride = stride,
            width = width,
            height = height
        )
        
        assertEquals(20.0, average, 0.01)
    }
    
    @Test
    fun `extractAverageLuma returns 0 for empty ROI`() {
        val buffer = ByteBuffer.allocate(16)
        val roi = Rect(0, 0, 0, 0)
        
        val average = LumaExtractor.extractAverageLuma(
            yPlane = buffer,
            roi = roi,
            rowStride = 4,
            width = 4,
            height = 4
        )
        
        assertEquals(0.0, average, 0.01)
    }
}
