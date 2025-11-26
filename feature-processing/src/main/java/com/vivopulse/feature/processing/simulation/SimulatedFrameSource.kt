package com.vivopulse.feature.processing.simulation

import com.vivopulse.feature.processing.RawSeriesBuffer
import com.vivopulse.feature.processing.timestamp.TimestampedValue
import com.vivopulse.signal.DspFunctions
import kotlin.math.sin
import kotlin.math.PI

/**
 * Simulated frame source for deterministic testing.
 * 
 * Generates synthetic PPG-like signals with configurable parameters:
 * - Heart rate
 * - PTT lag
 * - Noise level
 * - Drift amount
 */
class SimulatedFrameSource(
    private val config: SimulationConfig = SimulationConfig()
) {
    
    /**
     * Generate simulated signal data.
     * 
     * @return RawSeriesBuffer with synthetic face and finger signals
     */
    fun generateSignals(): RawSeriesBuffer {
        val sampleRate = config.captureRateHz
        val duration = config.durationSeconds
        val numSamples = (duration * sampleRate).toInt()
        
        // Generate base PPG signal (face)
        val facePPG = generatePPGSignal(
            heartRateHz = config.heartRateHz,
            duration = duration,
            sampleRate = sampleRate,
            amplitude = 1.0
        )
        
        // Generate finger signal (delayed by PTT)
        val pttSamples = (config.pttLagMs / 1000.0 * sampleRate).toInt()
        val fingerPPG = DoubleArray(numSamples)
        
        // Copy with lag
        for (i in pttSamples until numSamples) {
            fingerPPG[i] = facePPG[i - pttSamples]
        }
        
        // Add noise if enabled
        val noisyFace = if (config.noiseEnabled) {
            addNoise(facePPG, config.noiseLevel, duration, sampleRate)
        } else {
            facePPG
        }
        
        val noisyFinger = if (config.noiseEnabled) {
            addNoise(fingerPPG, config.noiseLevel, duration, sampleRate)
        } else {
            fingerPPG
        }
        
        // Add drift if enabled
        val finalFace = if (config.driftEnabled) {
            DspFunctions.addLinearDrift(noisyFace, config.driftRate)
        } else {
            noisyFace
        }
        
        val finalFinger = if (config.driftEnabled) {
            DspFunctions.addLinearDrift(noisyFinger, config.driftRate)
        } else {
            noisyFinger
        }
        
        // Create timestamped values
        val faceData = (0 until numSamples).map { i ->
            val timeS = i / sampleRate
            val timestampNs = (timeS * 1_000_000_000).toLong()
            TimestampedValue(timestampNs, finalFace[i])
        }
        
        val fingerData = (0 until numSamples).map { i ->
            val timeS = i / sampleRate
            val timestampNs = (timeS * 1_000_000_000).toLong()
            TimestampedValue(timestampNs, finalFinger[i])
        }
        
        return RawSeriesBuffer(faceData, fingerData)
    }
    
    /**
     * Generate realistic PPG signal (Pulse Train).
     * 
     * Uses Gaussian pulses instead of sine waves for more realistic morphology
     * (sharper upstroke, distinct feet).
     */
    private fun generatePPGSignal(
        heartRateHz: Double,
        duration: Double,
        sampleRate: Double,
        amplitude: Double
    ): DoubleArray {
        val numSamples = (duration * sampleRate).toInt()
        val signal = DoubleArray(numSamples)
        val period = 1.0 / heartRateHz
        
        // Generate pulse train
        var tPulse = 0.0
        while (tPulse < duration) {
            // Add pulse centered at tPulse
            // Width (sigma) = 0.08s (80ms) - typical systolic width
            val sigma = 0.08
            val widthFactor = 1.0 / (2.0 * sigma * sigma) // ~78.125
            
            // Optimization: iterate all samples (for simplicity) or just window
            // Since this is offline generation, iterating all is fine for 30s buffer
            for (i in 0 until numSamples) {
                val t = i / sampleRate
                
                // Systolic peak
                val dt = t - tPulse
                // Limit influence to +/- 0.5s
                if (kotlin.math.abs(dt) < 0.5) {
                    signal[i] += amplitude * kotlin.math.exp(-widthFactor * dt * dt)
                }
            }
            
            tPulse += period
        }
        
        return signal
    }
    
    /**
     * Add noise to signal.
     */
    private fun addNoise(
        signal: DoubleArray,
        noiseLevel: Double,
        duration: Double,
        sampleRate: Double
    ): DoubleArray {
        // High-frequency noise
        val noise1 = DspFunctions.generateSineWave(15.0, duration, sampleRate, amplitude = noiseLevel * 0.6)
        val noise2 = DspFunctions.generateSineWave(12.0, duration, sampleRate, amplitude = noiseLevel * 0.4)
        
        return signal.indices.map { i ->
            signal[i] + noise1[i] + noise2[i]
        }.toDoubleArray()
    }
}

/**
 * Simulation configuration parameters.
 */
data class SimulationConfig(
    val heartRateHz: Double = 1.2,          // 72 BPM
    val pttLagMs: Double = 100.0,           // Pulse transit time
    val durationSeconds: Double = 30.0,     // Capture duration
    val captureRateHz: Double = 30.0,       // Simulated camera FPS
    val noiseEnabled: Boolean = true,
    val noiseLevel: Double = 0.15,          // 15% of signal
    val driftEnabled: Boolean = true,
    val driftRate: Double = 0.03            // Linear drift slope
) {
    /**
     * Get heart rate in BPM.
     */
    fun getHeartRateBPM(): Int = (heartRateHz * 60).toInt()
    
    /**
     * Validate parameters.
     */
    fun isValid(): Boolean {
        return heartRateHz in 0.5..4.0 &&        // 30-240 BPM
               pttLagMs in 30.0..200.0 &&         // Physiological range
               durationSeconds in 5.0..120.0 &&   // 5s to 2 minutes
               captureRateHz in 10.0..60.0 &&     // Reasonable FPS
               noiseLevel in 0.0..1.0 &&          // 0-100%
               driftRate in 0.0..0.2              // Reasonable drift
    }
    
    /**
     * Create preset configurations.
     */
    companion object {
        fun ideal() = SimulationConfig(
            heartRateHz = 1.2,
            pttLagMs = 100.0,
            noiseEnabled = false,
            driftEnabled = false
        )
        
        fun realistic() = SimulationConfig(
            heartRateHz = 1.2,
            pttLagMs = 100.0,
            noiseEnabled = true,
            noiseLevel = 0.10,
            driftEnabled = true,
            driftRate = 0.02
        )
        
        fun challenging() = SimulationConfig(
            heartRateHz = 1.5,
            pttLagMs = 85.0,
            noiseEnabled = true,
            noiseLevel = 0.30,
            driftEnabled = true,
            driftRate = 0.05
        )
        
        fun lowHR() = SimulationConfig(
            heartRateHz = 0.9,  // 54 BPM
            pttLagMs = 120.0,
            noiseEnabled = true,
            noiseLevel = 0.12
        )
        
        fun highHR() = SimulationConfig(
            heartRateHz = 2.5,  // 150 BPM
            pttLagMs = 70.0,
            noiseEnabled = true,
            noiseLevel = 0.12
        )
    }
}

