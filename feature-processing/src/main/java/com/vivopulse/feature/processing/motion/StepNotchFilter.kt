package com.vivopulse.feature.processing.motion

import com.vivopulse.signal.DspFunctions

/**
 * Adaptive Notch Filter for Walking Mode.
 * 
 * Removes step frequency artifacts from PPG signal.
 */
object StepNotchFilter {

    /**
     * Apply notch filter if walking is detected and step frequency is known.
     */
    fun apply(
        signal: DoubleArray,
        motionFeatures: ImuMotionAnalyzer.MotionFeatures,
        fsHz: Double
    ): DoubleArray {
        if (!motionFeatures.isWalking || motionFeatures.stepFrequencyHz == null) {
            return signal
        }
        
        val fStep = motionFeatures.stepFrequencyHz
        
        // Apply notch at step frequency
        var filtered = DspFunctions.notchFilter(signal, fStep, fsHz, qFactor = 5.0)
        
        // Optionally notch 2nd harmonic
        val fStep2 = fStep * 2
        if (fStep2 < fsHz / 2) {
            filtered = DspFunctions.notchFilter(filtered, fStep2, fsHz, qFactor = 5.0)
        }
        
        return filtered
    }
}

