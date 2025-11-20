package com.vivopulse.feature.processing.motion

import kotlin.math.sqrt

/**
 * Computes motion index for face ROI using optical flow proxy.
 *
 * Uses a simplified block-matching or difference approach to estimate
 * pixel motion RMS within the face region.
 */
class FaceMotionIndex {

    /**
     * Compute motion RMS in pixels per frame.
     *
     * @param currentFrame Current frame luminance buffer (or ROI crop)
     * @param previousFrame Previous frame luminance buffer
     * @param width Width of the frame/ROI
     * @param height Height of the frame/ROI
     * @return RMS motion in pixels
     */
    fun computeMotionRms(
        currentFrame: ByteArray,
        previousFrame: ByteArray,
        width: Int,
        height: Int
    ): Double {
        if (currentFrame.size != previousFrame.size) return 0.0
        
        var sumSquaredDiff = 0.0
        var count = 0
        
        // Simple pixel difference as a lightweight proxy for optical flow magnitude
        // In a real implementation, we might use Lucas-Kanade on keypoints
        // or dense optical flow on a downscaled image.
        // Here we use Mean Squared Difference (MSD) which correlates with motion.
        
        // Subsample for performance
        val step = 4 
        
        for (i in 0 until currentFrame.size step step) {
            val diff = (currentFrame[i].toInt() and 0xFF) - (previousFrame[i].toInt() and 0xFF)
            sumSquaredDiff += diff * diff
            count++
        }
        
        if (count == 0) return 0.0
        
        // Normalize to roughly pixel-shift scale (heuristic)
        // A pure pixel difference isn't exactly "pixels of motion", 
        // but it's a monotonic metric we can threshold.
        // To get closer to "pixels", we'd need spatial gradients.
        // For now, we return a normalized metric.
        
        return sqrt(sumSquaredDiff / count) / 10.0 // Scaling factor to map to approx "pixels" range 0..5+
    }
}
