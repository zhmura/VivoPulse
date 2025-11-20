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
        var sumSquaredMag = 0.0
        
        for (i in 0 until n) {
            val x = accelX[i]
            val y = accelY[i]
            val z = accelZ[i]
            
            // Magnitude
            val mag = sqrt(x*x + y*y + z*z)
            
            // Simple gravity removal: assume mean is gravity (approx 1.0 or 9.8)
            // Ideally we'd use a high-pass filter.
            // Here we assume input is raw Gs, so ~1.0 is static.
            // We'll just look at deviation from 1.0 for simplicity, 
            // or better, deviation from the window mean.
            
            // Let's calculate variance from mean magnitude in this window
            // This captures "shaking" regardless of orientation.
        }
        
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
        timestampsNs: LongArray,
        accelZ: DoubleArray
    ): List<Long> {
        // Simple peak detection for steps
        // Threshold e.g. > 1.2G or < 0.8G spikes
        val steps = mutableListOf<Long>()
        val threshold = 0.2 // Deviation from 1.0
        
        // This is a placeholder for a more robust step detector
        // For now, we just return empty as we might not have raw IMU streams wired up yet
        return steps
    }
}
