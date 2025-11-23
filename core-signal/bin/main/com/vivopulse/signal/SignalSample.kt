package com.vivopulse.signal

/**
 * Cross-module representation of a real-time signal sample extracted per frame.
 *
 * Only contains scalar metrics required by quality analysis to avoid
 * retaining raw bitmaps or YUV buffers.
 */
data class SignalSample(
    val timestampNs: Long,
    val faceMeanLuma: Double? = null,
    val fingerMeanLuma: Double? = null,
    val faceMotionRmsPx: Double? = null,
    val fingerSaturationPct: Double? = null,
    val imuRmsG: Double? = null,
    val torchEnabled: Boolean = false
)


