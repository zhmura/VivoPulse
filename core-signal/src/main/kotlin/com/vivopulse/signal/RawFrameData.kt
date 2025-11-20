package com.vivopulse.signal

/**
 * Region of Interest (ROI) coordinates.
 * Platform-independent representation of a rectangle.
 */
data class Roi(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top
}

/**
 * Raw frame data for signal processing.
 * Contains the Y (Luma) plane and ROI information.
 * U/V planes are omitted for efficiency as they are rarely used for rPPG.
 */
data class RawFrameData(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val yPlane: ByteArray,
    val faceRoi: Roi? = null,
    val fingerRoi: Roi? = null,
    val imuData: Any? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RawFrameData

        if (timestampNs != other.timestampNs) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (!yPlane.contentEquals(other.yPlane)) return false
        if (faceRoi != other.faceRoi) return false
        if (fingerRoi != other.fingerRoi) return false
        if (imuData != other.imuData) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestampNs.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + yPlane.contentHashCode()
        result = 31 * result + (faceRoi?.hashCode() ?: 0)
        result = 31 * result + (fingerRoi?.hashCode() ?: 0)
        result = 31 * result + (imuData?.hashCode() ?: 0)
        return result
    }
}
