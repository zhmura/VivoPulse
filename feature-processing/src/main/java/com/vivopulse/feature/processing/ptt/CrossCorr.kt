package com.vivopulse.feature.processing.ptt

import kotlin.math.*

/**
 * Cross-correlation with sub-frame interpolation for PTT estimation.
 * 
 * Uses normalized cross-correlation to find lag between face and finger PPG signals,
 * with quadratic peak interpolation for sub-sample accuracy.
 */
object CrossCorr {
    
    /**
     * Compute cross-correlation lag between two signals.
     * 
     * Formula:
     * - R[τ] = (Σ (x[i] - μ_x)(y[i+τ] - μ_y)) / √(Σ(x[i] - μ_x)² * Σ(y[i] - μ_y)²)
     * - Pearson correlation coefficient for each lag τ
     * 
     * Sub-frame refinement:
     * - Find integer lag with max correlation
     * - Apply quadratic interpolation around peak: y = ax² + bx + c
     * - Refined lag = -b/(2a) for parabola vertex
     *
     * @param x First signal (e.g., face PPG)
     * @param y Second signal (e.g., finger PPG)
     * @param fsHz Sample rate in Hz
     * @param windowSec Window duration in seconds (default 20.0s, uses last window)
     * @return CrossCorrResult with lag, correlation, peak sharpness
     */
    fun crossCorrelationLag(
        x: DoubleArray,
        y: DoubleArray,
        fsHz: Double,
        windowSec: Double = 20.0
    ): CrossCorrResult {
        // Use last windowSec of signal for correlation
        val windowSamples = (windowSec * fsHz).toInt()
        val startIdx = maxOf(0, x.size - windowSamples)
        
        val xWindow = x.sliceArray(startIdx until x.size)
        val yWindow = y.sliceArray(startIdx until y.size)
        
        if (xWindow.size < 100 || yWindow.size < 100) {
            return CrossCorrResult(
                lagMs = 0.0,
                corrScore = 0.0,
                peakSharpness = 0.0,
                isValid = false,
                message = "Insufficient samples for correlation (need ≥100)"
            )
        }
        
        // Max lag: ±200ms for PTT (physiological range 30-200ms)
        val maxLagSamples = (fsHz * 0.2).toInt() // 200ms
        
        // Compute normalized cross-correlation
        val xcorr = normalizedCrossCorrelation(xWindow, yWindow, maxLagSamples)
        
        // Find peak
        val maxIndex = xcorr.indices.maxByOrNull { xcorr[it] } ?: return CrossCorrResult(
            lagMs = 0.0,
            corrScore = 0.0,
            peakSharpness = 0.0,
            isValid = false,
            message = "Failed to find correlation peak"
        )
        
        val maxCorr = xcorr[maxIndex]
        
        // Integer lag (samples)
        val lagSamplesInt = maxIndex - maxLagSamples
        
        // Quadratic interpolation for sub-sample accuracy
        val refinedLag = quadraticPeakInterpolation(xcorr, maxIndex, lagSamplesInt.toDouble())
        
        // Peak sharpness = peak - mean of its neighbors
        val peakSharpness = computePeakSharpness(xcorr, maxIndex)
        
        // Convert to milliseconds
        val lagMs = refinedLag * 1000.0 / fsHz
        
        return CrossCorrResult(
            lagMs = lagMs,
            corrScore = maxCorr,
            peakSharpness = peakSharpness,
            isValid = true,
            lagSamples = refinedLag,
            message = "PTT=${String.format("%.2f", lagMs)}ms, Corr=${String.format("%.3f", maxCorr)}, Sharp=${String.format("%.3f", peakSharpness)}"
        )
    }
    
    /**
     * Compute normalized cross-correlation (Pearson coefficient).
     * 
     * @param x First signal
     * @param y Second signal
     * @param maxLag Maximum lag in samples
     * @return Array of correlation values for lags [-maxLag, +maxLag]
     */
    private fun normalizedCrossCorrelation(
        x: DoubleArray,
        y: DoubleArray,
        maxLag: Int
    ): DoubleArray {
        val n = x.size
        val xcorr = DoubleArray(2 * maxLag + 1)
        
        val meanX = x.average()
        val meanY = y.average()
        
        // Compute for each lag
        for (lagIdx in xcorr.indices) {
            val lag = lagIdx - maxLag
            
            var sum = 0.0
            var sumXX = 0.0
            var sumYY = 0.0
            var count = 0
            
            for (i in 0 until n) {
                val j = i + lag  // Positive lag: y is delayed
                if (j >= 0 && j < n) {
                    val xi = x[i] - meanX
                    val yj = y[j] - meanY
                    sum += xi * yj
                    sumXX += xi * xi
                    sumYY += yj * yj
                    count++
                }
            }
            
            if (count > 0 && sumXX > 1e-10 && sumYY > 1e-10) {
                xcorr[lagIdx] = sum / sqrt(sumXX * sumYY) // Pearson correlation
            }
        }
        
        return xcorr
    }
    
    /**
     * Quadratic peak interpolation for sub-sample lag refinement.
     * 
     * Fits parabola y = ax² + bx + c around peak,
     * finds vertex at x = -b/(2a) for refined peak location.
     * 
     * @param xcorr Cross-correlation array
     * @param peakIdx Peak index
     * @param integerLag Integer lag value
     * @return Refined lag with sub-sample accuracy
     */
    private fun quadraticPeakInterpolation(
        xcorr: DoubleArray,
        peakIdx: Int,
        integerLag: Double
    ): Double {
        // Need neighbors for quadratic fit
        if (peakIdx <= 0 || peakIdx >= xcorr.size - 1) {
            return integerLag
        }
        
        val y1 = xcorr[peakIdx - 1]
        val y2 = xcorr[peakIdx]
        val y3 = xcorr[peakIdx + 1]
        
        // Quadratic fit: y = ax² + bx + c
        // Using 3 points: (-1, y1), (0, y2), (1, y3)
        val a = (y1 + y3) / 2.0 - y2
        val b = (y3 - y1) / 2.0
        
        // Vertex at x = -b/(2a)
        val delta = if (abs(a) > 1e-10) {
            -b / (2.0 * a)
        } else {
            0.0 // Peak is at integer position
        }
        
        // Refined lag
        return integerLag + delta
    }
    
    /**
     * Compute peak sharpness.
     * 
     * Sharpness = peak - mean(neighbors)
     * Higher sharpness = more confident peak
     * 
     * @param xcorr Cross-correlation array
     * @param peakIdx Peak index
     * @return Peak sharpness (0-1 range typically)
     */
    private fun computePeakSharpness(
        xcorr: DoubleArray,
        peakIdx: Int
    ): Double {
        if (peakIdx <= 0 || peakIdx >= xcorr.size - 1) {
            return 0.0
        }
        
        val peak = xcorr[peakIdx]
        val leftNeighbor = xcorr[peakIdx - 1]
        val rightNeighbor = xcorr[peakIdx + 1]
        
        val meanNeighbors = (leftNeighbor + rightNeighbor) / 2.0
        
        return peak - meanNeighbors
    }
}

/**
 * Cross-correlation result.
 */
data class CrossCorrResult(
    val lagMs: Double,              // Lag in milliseconds
    val corrScore: Double,          // Correlation coefficient (0-1)
    val peakSharpness: Double,      // Peak sharpness (confidence indicator)
    val isValid: Boolean,
    val lagSamples: Double = 0.0,   // Lag in samples (with sub-sample precision)
    val message: String = ""
)

