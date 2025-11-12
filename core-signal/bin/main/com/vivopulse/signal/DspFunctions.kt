package com.vivopulse.signal

import kotlin.math.*

/**
 * Digital Signal Processing functions for PPG signal conditioning.
 */
object DspFunctions {
    
    /**
     * Detrend a signal by subtracting a moving average.
     * 
     * This removes slow baseline drift and DC components.
     * 
     * @param signal Input signal
     * @param windowSize Moving average window size (samples)
     * @return Detrended signal
     */
    fun detrend(signal: DoubleArray, windowSize: Int = 100): DoubleArray {
        if (signal.size < windowSize) {
            // If signal too short, just remove DC component
            val mean = signal.average()
            return signal.map { it - mean }.toDoubleArray()
        }
        
        val detrended = DoubleArray(signal.size)
        
        // Compute moving average
        for (i in signal.indices) {
            val start = maxOf(0, i - windowSize / 2)
            val end = minOf(signal.size, i + windowSize / 2)
            
            var sum = 0.0
            for (j in start until end) {
                sum += signal[j]
            }
            val average = sum / (end - start)
            
            detrended[i] = signal[i] - average
        }
        
        return detrended
    }
    
    /**
     * Apply high-pass IIR filter for detrending.
     * 
     * First-order Butterworth high-pass filter.
     * 
     * @param signal Input signal
     * @param cutoffHz Cutoff frequency in Hz
     * @param sampleRateHz Sample rate in Hz
     * @return High-pass filtered signal
     */
    fun detrendIIR(signal: DoubleArray, cutoffHz: Double = 0.5, sampleRateHz: Double = 100.0): DoubleArray {
        if (signal.isEmpty()) return doubleArrayOf()
        
        // First-order high-pass filter coefficients
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        val dt = 1.0 / sampleRateHz
        val alpha = rc / (rc + dt)
        
        val filtered = DoubleArray(signal.size)
        filtered[0] = signal[0]
        
        for (i in 1 until signal.size) {
            filtered[i] = alpha * (filtered[i - 1] + signal[i] - signal[i - 1])
        }
        
        return filtered
    }
    
    /**
     * Apply Butterworth bandpass filter (IIR biquad).
     * 
     * Second-order sections (SOS) implementation for stability.
     * 
     * @param signal Input signal
     * @param lowCutoffHz Low cutoff frequency (Hz)
     * @param highCutoffHz High cutoff frequency (Hz)
     * @param sampleRateHz Sample rate (Hz)
     * @param order Filter order (default 2)
     * @return Bandpass filtered signal
     */
    fun butterworthBandpass(
        signal: DoubleArray,
        lowCutoffHz: Double = 0.7,
        highCutoffHz: Double = 4.0,
        sampleRateHz: Double = 100.0,
        order: Int = 2
    ): DoubleArray {
        if (signal.isEmpty()) return doubleArrayOf()
        
        // Design bandpass as cascade of high-pass and low-pass
        var filtered = signal
        
        // High-pass filter (remove frequencies below lowCutoffHz)
        filtered = butterworthHighpass(filtered, lowCutoffHz, sampleRateHz, order)
        
        // Low-pass filter (remove frequencies above highCutoffHz)
        filtered = butterworthLowpass(filtered, highCutoffHz, sampleRateHz, order)
        
        return filtered
    }
    
    /**
     * Second-order Butterworth low-pass filter.
     */
    private fun butterworthLowpass(
        signal: DoubleArray,
        cutoffHz: Double,
        sampleRateHz: Double,
        order: Int
    ): DoubleArray {
        var filtered = signal
        
        // Apply biquad sections (order/2 times)
        for (section in 0 until order / 2) {
            filtered = biquadLowpass(filtered, cutoffHz, sampleRateHz)
        }
        
        return filtered
    }
    
    /**
     * Second-order Butterworth high-pass filter.
     */
    private fun butterworthHighpass(
        signal: DoubleArray,
        cutoffHz: Double,
        sampleRateHz: Double,
        order: Int
    ): DoubleArray {
        var filtered = signal
        
        // Apply biquad sections (order/2 times)
        for (section in 0 until order / 2) {
            filtered = biquadHighpass(filtered, cutoffHz, sampleRateHz)
        }
        
        return filtered
    }
    
