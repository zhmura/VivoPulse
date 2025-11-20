package com.vivopulse.feature.processing.motion

/**
 * Computes saturation index for finger ROI.
 *
 * Detects if the sensor is saturated (clipping), which destroys the PPG signal.
 */
class FingerSaturationIndex {

    /**
     * Compute percentage of saturated pixels.
     *
     * @param lumaBuffer Luma plane buffer
     * @param width Width of the buffer
     * @param height Height of the buffer
     * @param threshold Saturation threshold (0-255), default 250
     * @return Percentage of pixels >= threshold (0.0 - 1.0)
     */
    fun computeSaturationPct(
        lumaBuffer: ByteArray,
        width: Int,
        height: Int,
        threshold: Int = 250
    ): Double {
        var saturatedCount = 0
        val totalPixels = lumaBuffer.size
        
        if (totalPixels == 0) return 0.0
        
        // Check every pixel (or subsample)
        for (i in lumaBuffer.indices) {
            val luma = lumaBuffer[i].toInt() and 0xFF
            if (luma >= threshold) {
                saturatedCount++
            }
        }
        
        return saturatedCount.toDouble() / totalPixels
    }
}
