package com.vivopulse.signal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fast Fourier Transform (Cooley-Tukey algorithm).
 * 
 * Optimized for power-of-two lengths.
 */
object FastFourierTransform {

    /**
     * Compute forward FFT.
     * 
     * @param real Real part of input
     * @param imag Imaginary part of input (optional, defaults to zeros)
     * @return Pair(real, imag) output arrays
     */
    fun fft(real: DoubleArray, imag: DoubleArray = DoubleArray(real.size)): Pair<DoubleArray, DoubleArray> {
        val n = real.size
        if (n == 0) return Pair(doubleArrayOf(), doubleArrayOf())
        if ((n and (n - 1)) != 0) {
            throw IllegalArgumentException("FFT size must be power of 2")
        }

        val xReal = real.clone()
        val xImag = imag.clone()

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempReal = xReal[i]
                val tempImag = xImag[i]
                xReal[i] = xReal[j]
                xImag[i] = xImag[j]
                xReal[j] = tempReal
                xImag[j] = tempImag
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        // Butterfly computations
        var l = 2
        while (l <= n) {
            val halfL = l / 2
            val theta = -2.0 * PI / l
            val wRealStep = cos(theta)
            val wImagStep = sin(theta)
            
            var wReal = 1.0
            var wImag = 0.0
            
            for (k in 0 until halfL) {
                for (i in k until n step l) {
                    val butterflyIndex = i + halfL
                    val tempReal = wReal * xReal[butterflyIndex] - wImag * xImag[butterflyIndex]
                    val tempImag = wReal * xImag[butterflyIndex] + wImag * xReal[butterflyIndex]
                    
                    xReal[butterflyIndex] = xReal[i] - tempReal
                    xImag[butterflyIndex] = xImag[i] - tempImag
                    xReal[i] += tempReal
                    xImag[i] += tempImag
                }
                val nextWReal = wReal * wRealStep - wImag * wImagStep
                wImag = wReal * wImagStep + wImag * wRealStep
                wReal = nextWReal
            }
            l *= 2
        }

        return Pair(xReal, xImag)
    }
    
    /**
     * Compute inverse FFT.
     *
     * Uses conjugate trick: IFFT(X) = conj(FFT(conj(X))) / N
     *
     * @param real Real part of frequency domain input
     * @param imag Imaginary part of frequency domain input
     * @return Pair(real, imag) time-domain output arrays
     */
    fun ifft(real: DoubleArray, imag: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = real.size
        if (n == 0) return Pair(doubleArrayOf(), doubleArrayOf())

        // Conjugate input
        val conjImag = DoubleArray(n) { -imag[it] }

        // Forward FFT on conjugated input
        val (outReal, outImag) = fft(real, conjImag)

        // Conjugate and scale by 1/N
        val invN = 1.0 / n
        for (i in 0 until n) {
            outReal[i] *= invN
            outImag[i] = -outImag[i] * invN
        }

        return Pair(outReal, outImag)
    }

    /**
     * Find next power of 2.
     */
    fun nextPowerOf2(n: Int): Int {
        var p = 1
        while (p < n) {
            p = p shl 1
        }
        return p
    }
}

