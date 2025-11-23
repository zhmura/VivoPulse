package com.vivopulse.feature.capture

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class LumaExtractorTest {

    @Test
    fun extractAverageLuma_calculatesCorrectMean() {
        val width = 4
        val height = 4
        val buffer = ByteBuffer.allocate(width * height)
        
        // Fill with uniform value 100
        for (i in 0 until width * height) {
            buffer.put(100.toByte())
        }
        buffer.rewind()
        
        val roi = Rect(0, 0, width, height)
        val mean = LumaExtractor.extractAverageLuma(buffer, roi, width, width, height)
        
        assertEquals(100.0, mean, 0.01)
    }

    @Test
    fun extractAverageLuma_respectsRoi() {
        val width = 4
        val height = 4
        val buffer = ByteBuffer.allocate(width * height)
        
        // Fill: top-left 2x2 = 100, rest = 0
        /*
         100 100   0   0
         100 100   0   0
           0   0   0   0
           0   0   0   0
        */
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = if (x < 2 && y < 2) 100 else 0
                buffer.put(value.toByte())
            }
        }
        buffer.rewind()
        
        // Test ROI on top-left
        val roiTopLeft = Rect(0, 0, 2, 2)
        val meanTopLeft = LumaExtractor.extractAverageLuma(buffer, roiTopLeft, width, width, height)
        assertEquals(100.0, meanTopLeft, 0.01)
        
        // Test ROI on bottom-right
        val roiBottomRight = Rect(2, 2, 4, 4)
        val meanBottomRight = LumaExtractor.extractAverageLuma(buffer, roiBottomRight, width, width, height)
        assertEquals(0.0, meanBottomRight, 0.01)
        
        // Test Full Frame mean (should be 400 / 16 = 25)
        val fullRoi = Rect(0, 0, 4, 4)
        val meanFull = LumaExtractor.extractAverageLuma(buffer, fullRoi, width, width, height)
        assertEquals(25.0, meanFull, 0.01)
    }

    @Test
    fun extractCenterRegionLuma_extractsCenter() {
        val width = 10
        val height = 10
        val buffer = ByteBuffer.allocate(width * height)
        
        // Fill center 6x6 (indices 2..7) with 200, rest 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = if (x in 2..7 && y in 2..7) 200 else 0
                buffer.put(value.toByte())
            }
        }
        buffer.rewind()
        
        val mean = LumaExtractor.extractCenterRegionLuma(buffer, width, width, height)
        
        // Center region is 60% of 10 = 6x6 pixels
        // All pixels in this region are 200
        assertEquals(200.0, mean, 0.01)
    }

    @Test
    fun computeLumaVariance_calculatesVariance() {
        val width = 2
        val height = 2
        val buffer = ByteBuffer.allocate(width * height)
        
        // Values: 10, 30, 10, 30
        // Mean = 20
        // Diff^2: 100, 100, 100, 100
        // Variance = 100
        buffer.put(10.toByte())
        buffer.put(30.toByte())
        buffer.put(10.toByte())
        buffer.put(30.toByte())
        buffer.rewind()
        
        val roi = Rect(0, 0, 2, 2)
        val variance = LumaExtractor.computeLumaVariance(buffer, roi, width, width, height)
        
        assertEquals(100.0, variance, 0.01)
    }
}

