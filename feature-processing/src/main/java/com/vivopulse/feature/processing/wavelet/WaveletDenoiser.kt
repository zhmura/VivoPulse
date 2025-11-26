package com.vivopulse.feature.processing.wavelet

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lightweight Wavelet Denoiser (Haar DWT).
 *
 * Uses Haar wavelet for O(N) complexity and minimal artifacts on sharp features (like peaks).
 * Implements Soft Thresholding.
 */
object WaveletDenoiser {

    data class Config(
        val levels: Int = 4,
        val thresholdStrategy: ThresholdStrategy = ThresholdStrategy.Soft,
        val baseThresholdFactor: Double = 1.0
    )

    enum class ThresholdStrategy {
        Soft, Hard
    }

    /**
     * Denoise 1D signal using Discrete Wavelet Transform.
     */
    fun denoise(signal: DoubleArray, config: Config): DoubleArray {
        if (signal.isEmpty()) return doubleArrayOf()
        
        // 1. Pad to power of 2
        val n = signal.size
        val paddedSize = nextPowerOf2(n)
        val padded = DoubleArray(paddedSize)
        System.arraycopy(signal, 0, padded, 0, n)
        // Mirror padding for edges
        for (i in n until paddedSize) {
            padded[i] = signal[n - 1 - (i - n)]
        }

        // 2. Forward DWT (Haar)
        val coeffs = forwardDWT(padded, config.levels)

        // 3. Thresholding
        // Estimate noise level (sigma) from finest detail coefficients (level 1)
        // Median Absolute Deviation (MAD) estimate: sigma = median(|d|) / 0.6745
        val detailLevel1StartIndex = paddedSize / 2
        val detailLevel1EndIndex = paddedSize
        val details = coeffs.sliceArray(detailLevel1StartIndex until detailLevel1EndIndex)
        val sigma = medianAbsoluteDeviation(details) / 0.6745
        
        val threshold = sigma * sqrt(2.0 * kotlin.math.log(paddedSize.toDouble(), Math.E)) * config.baseThresholdFactor

        // Apply threshold to all detail coefficients
        // Approximation: detail coeffs are from index 2^(levels_total - levels) to end?
        // Haar decomposition stores: [Approximation, Detail L, Detail L-1, ... Detail 1]
        // Details start after the lowest approximation level.
        // Approx size = N / 2^levels.
        val approxSize = paddedSize shr config.levels
        
        for (i in approxSize until paddedSize) {
            coeffs[i] = threshold(coeffs[i], threshold, config.thresholdStrategy)
        }

        // 4. Inverse DWT
        val denoisedPadded = inverseDWT(coeffs, config.levels)

        // 5. Unpad
        return denoisedPadded.sliceArray(0 until n)
    }

    private val SQRT_2 = sqrt(2.0)

    private fun forwardDWT(data: DoubleArray, levels: Int): DoubleArray {
        var current = data.clone()
        var n = current.size
        
        for (l in 0 until levels) {
            val next = DoubleArray(n)
            val half = n / 2
            for (i in 0 until half) {
                // Haar scaling (normalized)
                val sum = current[2 * i] + current[2 * i + 1]
                val diff = current[2 * i] - current[2 * i + 1]
                next[i] = sum / SQRT_2
                next[half + i] = diff / SQRT_2
            }
            System.arraycopy(next, 0, current, 0, n)
            n /= 2
        }
        return current
    }

    private fun inverseDWT(coeffs: DoubleArray, levels: Int): DoubleArray {
        var current = coeffs.clone()
        val totalSize = coeffs.size
        var currentSize = totalSize shr (levels - 1) 
        
        for (l in 0 until levels) {
            val half = currentSize / 2
            val next = DoubleArray(currentSize)
            for (i in 0 until half) {
                // Inverse Haar
                val s = current[i]
                val d = current[half + i]
                next[2 * i] = (s + d) / SQRT_2
                next[2 * i + 1] = (s - d) / SQRT_2
            }
            System.arraycopy(next, 0, current, 0, currentSize)
            currentSize *= 2
        }
        return current
    }

    private fun threshold(value: Double, threshold: Double, strategy: ThresholdStrategy): Double {
        return when (strategy) {
            ThresholdStrategy.Hard -> if (abs(value) > threshold) value else 0.0
            ThresholdStrategy.Soft -> {
                if (abs(value) > threshold) {
                    if (value > 0) value - threshold else value + threshold
                } else {
                    0.0
                }
            }
        }
    }

    private fun medianAbsoluteDeviation(data: DoubleArray): Double {
        if (data.isEmpty()) return 0.0
        val median = median(data)
        val deviations = data.map { abs(it - median) }.toDoubleArray()
        return median(deviations)
    }

    private fun median(data: DoubleArray): Double {
        if (data.isEmpty()) return 0.0
        val sorted = data.sorted()
        val n = sorted.size
        return if (n % 2 == 0) {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        } else {
            sorted[n / 2]
        }
    }

    private fun nextPowerOf2(n: Int): Int {
        var count = 0
        var v = n
        if (v > 0 && (v and (v - 1)) == 0) return v
        while (v != 0) {
            v = v shr 1
            count++
        }
        return 1 shl count
    }
}

