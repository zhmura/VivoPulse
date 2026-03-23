package com.vivopulse.signal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Welch's method for spectral density estimation.
 * 
 * Computes auto-spectra (PSD), cross-spectra, and magnitude-squared coherence
 * using overlapping windowed segments averaged in the frequency domain.
 * 
 * Reference: Welch, P.D. (1967) "The use of FFT for the estimation of power
 * spectra: A method based on time averaging over short, modified periodograms."
 */
object WelchEstimator {
    
    /**
     * Result of Welch cross-spectral estimation.
     * 
     * @param freqs Frequency axis (Hz), length = nfft/2 + 1
     * @param psdX Auto-spectrum of X (power spectral density)
     * @param psdY Auto-spectrum of Y
     * @param crossReal Real part of cross-spectrum S_xy
     * @param crossImag Imaginary part of cross-spectrum S_xy
     * @param coherence Magnitude-squared coherence γ²(f) ∈ [0, 1]
     * @param nSegments Number of segments averaged
     */
    data class WelchResult(
        val freqs: DoubleArray,
        val psdX: DoubleArray,
        val psdY: DoubleArray,
        val crossReal: DoubleArray,
        val crossImag: DoubleArray,
        val coherence: DoubleArray,
        val nSegments: Int
    )
    
    /**
     * Compute Welch cross-spectral estimates between two signals.
     * 
     * @param x First signal (e.g., face PPG)
     * @param y Second signal (e.g., finger PPG)
     * @param fs Sampling frequency (Hz)
     * @param segmentLength Segment length in samples (default: 256)
     * @param overlapFraction Overlap fraction (default: 0.5 = 50%)
     * @return WelchResult with PSD, cross-spectrum, and coherence
     */
    fun crossSpectral(
        x: DoubleArray,
        y: DoubleArray,
        fs: Double,
        segmentLength: Int = 256,
        overlapFraction: Double = 0.5
    ): WelchResult {
        val n = minOf(x.size, y.size)
        val nfft = FastFourierTransform.nextPowerOf2(segmentLength)
        val halfNfft = nfft / 2 + 1
        val step = (segmentLength * (1.0 - overlapFraction)).toInt().coerceAtLeast(1)
        
        // Accumulation arrays
        val sxxAccum = DoubleArray(halfNfft)
        val syyAccum = DoubleArray(halfNfft)
        val sxyRealAccum = DoubleArray(halfNfft)
        val sxyImagAccum = DoubleArray(halfNfft)
        
        // Hanning window + normalization
        val window = DoubleArray(segmentLength)
        var windowPowerSum = 0.0
        for (i in 0 until segmentLength) {
            window[i] = 0.5 * (1.0 - cos(2.0 * PI * i / (segmentLength - 1)))
            windowPowerSum += window[i] * window[i]
        }
        // Window normalization factor: fs * Σ(w²) for PSD scaling
        val normFactor = fs * windowPowerSum
        
        var nSegments = 0
        var offset = 0
        
        while (offset + segmentLength <= n) {
            // Extract and window segments
            val segX = DoubleArray(nfft)
            val segY = DoubleArray(nfft)
            for (i in 0 until segmentLength) {
                segX[i] = (x[offset + i] - x.sliceMean(offset, segmentLength)) * window[i]
                segY[i] = (y[offset + i] - y.sliceMean(offset, segmentLength)) * window[i]
            }
            // Remaining samples (segmentLength..nfft-1) are zero-padded
            
            // FFT both segments
            val (xReal, xImag) = FastFourierTransform.fft(segX)
            val (yReal, yImag) = FastFourierTransform.fft(segY)
            
            // Accumulate periodograms
            for (k in 0 until halfNfft) {
                // Auto-spectra: |X(k)|² and |Y(k)|²
                sxxAccum[k] += xReal[k] * xReal[k] + xImag[k] * xImag[k]
                syyAccum[k] += yReal[k] * yReal[k] + yImag[k] * yImag[k]
                
                // Cross-spectrum: X*(k) · Y(k) = (xR - j·xI)(yR + j·yI)
                sxyRealAccum[k] += xReal[k] * yReal[k] + xImag[k] * yImag[k]
                sxyImagAccum[k] += xImag[k] * yReal[k] - xReal[k] * yImag[k]
            }
            
            nSegments++
            offset += step
        }
        
        if (nSegments == 0) {
            // Signal too short — return empty result
            val freqs = DoubleArray(halfNfft) { it * fs / nfft }
            return WelchResult(freqs, DoubleArray(halfNfft), DoubleArray(halfNfft),
                DoubleArray(halfNfft), DoubleArray(halfNfft), DoubleArray(halfNfft), 0)
        }
        
        // Average and normalize
        val psdX = DoubleArray(halfNfft)
        val psdY = DoubleArray(halfNfft)
        val crossReal = DoubleArray(halfNfft)
        val crossImag = DoubleArray(halfNfft)
        val coherence = DoubleArray(halfNfft)
        val freqs = DoubleArray(halfNfft) { it * fs / nfft }
        
        for (k in 0 until halfNfft) {
            psdX[k] = sxxAccum[k] / (nSegments * normFactor)
            psdY[k] = syyAccum[k] / (nSegments * normFactor)
            crossReal[k] = sxyRealAccum[k] / (nSegments * normFactor)
            crossImag[k] = sxyImagAccum[k] / (nSegments * normFactor)
            
            // Coherence: γ²(f) = |S_xy|² / (S_xx · S_yy)
            val crossMagSq = crossReal[k] * crossReal[k] + crossImag[k] * crossImag[k]
            val denom = psdX[k] * psdY[k]
            coherence[k] = if (denom > 1e-20) (crossMagSq / denom).coerceIn(0.0, 1.0) else 0.0
        }
        
        return WelchResult(freqs, psdX, psdY, crossReal, crossImag, coherence, nSegments)
    }
    
    /**
     * Compute PSD of a single signal using Welch's method.
     */
    fun psd(
        x: DoubleArray,
        fs: Double,
        segmentLength: Int = 256,
        overlapFraction: Double = 0.5
    ): Pair<DoubleArray, DoubleArray> {
        val result = crossSpectral(x, x, fs, segmentLength, overlapFraction)
        return Pair(result.freqs, result.psdX)
    }
    
    /**
     * Find dominant frequency in a given band.
     */
    fun dominantFrequency(
        freqs: DoubleArray,
        psd: DoubleArray,
        bandLow: Double,
        bandHigh: Double
    ): Double {
        var maxPower = -1.0
        var maxFreq = (bandLow + bandHigh) / 2.0
        for (i in freqs.indices) {
            if (freqs[i] in bandLow..bandHigh && psd[i] > maxPower) {
                maxPower = psd[i]
                maxFreq = freqs[i]
            }
        }
        return maxFreq
    }
    
    /**
     * Mean coherence over specific frequency bins.
     */
    fun meanCoherence(
        freqs: DoubleArray,
        coherence: DoubleArray,
        targetFreqs: List<Double>,
        bandwidth: Double = 0.15
    ): Double {
        var sum = 0.0
        var count = 0
        for (fTarget in targetFreqs) {
            for (i in freqs.indices) {
                if (freqs[i] >= fTarget - bandwidth && freqs[i] <= fTarget + bandwidth) {
                    sum += coherence[i]
                    count++
                }
            }
        }
        return if (count > 0) sum / count else 0.0
    }
    
    // Helper: compute slice mean without allocation
    private fun DoubleArray.sliceMean(offset: Int, length: Int): Double {
        var sum = 0.0
        for (i in offset until offset + length) sum += this[i]
        return sum / length
    }
}
