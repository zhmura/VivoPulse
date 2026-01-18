package com.vivopulse.feature.capture.model

import com.vivopulse.feature.capture.RgbData
import java.nio.ByteBuffer

/**
 * Camera source type.
 */
enum class Source {
    FACE,    // Front camera
    FINGER   // Back camera
}

/**
 * Represents a single camera frame with YUV data.
 *
 * @property source Camera source (FACE or FINGER)
 * @property timestampNs Frame timestamp in nanoseconds from Image.timestamp
 * @property width Frame width in pixels
 * @property height Frame height in pixels
 * @property yuvPlanes YUV_420_888 plane data (Y, U, V)
 * @property captureTimestamp System timestamp when frame was captured
 * @property faceLuma Average luma from face ROI (if source=FACE and ROI available)
 * @property fingerLuma Average luma from finger region (if source=FINGER)
 */
data class Frame(
    val source: Source,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val yuvPlanes: List<ByteBuffer>,
    val captureTimestamp: Long = System.currentTimeMillis(),
    val faceLuma: Double? = null,
    val fingerLuma: Double? = null,
    val faceRgb: RgbData? = null,
    val fingerRgb: RgbData? = null,
    // Detailed metrics for export/analysis
    val faceMotionRms: Double? = null,
    val fingerSaturationPct: Double? = null,
    val imuRmsG: Double? = null,
    val faceRoiRect: android.graphics.Rect? = null
) {
    /**
     * Get frame size in bytes (approximate).
     */
    fun getSizeBytes(): Int {
        return yuvPlanes.sumOf { it.remaining() }
    }

    /**
     * Get luma value for this frame.
     */
    fun getLuma(): Double? {
        return when (source) {
            Source.FACE -> faceLuma ?: faceRgb?.g
            Source.FINGER -> fingerLuma ?: fingerRgb?.g
        }
    }
    
    /**
     * Check if luma has been extracted.
     */
    fun hasLuma(): Boolean = getLuma() != null
    
    /**
     * Create a copy of the frame with deep-copied buffers.
     * Required for storing frames as buffers are reused by camera.
     */
    fun deepCopy(): Frame {
        if (yuvPlanes.isEmpty()) {
            return copy()
        }
        val copiedPlanes = yuvPlanes.map { originalBuffer ->
            val dup = originalBuffer.duplicate()
            val bytes = ByteArray(dup.remaining())
            dup.get(bytes)
            ByteBuffer.wrap(bytes)
        }
        return copy(
            yuvPlanes = copiedPlanes,
            faceLuma = faceLuma,
            fingerLuma = fingerLuma,
            faceRgb = faceRgb,
            fingerRgb = fingerRgb,
            faceMotionRms = faceMotionRms,
            fingerSaturationPct = fingerSaturationPct,
            imuRmsG = imuRmsG,
            faceRoiRect = faceRoiRect
        )
    }
}

