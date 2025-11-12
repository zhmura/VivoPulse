package com.vivopulse.feature.processing

import com.vivopulse.feature.processing.ptt.HeartRateResult
import com.vivopulse.feature.processing.ptt.PttOutput
import com.vivopulse.feature.processing.PttResult
import com.vivopulse.feature.processing.wave.WaveFeatures
import com.vivopulse.signal.ChannelSQI

/**
 * Aggregated per-session summary for UI and export.
 *
 * Contains:
 * - PTT outputs (algorithmic and/or simplified)
 * - Vascular wave profile (shape-based features)
 * - HR & HRV from RR intervals
 * - SQIs (per-channel and combined)
 * - Light text hint of wave pattern (non-diagnostic)
 */
data class SessionSummary(
    val pttResult: PttResult?,                           // From PttCalculator
    val pttOutput: PttOutput? = null,                    // From PttEngine (optional, if used)
    val waveProfile: WaveFeatures.VascularWaveProfile?,  // Aggregated wave shape profile
    val heartRate: HeartRateResult?,                     // HR and HRV metrics (finger-primary)
    val faceSQI: ChannelSQI?,                            // Face channel SQI
    val fingerSQI: ChannelSQI?,                          // Finger channel SQI
    val combinedSQI: Double?,                            // Combined SQI (0-100)
    val wavePatternHint: String?                         // "more_elastic_like" / "more_stiff_like" / "uncertain"
)


