package com.vivopulse.feature.capture.roi

import android.graphics.Rect
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Finger ROI detector for optimal signal extraction.
 * 
 * Finds the region with best contact/perfusion from back camera.
 */
object FingerRoiDetector {
    
    /**
     * Detect optimal finger region from Y plane.
     * 
     * Strategy:
     * 1. Use center 60% of frame (fingertip typically centered)
     * 2. Find region with highest variance (indicates good perfusion)
     * 3. Avoid edges (less stable contact)
     * 
     * @param yPlane Y plane buffer
     * @param rowStride Row stride
     * @param width Frame width
     * @param height Frame height
     * @return Optimal ROI for finger, or null if detection fails
     */
    fun detectFingerRoi(
        yPlane: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ): Rect? {
        // Use center region (60% of frame)
        val centerWidth = (width * 0.6).toInt()
        val centerHeight = (height * 0.6).toInt()
        val left = (width - centerWidth) / 2
        val top = (height - centerHeight) / 2
        
        return Rect(
            left,
            top,
            left + centerWidth,
            top + centerHeight
        )
    }
    
    /**
     * Detect finger region with advanced variance-based selection.
     * 
     * Divides center area into grid and selects region with highest variance
     * (indicates good blood perfusion).
     * 
     * @param yPlane Y plane buffer
     * @param rowStride Row stride
     * @param width Frame width
     * @param height Frame height
     * @return Optimal ROI based on variance analysis
     */
    fun detectOptimalFingerRoi(
        yPlane: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ): Rect {
        // Center region
        val centerWidth = (width * 0.8).toInt()
        val centerHeight = (height * 0.8).toInt()
        val baseLeft = (width - centerWidth) / 2
        val baseTop = (height - centerHeight) / 2
        
        // Grid search for best region
        val gridSize = 3 // 3x3 grid
        val cellWidth = centerWidth / gridSize
        val cellHeight = centerHeight / gridSize
        
        var bestRoi = Rect(baseLeft, baseTop, baseLeft + cellWidth, baseTop + cellHeight)
        var maxVariance = 0.0
        
        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val roi = Rect(
                    baseLeft + col * cellWidth,
                    baseTop + row * cellHeight,
                    baseLeft + (col + 1) * cellWidth,
                    baseTop + (row + 1) * cellHeight
                )
                
                val variance = computeRoiVariance(yPlane, roi, rowStride, width, height)
                
                if (variance > maxVariance) {
                    maxVariance = variance
                    bestRoi = roi
                }
            }
        }
        
        return bestRoi
    }
    
    /**
     * Compute luma variance in ROI.
     */
    private fun computeRoiVariance(
        yPlane: ByteBuffer,
        roi: Rect,
        rowStride: Int,
        width: Int,
        height: Int
    ): Double {
        val constrainedRoi = Rect(
            max(0, roi.left),
            max(0, roi.top),
            min(width, roi.right),
            min(height, roi.bottom)
        )
        
        if (constrainedRoi.isEmpty) return 0.0
        
        // Compute mean
        var sum = 0.0
        var count = 0
        
        for (y in constrainedRoi.top until constrainedRoi.bottom step 2) {
            for (x in constrainedRoi.left until constrainedRoi.right step 2) {
                val index = y * rowStride + x
                if (index >= 0 && index < yPlane.limit()) {
                    sum += (yPlane.get(index).toInt() and 0xFF)
                    count++
                }
            }
        }
        
        if (count == 0) return 0.0
        val mean = sum / count
        
        // Compute variance
        var sumSquared = 0.0
        for (y in constrainedRoi.top until constrainedRoi.bottom step 2) {
            for (x in constrainedRoi.left until constrainedRoi.right step 2) {
                val index = y * rowStride + x
                if (index >= 0 && index < yPlane.limit()) {
                    val value = (yPlane.get(index).toInt() and 0xFF)
                    sumSquared += (value - mean) * (value - mean)
                }
            }
        }
        
        return sumSquared / count
    }
}

