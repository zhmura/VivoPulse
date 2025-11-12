package com.vivopulse.feature.capture.imu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * IMU-based motion detector.
 * 
 * Uses accelerometer to detect motion that may corrupt PPG signals.
 * Complements optical flow-based motion detection.
 */
class IMUMotionDetector(context: Context) : SensorEventListener {
    
    private val tag = "IMUMotionDetector"
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    companion object {
        const val MOTION_THRESHOLD_RMS = 0.5  // m/s² RMS threshold for motion
        const val WINDOW_SIZE = 30  // Samples for RMS calculation (~30 samples @ ~100 Hz)
    }
    
    private val accelHistory = mutableListOf<AccelSample>()
    private val maxHistorySize = 300 // 3 seconds at 100 Hz
    
    private var isMonitoring = false
    
    /**
     * Start monitoring accelerometer.
     */
    fun startMonitoring() {
        if (accelerometer == null) {
            Log.w(tag, "Accelerometer not available")
            return
        }
        
        sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_GAME // ~50-100 Hz
        )
        isMonitoring = true
        
        Log.d(tag, "IMU monitoring started")
    }
    
    /**
     * Stop monitoring accelerometer.
     */
    fun stopMonitoring() {
        if (!isMonitoring) return
        
        sensorManager.unregisterListener(this)
        isMonitoring = false
        
        Log.d(tag, "IMU monitoring stopped")
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        
        // Remove gravity (simple high-pass: subtract mean over window)
        val magnitude = sqrt(x.pow(2) + y.pow(2) + z.pow(2))
        
        accelHistory.add(
            AccelSample(
                timestampMs = System.currentTimeMillis(),
                x = x.toDouble(),
                y = y.toDouble(),
                z = z.toDouble(),
                magnitude = magnitude.toDouble()
            )
        )
        
        // Keep history size manageable
        while (accelHistory.size > maxHistorySize) {
            accelHistory.removeAt(0)
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }
    
    /**
     * Compute RMS acceleration (motion indicator).
     * 
     * @return RMS acceleration in m/s²
     */
    fun computeRMS(): Double {
        if (accelHistory.size < WINDOW_SIZE) return 0.0
        
        val recent = accelHistory.takeLast(WINDOW_SIZE)
        
        // Compute mean magnitude (gravity)
        val meanMagnitude = recent.map { it.magnitude }.average()
        
        // Compute RMS of deviations from mean
        val rms = sqrt(recent.map { (it.magnitude - meanMagnitude).pow(2) }.average())
        
        return rms
    }
    
    /**
     * Check if currently in motion.
     */
    fun isInMotion(): Boolean {
        return computeRMS() > MOTION_THRESHOLD_RMS
    }
    
    /**
     * Get motion percentage over entire session.
     */
    fun getMotionPercentage(): Double {
        if (accelHistory.isEmpty()) return 0.0
        
        var motionSamples = 0
        for (i in WINDOW_SIZE until accelHistory.size) {
            val window = accelHistory.subList(i - WINDOW_SIZE, i)
            val meanMag = window.map { it.magnitude }.average()
            val rms = sqrt(window.map { (it.magnitude - meanMag).pow(2) }.average())
            if (rms > MOTION_THRESHOLD_RMS) {
                motionSamples++
            }
        }
        
        val validSamples = accelHistory.size - WINDOW_SIZE
        return if (validSamples > 0) {
            (motionSamples.toDouble() / validSamples) * 100.0
        } else {
            0.0
        }
    }
    
    /**
     * Get motion time windows.
     * 
     * @return List of time windows with motion
     */
    fun getMotionWindows(): List<MotionWindow> {
        if (accelHistory.size < WINDOW_SIZE) return emptyList()
        
        val windows = mutableListOf<MotionWindow>()
        var windowStart: Long? = null
        
        for (i in WINDOW_SIZE until accelHistory.size) {
            val window = accelHistory.subList(i - WINDOW_SIZE, i)
            val meanMag = window.map { it.magnitude }.average()
            val rms = sqrt(window.map { (it.magnitude - meanMag).pow(2) }.average())
            
            if (rms > MOTION_THRESHOLD_RMS) {
                if (windowStart == null) {
                    windowStart = accelHistory[i].timestampMs
                }
            } else {
                if (windowStart != null) {
                    windows.add(
                        MotionWindow(
                            startMs = windowStart,
                            endMs = accelHistory[i - 1].timestampMs,
                            avgRMS = rms
                        )
                    )
                    windowStart = null
                }
            }
        }
        
        // Handle last window
        if (windowStart != null) {
            windows.add(
                MotionWindow(
                    startMs = windowStart,
                    endMs = accelHistory.last().timestampMs,
                    avgRMS = computeRMS()
                )
            )
        }
        
        return windows
    }
    
    /**
     * Reset detector.
     */
    fun reset() {
        accelHistory.clear()
    }
}

/**
 * Accelerometer sample.
 */
data class AccelSample(
    val timestampMs: Long,
    val x: Double,
    val y: Double,
    val z: Double,
    val magnitude: Double
)

/**
 * Motion window from IMU.
 */
data class MotionWindow(
    val startMs: Long,
    val endMs: Long,
    val avgRMS: Double
)

