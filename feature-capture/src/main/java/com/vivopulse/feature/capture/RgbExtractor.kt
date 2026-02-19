package com.vivopulse.feature.capture

import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Data class holding average RGB values.
 */
data class RgbData(
    val r: Double,
    val g: Double,
    val b: Double
)

/**
 * Utility for extracting average RGB from YUV 420 frames.
 *
 * Performs on-the-fly YUV->RGB conversion for pixels within an ROI.
 * Optimized to avoid full frame conversion or Bitmap creation.
 */
object RgbExtractor {

    /**
     * Extract average RGB from an ImageProxy (YUV_420_888).
     *
     * @param image Camera image proxy
     * @param roi Region of interest
     * @return Average RgbData or null if extraction fails
     */
    fun extractAverageRgb(
        image: ImageProxy,
        roi: Rect
    ): RgbData? {
        val width = image.width
        val height = image.height

        // Constrain ROI
        val constrainedRoi = Rect(
            max(0, roi.left),
            max(0, roi.top),
            min(width, roi.right),
            min(height, roi.bottom)
        )

        if (constrainedRoi.isEmpty) return null

        val planes = image.planes
        if (planes.size < 3) return null

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride // Usually same for U and V
        val uvPixelStride = uPlane.pixelStride // Usually 1 or 2

        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0

        // Iterate over ROI
        // To optimize, we can step by 1 or 2 pixels if ROI is large,
        // but for accurate averaging 1-pixel step is safer unless performance hits.
        // Let's stick to step 1 for now, or step 2 for larger ROIs to be safe on CPU.
        val step = 1

        for (y in constrainedRoi.top until constrainedRoi.bottom step step) {
            for (x in constrainedRoi.left until constrainedRoi.right step step) {
                
                // Y index
                val yIndex = y * yRowStride + x
                
                // UV index (subsampled 2x2)
                val uvX = x / 2
                val uvY = y / 2
                val uvIndex = uvY * uvRowStride + (uvX * uvPixelStride)

                // Get YUV values safely
                // Note: Buffers are direct, so we need care with bounds if not perfectly standard
                if (yIndex < yBuffer.limit() && uvIndex < uBuffer.limit() && uvIndex < vBuffer.limit()) {
                    // Y is unsigned byte 0-255
                    val yVal = yBuffer.get(yIndex).toInt() and 0xFF
                    
                    // U and V are unsigned byte, convert to signed offset from 128
                    // V corresponds to Cr, U corresponds to Cb
                    val uVal = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                    val vVal = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                    // Convert to RGB
                    // Integer math is faster
                    // R = Y + 1.402V
                    // G = Y - 0.344136U - 0.714136V
                    // B = Y + 1.772U
                    
                    val r = (yVal + 1.402 * vVal).coerceIn(0.0, 255.0)
                    val g = (yVal - 0.344136 * uVal - 0.714136 * vVal).coerceIn(0.0, 255.0)
                    val b = (yVal + 1.772 * uVal).coerceIn(0.0, 255.0)

                    sumR += r
                    sumG += g
                    sumB += b
                    count++
                }
            }
        }

        return if (count > 0) {
            RgbData(
                r = sumR / count,
                g = sumG / count,
                b = sumB / count
            )
        } else {
            null
        }
    }

    // ---- P0-B: Lightweight extraction fast paths ----

    /**
     * Fast luma-only extraction — Y channel only, no color conversion.
     * ~3-5x faster than full RGB for finger PPG where red channel dominates via torch.
     * Uses 2x spatial subsampling for additional speedup.
     */
    fun extractAverageLuma(image: ImageProxy, roi: Rect): Double? {
        val width = image.width
        val height = image.height

        val constrained = Rect(
            max(0, roi.left),
            max(0, roi.top),
            min(width, roi.right),
            min(height, roi.bottom)
        )
        if (constrained.isEmpty) return null

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride

        var sum = 0L
        var count = 0

        // 2x spatial subsampling — sufficient for average signal extraction
        for (y in constrained.top until constrained.bottom step 2) {
            for (x in constrained.left until constrained.right step 2) {
                val idx = y * yRowStride + x
                if (idx < yBuffer.limit()) {
                    sum += yBuffer.get(idx).toInt() and 0xFF
                    count++
                }
            }
        }

        return if (count > 0) sum.toDouble() / count else null
    }

    /**
     * Green-proxy extraction from YUV — avoids full RGB matrix.
     * G ≈ Y - 0.344*U - 0.714*V (dominant rPPG channel for face).
     * ~40% faster than full RGB (skips R and B computation).
     * Uses 2x spatial subsampling.
     */
    fun extractAverageGreenProxy(image: ImageProxy, roi: Rect): Double? {
        val width = image.width
        val height = image.height

        val constrained = Rect(
            max(0, roi.left),
            max(0, roi.top),
            min(width, roi.right),
            min(height, roi.bottom)
        )
        if (constrained.isEmpty) return null

        val planes = image.planes
        if (planes.size < 3) return null

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        var sumG = 0.0
        var count = 0

        // Step by 2 for spatial subsampling (matches chroma subsampling)
        for (y in constrained.top until constrained.bottom step 2) {
            for (x in constrained.left until constrained.right step 2) {
                val yIndex = y * yRowStride + x
                val uvX = x / 2
                val uvY = y / 2
                val uvIndex = uvY * uvRowStride + (uvX * uvPixelStride)

                if (yIndex < yBuffer.limit() && uvIndex < uBuffer.limit() && uvIndex < vBuffer.limit()) {
                    val yVal = yBuffer.get(yIndex).toInt() and 0xFF
                    val uVal = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                    val vVal = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128

                    // Green channel only: G = Y - 0.344*U - 0.714*V
                    val g = (yVal - 0.344136 * uVal - 0.714136 * vVal).coerceIn(0.0, 255.0)
                    sumG += g
                    count++
                }
            }
        }

        return if (count > 0) sumG / count else null
    }

    /**
     * V-channel (Cr) extraction — approximates Red chrominance.
     * 
     * Highly effective for Finger PPG under white flash (Red-dominated).
     * Faster than GreenProxy (no linear combination) and much faster than RGB.
     * Returns 0-255 range (128 is neutral). High values = Red.
     */
    fun extractAverageVChannel(image: ImageProxy, roi: Rect): Double? {
        val width = image.width
        val height = image.height

        val constrained = Rect(
            max(0, roi.left),
            max(0, roi.top),
            min(width, roi.right),
            min(height, roi.bottom)
        )
        if (constrained.isEmpty) return null

        val vPlane = image.planes[2]
        val vBuffer = vPlane.buffer
        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride

        var sumV = 0L
        var count = 0

        // Step by 2 for spatial subsampling (matches chroma subsampling 4:2:0)
        for (y in constrained.top until constrained.bottom step 2) {
            for (x in constrained.left until constrained.right step 2) {
                
                val uvX = x / 2
                val uvY = y / 2
                val uvIndex = uvY * uvRowStride + (uvX * uvPixelStride)

                if (uvIndex < vBuffer.limit()) {
                    // Extract raw V (Cr) byte as 0-255 unsigned
                    val vVal = vBuffer.get(uvIndex).toInt() and 0xFF
                    sumV += vVal
                    count++
                }
            }
        }

        return if (count > 0) sumV.toDouble() / count else null
    }
}
