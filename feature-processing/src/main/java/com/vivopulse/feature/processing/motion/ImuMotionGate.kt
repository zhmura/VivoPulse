package com.vivopulse.feature.processing.motion

import kotlin.math.sqrt

/**
 * Gates signal processing based on IMU (Accelerometer/Gyro) motion.
 *
 * Detects high-motion events like walking (heel strikes) that corrupt PPG.
 */
class ImuMotionGate {

    /**
     * Compute RMS acceleration (removing gravity).
     *
     * @param accelX Accelerometer X samples
     * @param accelY Accelerometer Y samples
     * @param accelZ Accelerometer Z samples
     * @return RMS acceleration in G (excluding gravity)
     */
    fun computeRmsAcceleration(
        accelX: DoubleArray,
        accelY: DoubleArray,
        accelZ: DoubleArray
    ): Double {
        if (accelX.isEmpty()) return 0.0
        
        val n = accelX.size
        
        // Two-pass for variance: calculate mean magnitude first, then variance
        
        // Two-pass for variance
        var sumMag = 0.0
        val mags = DoubleArray(n)
        for (i in 0 until n) {
            mags[i] = sqrt(accelX[i]*accelX[i] + accelY[i]*accelY[i] + accelZ[i]*accelZ[i])
            sumMag += mags[i]
        }
        val meanMag = sumMag / n
        
        var sumVar = 0.0
        for (m in mags) {
            sumVar += (m - meanMag) * (m - meanMag)
        }
        
        return sqrt(sumVar / n)
    }
    
    /**
     * Detect step events (heel strikes).
     *
     * @param timestampsNs Timestamps of samples
     * @param accelZ Vertical/Z acceleration (or magnitude)
     * @return List of timestamps (ns) where a step likely occurred
     */
    fun detectSteps(
        @Suppress("UNUSED_PARAMETER") timestampsNs: LongArray,
        @Suppress("UNUSED_PARAMETER") accelZ: DoubleArray
    ): List<Long> {
        // Placeholder for step detector (not yet implemented)
        return emptyList()
    }
}
