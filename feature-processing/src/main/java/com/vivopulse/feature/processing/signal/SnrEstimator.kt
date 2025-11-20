package com.vivopulse.feature.processing.signal

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Estimates Signal-to-Noise Ratio (SNR) for PPG signals.
 *
 * Uses spectral power ratio: Band (0.7-4.0 Hz) / Off-Band.
 */
class SnrEstimator {

    /**
     * Compute SNR in dB.
     *
     * @param signal PPG signal (time domain)
     * @param fsHz Sampling frequency
     * @return SNR in dB
     */
    fun computeSnrDb(signal: DoubleArray, fsHz: Double): Double {
        if (signal.size < 64) return 0.0
        
        // 1. Remove DC / Detrend
        val mean = signal.average()
        val zeroMean = signal.map { it - mean }.toDoubleArray()
        
        // 2. Windowing (Hanning)
        val n = zeroMean.size
        val windowed = DoubleArray(n)
        for (i in 0 until n) {
            val w = 0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / (n - 1)))
            windowed[i] = zeroMean[i] * w
        }
        
        // 3. FFT (Magnitude Spectrum)
        // Simple DFT for now if N is small, or assume we have an FFT util.
        // Since we don't have a complex math lib handy, let's do a simple DFT for relevant bins
        // or just use a rough variance ratio if we had bandpassed signals.
        // But the requirement asks for "band power / off-band power".
        
        // Let's implement a basic DFT for the spectrum.
        val spectrum = computeMagnitudeSpectrum(windowed)
        
        // 4. Sum Power in Signal Band (0.7 - 4.0 Hz) vs Noise Band
        var signalPower = 0.0
        var noisePower = 0.0
        
        val binWidth = fsHz / n
        
        for (i in spectrum.indices) {
            val freq = i * binWidth
            // Skip DC (already removed but just in case)
            if (freq < 0.1) continue
            // Nyquist
            if (freq > fsHz / 2) break
            
            val power = spectrum[i] * spectrum[i]
            
            if (freq in 0.7..4.0) {
                signalPower += power
            } else {
                noisePower += power
            }
        }
        
        if (noisePower <= 1e-9) return 50.0 // High cap
        
        return 10.0 * log10(signalPower / noisePower)
    }
    
    private fun computeMagnitudeSpectrum(data: DoubleArray): DoubleArray {
        val n = data.size
        val spectrum = DoubleArray(n / 2 + 1)
        
        // Slow DFT O(N^2) - okay for small windows (e.g. 100-300 samples)
        // For larger windows, we should use FFT.
        // Assuming window is ~6-10s @ 30Hz = 180-300 samples. Acceptable.
        
        for (k in spectrum.indices) {
            var real = 0.0
            var imag = 0.0
            for (t in 0 until n) {
                val angle = 2.0 * Math.PI * t * k / n
                real += data[t] * kotlin.math.cos(angle)
                imag -= data[t] * kotlin.math.sin(angle)
            }
            spectrum[k] = sqrt(real * real + imag * imag)
        }
        return spectrum
    }
}
