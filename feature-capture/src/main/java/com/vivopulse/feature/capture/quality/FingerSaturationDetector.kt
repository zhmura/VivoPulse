package com.vivopulse.feature.capture.quality

import java.nio.ByteBuffer
import android.graphics.Rect

/**
 * Finger saturation detector.
 * 
 * Detects clipped/saturated pixels in finger ROI (green channel),
 * which indicates excessive pressure on lens.
 */
object FingerSaturationDetector {
    
    const val SATURATION_THRESHOLD_8BIT = 250  // Pixels >250 considered saturated (out of 255)
    
    /**
     * Compute saturation percentage in ROI.
     * 
     * For YUV, Y plane saturation is a good proxy for overall saturation.
     * 
     * @param yPlane Y plane buffer
     * @param roi Region of interest
     * @param rowStride Row stride
     * @param width Frame width
     * @param height Frame height
     * @return Percentage of saturated pixels (0-100)
     */
    fun computeSaturationPercent(
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
        
        if (constrainedRoi.isEmpty) return 0.0
        
        var totalPixels = 0
        var saturatedPixels = 0
        
        for (y in constrainedRoi.top until constrainedRoi.bottom) {
            for (x in constrainedRoi.left until constrainedRoi.right) {
                val index = y * rowStride + x
                
                if (index >= 0 && index < yPlane.limit()) {
                    val lumaValue = yPlane.get(index).toInt() and 0xFF
                    totalPixels++
                    
                    if (lumaValue >= SATURATION_THRESHOLD_8BIT) {
                        saturatedPixels++
                    }
                }
            }
        }
        
        return if (totalPixels > 0) {
            (saturatedPixels.toDouble() / totalPixels) * 100.0
        } else {
            0.0
        }
    }
    
    /**
     * Compute saturation for full frame (finger camera).
     */
    fun computeFullFrameSaturation(
        yPlane: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ): Double {
        return computeSaturationPercent(
            yPlane,
            Rect(0, 0, width, height),
            rowStride,
            width,
            height
        )
    }
    
    /**
     * Compute saturation for center region (60% of frame).
     */
    fun computeCenterRegionSaturation(
        yPlane: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ): Double {
        val centerWidth = (width * 0.6).toInt()
        val centerHeight = (height * 0.6).toInt()
        val left = (width - centerWidth) / 2
        val top = (height - centerHeight) / 2
        
        val centerRoi = Rect(left, top, left + centerWidth, top + centerHeight)
        
        return computeSaturationPercent(yPlane, centerRoi, rowStride, width, height)
    }
}