    /**
     * Second-order biquad low-pass filter.
     * 
     * Direct Form II implementation.
     */
    private fun biquadLowpass(signal: DoubleArray, cutoffHz: Double, sampleRateHz: Double): DoubleArray {
        if (signal.isEmpty()) return doubleArrayOf()
        
        // Calculate filter coefficients
        val omega = 2.0 * PI * cutoffHz / sampleRateHz
        val sn = sin(omega)
        val cs = cos(omega)
        val alpha = sn / sqrt(2.0)
        
        val b0 = (1.0 - cs) / 2.0
        val b1 = 1.0 - cs
        val b2 = (1.0 - cs) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cs
        val a2 = 1.0 - alpha
        
        // Normalize coefficients
        val b0n = b0 / a0
        val b1n = b1 / a0
        val b2n = b2 / a0
        val a1n = a1 / a0
        val a2n = a2 / a0
        
        // Apply filter (Direct Form II)
        val filtered = DoubleArray(signal.size)
        var w1 = 0.0
        var w2 = 0.0
        
        for (i in signal.indices) {
            val w0 = signal[i] - a1n * w1 - a2n * w2
            filtered[i] = b0n * w0 + b1n * w1 + b2n * w2
            w2 = w1
            w1 = w0
        }
        
        return filtered
    }
    
    /**
     * Second-order biquad high-pass filter.
     */
    private fun biquadHighpass(signal: DoubleArray, cutoffHz: Double, sampleRateHz: Double): DoubleArray {
        if (signal.isEmpty()) return doubleArrayOf()
        
        // Calculate filter coefficients
        val omega = 2.0 * PI * cutoffHz / sampleRateHz
        val sn = sin(omega)
        val cs = cos(omega)
        val alpha = sn / sqrt(2.0)
        
        val b0 = (1.0 + cs) / 2.0
        val b1 = -(1.0 + cs)
        val b2 = (1.0 + cs) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cs
        val a2 = 1.0 - alpha
        
        // Normalize coefficients
        val b0n = b0 / a0
        val b1n = b1 / a0
        val b2n = b2 / a0
        val a1n = a1 / a0
        val a2n = a2 / a0
        
        // Apply filter (Direct Form II)
        val filtered = DoubleArray(signal.size)
        var w1 = 0.0
        var w2 = 0.0
        
        for (i in signal.indices) {
            val w0 = signal[i] - a1n * w1 - a2n * w2
            filtered[i] = b0n * w0 + b1n * w1 + b2n * w2
            w2 = w1
            w1 = w0
        }
        
        return filtered
    }
    
    /**
     * Normalize signal using z-score normalization.
     * 
     * Transforms signal to zero mean and unit variance.
     * 
     * @param signal Input signal
     * @return Normalized signal with mean=0, std=1
     */
    fun zscoreNormalize(signal: DoubleArray): DoubleArray {
        if (signal.isEmpty()) return doubleArrayOf()
        
        val mean = signal.average()
        val variance = signal.map { (it - mean).pow(2) }.average()
        val std = sqrt(variance)
        
        return if (std > 1e-10) {
            signal.map { (it - mean) / std }.toDoubleArray()
        } else {
            // Signal is constant, return zeros
            DoubleArray(signal.size)
        }
    }
    
    /**
     * Generate synthetic sine wave for testing.
     * 
     * @param frequencyHz Frequency in Hz
     * @param durationS Duration in seconds
     * @param sampleRateHz Sample rate in Hz
     * @param amplitude Amplitude
     * @param phase Phase offset in radians
     * @return Synthetic signal
     */
    fun generateSineWave(
        frequencyHz: Double,
        durationS: Double,
        sampleRateHz: Double,
        amplitude: Double = 1.0,
        phase: Double = 0.0
    ): DoubleArray {
        val numSamples = (durationS * sampleRateHz).toInt()
        val signal = DoubleArray(numSamples)
        
        for (i in 0 until numSamples) {
            val t = i / sampleRateHz
            signal[i] = amplitude * sin(2.0 * PI * frequencyHz * t + phase)
        }
        
        return signal
    }
    
    /**
     * Add linear drift to a signal.
     * 
     * @param signal Input signal
     * @param driftRate Drift rate (units per sample)
     * @return Signal with added drift
     */
    fun addLinearDrift(signal: DoubleArray, driftRate: Double): DoubleArray {
        return signal.mapIndexed { i, value ->
            value + i * driftRate
        }.toDoubleArray()
    }
    
    /**
     * Compute signal power in frequency band.
     * 
     * Simple approximation using sum of squares.
     * 
     * @param signal Input signal
     * @return Signal power (RMS)
     */
    fun computePower(signal: DoubleArray): Double {
        if (signal.isEmpty()) return 0.0
        return sqrt(signal.map { it * it }.average())
    }
}

