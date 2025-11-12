package com.vivopulse.feature.capture.flicker

import kotlin.math.*

/**
 * Flicker meter for detecting artificial lighting flicker.
 * 
 * Uses periodogram (FFT-based power spectral density) to detect strong peaks
 * at mains frequencies (50/60 Hz) or their aliases in the cardiac band.
 */
class FlickerMeter(
    private val fsHz: Double = 30.0 // Camera frame rate
) {
    
    companion object {
        const val FLICKER_THRESHOLD_DB = -10.0  // If peak power > threshold, flicker detected
    }
    
    private val sampleBuffer = mutableListOf<Double>()
    private val bufferSize = 128 // ~4 seconds at 30 fps
    
    /**
     * Add sample to buffer.
     */
    fun addSample(meanLuma: Double) {
        sampleBuffer.add(meanLuma)
        
        while (sampleBuffer.size > bufferSize) {
            sampleBuffer.removeAt(0)
        }
    }
    
    /**
     * Detect flicker in current buffer.
     * 
     * Looks for strong periodic components at:
     * - 50/60 Hz (mains)
     * - 100/120 Hz (double frequency)
     * - Aliased frequencies (if fs < flicker freq)
     * 
     * @return FlickerResult with detection status and suggested mode
     */
    fun detectFlicker(): FlickerResult {
        if (sampleBuffer.size < 64) {
            return FlickerResult(
                hasFlicker = false,
                flickerFreqHz = 0.0,
                powerDb = 0.0,
                suggestedMode = AntiFlickerMode.AUTO,
                message = "Insufficient samples"
            )
        }
        
        // Compute periodogram (simplified: just check specific frequencies)
        val signal = sampleBuffer.toDoubleArray()
        
        // Detrend
        val mean = signal.average()
        val detrended = signal.map { it - mean }.toDoubleArray()
        
        // Check power at 50 Hz, 60 Hz, and aliases
        // Note: If fs = 30 Hz, 50 Hz aliases to 30-50=-20 Hz = 20 Hz
        // 60 Hz aliases to 30-60=-30 Hz = 30 Hz (Nyquist)
        
        val freq50Alias = abs(fsHz - 50.0) // Alias of 50 Hz
        val freq60Alias = abs(fsHz - 60.0) // Alias of 60 Hz
        val freq100Alias = abs(fsHz - 100.0) // Alias of 100 Hz
        val freq120Alias = abs(fsHz - 120.0) // Alias of 120 Hz
        
        // Compute power at each candidate frequency
        val power50 = computePowerAtFreq(detrended, minOf(freq50Alias, fsHz / 2.0 - 1.0), fsHz)
        val power60 = computePowerAtFreq(detrended, minOf(freq60Alias, fsHz / 2.0 - 1.0), fsHz)
        val power100 = computePowerAtFreq(detrended, minOf(freq100Alias, fsHz / 2.0 - 1.0), fsHz)
        val power120 = computePowerAtFreq(detrended, minOf(freq120Alias, fsHz / 2.0 - 1.0), fsHz)
        
        // Find max power
        val powers = listOf(
            Pair(50.0, power50),
            Pair(60.0, power60),
            Pair(100.0, power100),
            Pair(120.0, power120)
        )
        val (maxFreq, maxPower) = powers.maxByOrNull { it.second } ?: Pair(0.0, 0.0)
        
        // Compute power in dB relative to total signal power
        val totalPower = signal.map { it * it }.sum() / signal.size
        val powerDb = if (totalPower > 1e-10) {
            10.0 * log10(maxPower / totalPower)
        } else {
            -100.0
        }
        
        // Detect flicker if power above threshold
        val hasFlicker = powerDb > FLICKER_THRESHOLD_DB
        
        // Suggest anti-flicker mode
        val suggestedMode = when {
            !hasFlicker -> AntiFlickerMode.AUTO
            maxFreq in 45.0..55.0 || power50 > power60 -> AntiFlickerMode.HZ_50
            maxFreq in 55.0..65.0 || power60 > power50 -> AntiFlickerMode.HZ_60
            else -> AntiFlickerMode.AUTO
        }
        
        return FlickerResult(
            hasFlicker = hasFlicker,
            flickerFreqHz = maxFreq,
            powerDb = powerDb,
            suggestedMode = suggestedMode,
            message = if (hasFlicker) {
                "Flicker detected at ${maxFreq.toInt()} Hz (${powerDb.toInt()} dB)"
            } else {
                "No significant flicker"
            }
        )
    }
    
    /**
     * Compute power at specific frequency using Goertzel algorithm.
     * 
     * Simpler than FFT for single frequency.
     */
    private fun computePowerAtFreq(signal: DoubleArray, freqHz: Double, fsHz: Double): Double {
        val n = signal.size
        val k = (freqHz / fsHz * n).toInt()
        val omega = 2.0 * PI * k / n
        val coeff = 2.0 * cos(omega)
        
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        
        for (sample in signal) {
            s0 = sample + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return power / n
    }
    
    /**
     * Reset meter.
     */
    fun reset() {
        sampleBuffer.clear()
    }
}

/**
 * Flicker detection result.
 */
data class FlickerResult(
    val hasFlicker: Boolean,
    val flickerFreqHz: Double,
    val powerDb: Double,
    val suggestedMode: AntiFlickerMode,
    val message: String
)

/**
 * Anti-flicker mode enum.
 */
enum class AntiFlickerMode {
    AUTO,
    HZ_50,
    HZ_60
}



