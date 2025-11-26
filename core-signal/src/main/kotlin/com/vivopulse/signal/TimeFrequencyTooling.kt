package com.vivopulse.signal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Time-Frequency Analysis Tools.
 * 
 * Provides Short-Time Fourier Transform (STFT) capability for 
 * visualizing signal frequency content over time (spectrogram).
 */
object TimeFrequencyTooling {

    /**
     * Compute Short-Time Fourier Transform (STFT).
     * 
     * @param signal Input signal
     * @param sampleRateHz Sample rate
     * @param windowSizeSamples Window size (e.g. 256). Power of 2 recommended.
     * @param overlapSamples Overlap size (e.g. 128 for 50% overlap)
     * @return STFTResult containing magnitude matrix and axes
     */
    fun computeSTFT(
        signal: DoubleArray,
        sampleRateHz: Double,
        windowSizeSamples: Int = 256,
        overlapSamples: Int = 128
    ): STFTResult {
        if (signal.isEmpty()) return STFTResult.empty()
        
        val hopSize = windowSizeSamples - overlapSamples
        if (hopSize <= 0) throw IllegalArgumentException("Overlap must be less than window size")
        
        val numFrames = (signal.size - windowSizeSamples) / hopSize + 1
        if (numFrames <= 0) return STFTResult.empty()
        
        val numBins = windowSizeSamples / 2 + 1
        val magnitudes = Array(numFrames) { DoubleArray(numBins) }
        val timeAxis = DoubleArray(numFrames)
        val freqAxis = DoubleArray(numBins)
        
        // Precompute frequency axis
        for (k in 0 until numBins) {
            freqAxis[k] = k * sampleRateHz / windowSizeSamples
        }
        
        // Precompute Hann window
        val window = DoubleArray(windowSizeSamples) { i ->
            0.5 * (1 - cos(2 * PI * i / (windowSizeSamples - 1)))
        }
        
        // Compute STFT frames
        for (i in 0 until numFrames) {
            val start = i * hopSize
            // Apply window
            val frameReal = DoubleArray(windowSizeSamples)
            val frameImag = DoubleArray(windowSizeSamples)
            
            for (j in 0 until windowSizeSamples) {
                if (start + j < signal.size) {
                    frameReal[j] = signal[start + j] * window[j]
                }
            }
            
            // FFT (O(N^2) naive implementation for simplicity as window is small, 
            // or assume standard library if available. Since we don't have heavy FFT lib, implementing naive DFT or simple FFT)
            // For 256 samples, naive DFT is 256*256 = 65k ops per frame. 
            // For 30s signal (3000 samples), ~20 frames. Total ~1.3M ops. 
            // Acceptable for offline export.
            
            computeDFT(frameReal, frameImag, magnitudes[i])
            
            // Time axis (center of window)
            timeAxis[i] = (start + windowSizeSamples / 2.0) / sampleRateHz
        }
        
        return STFTResult(magnitudes, timeAxis, freqAxis)
    }
    
    /**
     * Simple DFT computation (Magnitude only).
     * Optimized for real input.
     */
    private fun computeDFT(inputReal: DoubleArray, inputImag: DoubleArray, outputMag: DoubleArray) {
        val N = inputReal.size
        val K = outputMag.size // N/2 + 1
        
        for (k in 0 until K) {
            var sumReal = 0.0
            var sumImag = 0.0
            val angleTerm = 2 * PI * k / N
            
            for (n in 0 until N) {
                val angle = angleTerm * n
                val cosA = cos(angle)
                val sinA = sin(angle) // sign? exp(-j...) = cos - j sin
                
                // (r + ji) * (c - js) = rc - jrs + jic - j^2is = (rc+is) + j(ic-rs)
                sumReal += inputReal[n] * cosA + inputImag[n] * sinA
                sumImag += inputImag[n] * cosA - inputReal[n] * sinA
            }
            
            outputMag[k] = sqrt(sumReal * sumReal + sumImag * sumImag)
        }
    }
}

data class STFTResult(
    val magnitudes: Array<DoubleArray>, // [time][freq]
    val timeAxis: DoubleArray,
    val freqAxis: DoubleArray
) {
    companion object {
        fun empty() = STFTResult(emptyArray(), doubleArrayOf(), doubleArrayOf())
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as STFTResult
        if (!magnitudes.contentDeepEquals(other.magnitudes)) return false
        if (!timeAxis.contentEquals(other.timeAxis)) return false
        if (!freqAxis.contentEquals(other.freqAxis)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = magnitudes.contentDeepHashCode()
        result = 31 * result + timeAxis.contentHashCode()
        result = 31 * result + freqAxis.contentHashCode()
        return result
    }
    
    /**
     * Convert to CSV format.
     * Header: time_s, freq_hz, magnitude
     */
    fun toCsv(): String {
        val sb = StringBuilder()
        sb.append("time_s")
        for (f in freqAxis) {
            sb.append(",freq_${String.format("%.1f", f)}")
        }
        sb.append("\n")
        
        for (i in timeAxis.indices) {
            sb.append(String.format("%.3f", timeAxis[i]))
            for (mag in magnitudes[i]) {
                sb.append(",${String.format("%.4f", mag)}")
            }
            sb.append("\n")
        }
        return sb.toString()
    }
}

