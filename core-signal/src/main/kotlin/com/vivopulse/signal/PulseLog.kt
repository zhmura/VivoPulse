package com.vivopulse.signal

/**
 * Structured logcat tags for the VivoPulse capture & processing pipeline.
 *
 * Each tag represents a functional category, filterable via:
 * ```
 * adb logcat -s PULSE_SESSION PULSE_HW PULSE_FRAME PULSE_MEASURE PULSE_PIPELINE PULSE_SUMMARY
 * ```
 *
 * Or individually:
 * ```
 * adb logcat -s PULSE_FRAME       # per-frame diagnostics (AE, clipping, cover)
 * adb logcat -s PULSE_PIPELINE    # postprocessing (resampling, filtering, PTT)
 * ```
 */
object PulseLog {
    /** Session lifecycle: start, stop, duration, mode, FPS stats */
    const val SESSION = "PULSE_SESSION"

    /** Camera hardware config: device probe, capabilities, binding mode, resolution */
    const val HW = "PULSE_HW"

    /** Per-frame artifacts: AE_DIAG, AE_DRIFT, PHOTO_GATE, clipping, finger cover, clock */
    const val FRAME = "PULSE_FRAME"

    /** Measurement state: 3A lock, status transitions, GoodSync progress */
    const val MEASURE = "PULSE_MEASURE"

    /** Postprocessing pipeline: motion rejection, resampling, filtering, PTT, step exclusion */
    const val PIPELINE = "PULSE_PIPELINE"

    /** Final session summary block */
    const val SUMMARY = "PULSE_SUMMARY"
}
