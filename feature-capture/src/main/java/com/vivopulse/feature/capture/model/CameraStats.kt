package com.vivopulse.feature.capture.model

/**
 * Camera statistics for monitoring performance.
 */
data class CameraStats(
    val source: Source,
    val framesReceived: Int = 0,
    val framesDropped: Int = 0,
    val averageFps: Float = 0f,
    val lastFrameTimestamp: Long = 0L
) {
    val totalFrames: Int
        get() = framesReceived + framesDropped

    val dropRate: Float
        get() = if (totalFrames > 0) framesDropped.toFloat() / totalFrames else 0f
}

/**
 * Recording session statistics.
 */
data class SessionStats(
    val durationMs: Long = 0,
    val faceStats: CameraStats = CameraStats(Source.FACE),
    val fingerStats: CameraStats = CameraStats(Source.FINGER)
) {
    val totalFrames: Int
        get() = faceStats.framesReceived + fingerStats.framesReceived

    val totalDropped: Int
        get() = faceStats.framesDropped + fingerStats.framesDropped
}

