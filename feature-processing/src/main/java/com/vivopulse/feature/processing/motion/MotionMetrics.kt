package com.vivopulse.feature.processing.motion

/**
 * Combined motion metrics from optical flow and IMU.
 * 
 * Provides comprehensive motion assessment for session quality.
 */
data class MotionMetrics(
    // Optical flow metrics
    val opticalFlowStats: MotionStatistics,
    val maskedWindowsOptical: List<TimeWindow>,
    val maskedPercentageOptical: Double,
    
    // IMU metrics (optional)
    val imuMotionWindows: List<MotionWindow>?,
    val maskedPercentageIMU: Double?,
    
    // Combined metrics
    val combinedMaskedPercentage: Double,
    val isMotionRobust: Boolean  // True if masked <30%
) {
    /**
     * Get motion quality assessment.
     */
    fun getMotionQuality(): MotionQuality {
        return when {
            combinedMaskedPercentage > 40 -> MotionQuality.POOR
            combinedMaskedPercentage > 30 -> MotionQuality.FAIR
            combinedMaskedPercentage > 15 -> MotionQuality.GOOD
            else -> MotionQuality.EXCELLENT
        }
    }
    
    /**
     * Get actionable tip based on motion metrics.
     */
    fun getMotionTip(): String? {
        return when (getMotionQuality()) {
            MotionQuality.POOR -> "Excessive motion detected. Hold device very steady."
            MotionQuality.FAIR -> "Moderate motion detected. Try to minimize head movement."
            MotionQuality.GOOD -> "Some motion detected. Minor improvements possible."
            MotionQuality.EXCELLENT -> null // No tip needed
        }
    }
    
    /**
     * Format for display.
     */
    fun formatSummary(): String {
        return buildString {
            appendLine("Motion Metrics:")
            appendLine("  Avg Flow: ${String.format("%.2f", opticalFlowStats.avgFlowMagnitude)} px")
            appendLine("  Max Flow: ${String.format("%.2f", opticalFlowStats.maxFlowMagnitude)} px")
            appendLine("  Stable: ${String.format("%.1f", opticalFlowStats.stablePercentage)}%")
            appendLine("  Motion: ${String.format("%.1f", opticalFlowStats.motionPercentage)}%")
            appendLine("  Masked (Optical): ${String.format("%.1f", maskedPercentageOptical)}%")
            if (maskedPercentageIMU != null) {
                appendLine("  Masked (IMU): ${String.format("%.1f", maskedPercentageIMU)}%")
            }
            appendLine("  Masked (Combined): ${String.format("%.1f", combinedMaskedPercentage)}%")
            appendLine("  Quality: ${getMotionQuality()}")
            getMotionTip()?.let { appendLine("  Tip: $it") }
        }
    }
    
    companion object {
        /**
         * Compute combined metrics from optical flow and IMU.
         */
        fun compute(
            opticalFlowStats: MotionStatistics,
            maskedWindowsOptical: List<TimeWindow>,
            totalDurationS: Double,
            imuMotionWindows: List<MotionWindow>? = null
        ): MotionMetrics {
            // Compute optical masked percentage
            val opticalMaskedDurationS = maskedWindowsOptical.sumOf { 
                (it.endMs - it.startMs) / 1000.0
            }
            val maskedPercentageOptical = (opticalMaskedDurationS / totalDurationS) * 100.0
            
            // Compute IMU masked percentage if available
            var maskedPercentageIMU: Double? = null
            if (imuMotionWindows != null) {
                val imuMaskedDurationS = imuMotionWindows.sumOf { 
                    (it.endMs - it.startMs) / 1000.0
                }
                maskedPercentageIMU = (imuMaskedDurationS / totalDurationS) * 100.0
            }
            
            // Combined percentage (union of optical and IMU)
            val combinedMaskedPercentage = if (maskedPercentageIMU != null) {
                // Take max (conservative)
                kotlin.math.max(maskedPercentageOptical, maskedPercentageIMU)
            } else {
                maskedPercentageOptical
            }
            
            val isMotionRobust = combinedMaskedPercentage <= 30.0
            
            return MotionMetrics(
                opticalFlowStats = opticalFlowStats,
                maskedWindowsOptical = maskedWindowsOptical,
                maskedPercentageOptical = maskedPercentageOptical,
                imuMotionWindows = imuMotionWindows,
                maskedPercentageIMU = maskedPercentageIMU,
                combinedMaskedPercentage = combinedMaskedPercentage,
                isMotionRobust = isMotionRobust
            )
        }
    }
}

/**
 * Motion quality enum.
 */
enum class MotionQuality {
    EXCELLENT,  // <15% masked
    GOOD,       // 15-30% masked
    FAIR,       // 30-40% masked
    POOR        // >40% masked
}

/**
 * Motion statistics (duplicated from feature-capture to avoid circular dependency).
 */
data class MotionStatistics(
    val avgFlowMagnitude: Double,
    val maxFlowMagnitude: Double,
    val motionPercentage: Double,
    val stablePercentage: Double
)

/**
 * Motion window from IMU (duplicated from feature-capture).
 */
data class MotionWindow(
    val startMs: Long,
    val endMs: Long,
    val avgRMS: Double
)

