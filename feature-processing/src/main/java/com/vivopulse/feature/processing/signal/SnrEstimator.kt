package com.vivopulse.feature.processing.signal

import com.vivopulse.signal.FastFourierTransform
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Estimates Signal-to-Noise Ratio (SNR) for PPG signals.
 *
 * Uses spectral power ratio: Band (0.7-4.0 Hz) / Off-Band.
 * 
 * P0.3: Migrated from O(N²) DFT to FFT (Cooley-Tukey) for performance.
 */
class SnrEstimator {

    /**
     * Compute SNR in dB using FFT-based power spectrum.
     *
     * @param signal PPG signal (time domain)
     * @param fsHz Sampling frequency
     * @return SNR in dB
     */
    fun computeSnrDb(signal: DoubleArray, fsHz: Double): Double {
        if (signal.size < 64) return 0.0
        
        // 1. Remove DC / Detrend
        val mean = signal.average()
        val zeroMean = DoubleArray(signal.size) { signal[it] - mean }
        
        // 2. Zero-pad to next power of 2
        val nfft = FastFourierTransform.nextPowerOf2(zeroMean.size)
        
        // 3. Windowing (Hanning) + zero-pad
        val windowed = DoubleArray(nfft)
        val n = zeroMean.size
        for (i in 0 until n) {
            val w = 0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / (n - 1)))
            windowed[i] = zeroMean[i] * w
        }
        // Remaining windowed[n..nfft-1] are zero-padded
        
        // 4. FFT
        val (real, imag) = FastFourierTransform.fft(windowed)
        
        // 5. Sum power in signal band (0.7–4.0 Hz) vs noise band
        val binWidth = fsHz / nfft
        var signalPower = 0.0
        var noisePower = 0.0
        
        // Only iterate over positive frequencies [0, Nyquist]
        val nyquistBin = nfft / 2
        for (i in 1..nyquistBin) {
            val freq = i * binWidth
            if (freq > fsHz / 2) break
            
            val power = real[i] * real[i] + imag[i] * imag[i]
            
            if (freq in 0.7..4.0) {
                signalPower += power
            } else {
                noisePower += power
            }
        }
        
        if (noisePower <= 1e-9) return 50.0 // High cap
        
        return 10.0 * log10(signalPower / noisePower)
    }
    
    /**
     * Compute magnitude spectrum using FFT.
     * 
     * @param data Input signal (any length, will be zero-padded to power of 2)
     * @return Magnitude spectrum (positive frequencies only, length nfft/2 + 1)
     */
    fun computeMagnitudeSpectrum(data: DoubleArray): DoubleArray {
        val nfft = FastFourierTransform.nextPowerOf2(data.size)
        val padded = DoubleArray(nfft)
        System.arraycopy(data, 0, padded, 0, data.size)
        
        val (real, imag) = FastFourierTransform.fft(padded)
        
        val spectrum = DoubleArray(nfft / 2 + 1)
        for (k in spectrum.indices) {
            spectrum[k] = sqrt(real[k] * real[k] + imag[k] * imag[k])
        }
        return spectrum
    }
}
