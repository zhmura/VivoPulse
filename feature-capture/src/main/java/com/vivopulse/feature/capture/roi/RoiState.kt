package com.vivopulse.feature.capture.roi

import android.graphics.Rect

/**
 * ROI tracking state.
 */
enum class RoiState {
    STABLE,        // ROI is being tracked successfully (green)
    RE_ACQUIRING,  // Face detected but ROI is being recomputed (yellow)
    LOST           // No face detected (red)
}

/**
 * Face ROI information.
 */
data class FaceRoi(
    val rect: Rect,
    val state: RoiState,
    val confidence: Float = 1.0f,
    val frameTimestamp: Long = System.currentTimeMillis()
) {
    /**
     * Check if ROI is valid for signal extraction.
     */
    fun isValid(): Boolean = state == RoiState.STABLE && !rect.isEmpty
    
    /**
     * Get ROI size.
     */
    fun getSize(): Int = rect.width() * rect.height()
}

