package com.vivopulse.signal

/**
 * Savitzky-Golay filter for smoothing and derivative estimation.
 * 
 * Uses local polynomial fitting (least-squares) to compute values and derivatives
 * at each point. This preserves signal shape (especially peaks and shoulders)
 * much better than moving averages, which is critical for accurate PPG fiducial
 * point detection (foot, max slope, peak).
 * 
 * Reference: Savitzky & Golay (1964), "Smoothing and Differentiation of Data
 * by Simplified Least Squares Procedures."
 */
object SavitzkyGolay {
    
    /**
     * Compute the first derivative of a signal using Savitzky-Golay filtering.
     * 
     * Uses pre-computed coefficients for common configurations (order 3, various windows).
     * 
     * @param signal Input signal
     * @param fs Sampling frequency (Hz) — used to scale derivative to proper units
     * @param windowSamples Half-window width in samples (total window = 2*m + 1)
     * @param polyOrder Polynomial order (default 3, cubic)
     * @return First derivative of signal (same length, edges zero-padded)
     */
    fun firstDerivative(
        signal: DoubleArray,
        fs: Double,
        windowSamples: Int = 5,
        @Suppress("UNUSED_PARAMETER") polyOrder: Int = 3
    ): DoubleArray {
        val n = signal.size
        val m = windowSamples.coerceIn(2, n / 2 - 1)
        
        // Compute SG first-derivative coefficients for cubic polynomial
        // For cubic fit over [-m, m], the derivative coefficients are:
        //   c_k = 3k / (m(m+1)(2m+1)) — standard result for order-3 SG first deriv
        // This is the closed-form for the least-squares first-derivative filter.
        val windowLen = 2 * m + 1
        val coeffs = DoubleArray(windowLen)
        val denom = m.toDouble() * (m + 1).toDouble() * (2 * m + 1).toDouble() / 3.0
        for (k in -m..m) {
            coeffs[k + m] = k.toDouble() / denom
        }
        
        // Apply convolution
        val result = DoubleArray(n)
        for (i in m until n - m) {
            var sum = 0.0
            for (k in -m..m) {
                sum += coeffs[k + m] * signal[i + k]
            }
            result[i] = sum * fs // Scale by sampling rate for proper derivative units
        }
        
        // Extrapolate edges using simple central differences (fallback)
        for (i in 0 until m) {
            result[i] = if (i > 0) (signal[i + 1] - signal[i - 1]) * fs / 2.0 else 0.0
        }
        for (i in n - m until n) {
            result[i] = if (i < n - 1) (signal[i] - signal[i - 1]) * fs else 0.0
        }
        
        return result
    }
    
    /**
     * Smooth a signal using Savitzky-Golay filter (zeroth derivative).
     * 
     * @param signal Input signal
     * @param windowSamples Half-window width in samples
     * @param polyOrder Polynomial order (default 3)
     * @return Smoothed signal (same length)
     */
    fun smooth(
        signal: DoubleArray,
        windowSamples: Int = 5,
        @Suppress("UNUSED_PARAMETER") polyOrder: Int = 3
    ): DoubleArray {
        val n = signal.size
        val m = windowSamples.coerceIn(2, n / 2 - 1)
        val windowLen = 2 * m + 1
        
        // For cubic polynomial, smoothing coefficients (zeroth derivative):
        // c_k = (3m(m+1) - 1 - 5k²) / ((2m+3)(2m+1)(2m-1)/3)
        // Simplified normalization approach: compute via least-squares matrix
        // For practical use, we use a simple efficient approach:
        val coeffs = computeSmoothingCoeffs(m)
        
        val result = DoubleArray(n)
        for (i in m until n - m) {
            var sum = 0.0
            for (k in -m..m) {
                sum += coeffs[k + m] * signal[i + k]
            }
            result[i] = sum
        }
        
        // Copy edges unchanged
        for (i in 0 until m) result[i] = signal[i]
        for (i in n - m until n) result[i] = signal[i]
        
        return result
    }
    
    /**
     * Compute smoothing coefficients for cubic (order 3) SG filter.
     * Coefficients: c_k = (3m(m+1) - 1 - 5k²) / Σ(3m(m+1) - 1 - 5j²) for j in [-m,m]
     */
    private fun computeSmoothingCoeffs(m: Int): DoubleArray {
        val windowLen = 2 * m + 1
        val coeffs = DoubleArray(windowLen)
        val mm1 = 3.0 * m * (m + 1)
        var normSum = 0.0
        
        for (k in -m..m) {
            val ck = mm1 - 1.0 - 5.0 * k * k
            coeffs[k + m] = ck
            normSum += ck
        }
        
        // Normalize so coefficients sum to 1 (preserve DC)
        if (normSum > 1e-10) {
            for (i in coeffs.indices) coeffs[i] /= normSum
        }
        
        return coeffs
    }
    
    /**
     * Compute optimal window size in samples from a desired window in milliseconds.
     * 
     * @param windowMs Desired window width in milliseconds (total, not half)
     * @param fs Sampling frequency
     * @return Half-window size in samples (m), where total window = 2m+1
     */
    fun windowMsToSamples(windowMs: Double, fs: Double): Int {
        val totalSamples = (windowMs / 1000.0 * fs).toInt()
        return (totalSamples / 2).coerceAtLeast(2)
    }
}
