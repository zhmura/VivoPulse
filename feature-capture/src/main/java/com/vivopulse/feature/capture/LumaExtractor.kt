package com.vivopulse.feature.capture

import android.graphics.Rect
import java.nio.ByteBuffer

/**
 * Luma (brightness) extraction utilities for PPG signal generation.
 * 
 * Extracts average luma from YUV_420_888 frames for rPPG analysis.
 */
object LumaExtractor {
    
    /**
     * Extract average luma from a region of interest (ROI).
     * 
     * Uses Y plane from YUV_420_888 format directly (no RGB conversion needed).
     * 
     * @param yPlane Y plane buffer from ImageProxy
     * @param roi Region of interest rectangle
     * @param rowStride Row stride of the Y plane
     * @param width Frame width
     * @param height Frame height
     * @return Average luma value (0-255)
     */
    fun extractAverageLuma(
        yPlane: ByteBuffer,
        roi: Rect,
        rowStride: Int,
        width: Int,
        height: Int
    ): Double {
        // Constrain ROI to frame boundaries
        val constrainedRoi = Rect(
            maxOf(0, roi.left),
            maxOf(0, roi.top),
            minOf(width, roi.right),
            minOf(height, roi.bottom)
        )
        
        if (constrainedRoi.isEmpty) {
            return 0.0
        }
        
        var sum = 0.0
        var count = 0
        
        // Extract luma from ROI
        for (y in constrainedRoi.top until constrainedRoi.bottom) {
            for (x in constrainedRoi.left until constrainedRoi.right) {
                val index = y * rowStride + x
                
                if (index >= 0 && index < yPlane.limit()) {
                    val lumaValue = yPlane.get(index).toInt() and 0xFF
                    sum += lumaValue
                    count++
                }
            }
        }
        
        return if (count > 0) sum / count else 0.0
    }
    
    /**
     * Extract average luma from entire frame.
     * 
     * Used for finger camera when no specific ROI is defined.
     * 
     * @param yPlane Y plane buffer
     * @param rowStride Row stride
     * @param width Frame width
     * @param height Frame height
     * @return Average luma value (0-255)
     */
    fun extractFullFrameLuma(
        yPlane: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ): Double {
        return extractAverageLuma(
            yPlane,
            Rect(0, 0, width, height),
            rowStride,
            width,
            height
        )
    }
    
    /**
     * Extract luma from center region (for finger camera).
     * 
     * Uses central 60% of frame to focus on fingertip area.
     * 
     * @param yPlane Y plane buffer
     * @param rowStride Row stride
     * @param width Frame width
     * @param height Frame height
     * @return Average luma from center region
     */
    fun extractCenterRegionLuma(
        yPlane: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ): Double {
        val centerWidth = (width * 0.6).toInt()
        val centerHeight = (height * 0.6).toInt()
        val left = (width - centerWidth) / 2
        val top = (height - centerHeight) / 2
        
        val centerRoi = Rect(
            left,
            top,
            left + centerWidth,
            top + centerHeight
        )
        
        return extractAverageLuma(yPlane, centerRoi, rowStride, width, height)
    }
    
    /**
     * Compute variance of luma in ROI (for quality assessment).
     * 
     * @param yPlane Y plane buffer
     * @param roi Region of interest
     * @param rowStride Row stride
     * @param width Frame width
     * @param height Frame height
     * @return Luma variance
     */
    fun computeLumaVariance(
        yPlane: ByteBuffer,
        roi: Rect,
        rowStride: Int,
        width: Int,
        height: Int
    ): Double {
        val constrainedRoi = Rect(
            maxOf(0, roi.left),
            maxOf(0, roi.top),
            minOf(width, roi.right),
            minOf(height, roi.bottom)
        )
        
        if (constrainedRoi.isEmpty) {
            return 0.0
        }
        
        // First pass: mean
        val mean = extractAverageLuma(yPlane, constrainedRoi, rowStride, width, height)
        
        // Second pass: variance
        var sumSquaredDiff = 0.0
        var count = 0
        
        for (y in constrainedRoi.top until constrainedRoi.bottom) {
            for (x in constrainedRoi.left until constrainedRoi.right) {
                val index = y * rowStride + x
                
                if (index >= 0 && index < yPlane.limit()) {
                    val lumaValue = yPlane.get(index).toInt() and 0xFF
                    sumSquaredDiff += (lumaValue - mean) * (lumaValue - mean)
                    count++
                }
            }
        }
        
        return if (count > 0) sumSquaredDiff / count else 0.0
    }
}

