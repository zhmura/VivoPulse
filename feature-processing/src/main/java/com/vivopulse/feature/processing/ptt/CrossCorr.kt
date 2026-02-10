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
        
        // P3-B: Max lag ±400ms — aligned with foot-to-foot detection range
        val maxLagSamples = (fsHz * 0.4).toInt() // 400ms
        
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
        
        // P3-B: Confidence decay for lags beyond physiological sweet spot (200ms)
        val lagConfidence = if (abs(lagMs) > 200.0) {
            1.0 - ((abs(lagMs) - 200.0) / 300.0).coerceIn(0.0, 0.5)
        } else 1.0
        
        return CrossCorrResult(
            lagMs = lagMs,
            corrScore = maxCorr,
            peakSharpness = peakSharpness,
            isValid = true,
            lagSamples = refinedLag,
            lagConfidence = lagConfidence,
            message = "PTT=${String.format("%.2f", lagMs)}ms, Corr=${String.format("%.3f", maxCorr)}, Sharp=${String.format("%.3f", peakSharpness)}, LagConf=${String.format("%.2f", lagConfidence)}"
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

    // ═══════════════════════════════════════════════════════════════════
    // R3-A: GCC-PHAT (Generalized Cross-Correlation — Phase Transform)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Compute time delay using GCC-PHAT.
     *
     * Unlike Pearson NCC, GCC-PHAT whitens the cross-power spectrum
     * (divides by magnitude), keeping only phase information.
     * This produces sharper peaks and is more robust under
     * amplitude distortion (AE steps, varying SNR, illumination changes).
     *
     * R_PHAT(τ) = IFFT[ X*(f)·Y(f) / |X*(f)·Y(f)| ]
     *
     * @param x First signal (e.g., face PPG)
     * @param y Second signal (e.g., finger PPG)
     * @param fsHz Sample rate in Hz
     * @param minLagMs Minimum physiological lag (default 30ms)
     * @param maxLagMs Maximum physiological lag (default 400ms)
     * @param windowSec Window duration in seconds (default 20.0s)
     * @param beta PHAT weighting exponent: 1.0 = full whitening, 0.0 = standard CC
     * @return CrossCorrResult with lag, correlation, peak sharpness
     */
    fun gccPhatLag(
        x: DoubleArray,
        y: DoubleArray,
        fsHz: Double,
        minLagMs: Double = 30.0,
        maxLagMs: Double = 400.0,
        windowSec: Double = 20.0,
        beta: Double = 0.8
    ): CrossCorrResult {
        // Use last windowSec of signal
        val windowSamples = (windowSec * fsHz).toInt()
        val startIdx = maxOf(0, x.size - windowSamples)
        
        val xWindow = x.sliceArray(startIdx until x.size)
        val yWindow = y.sliceArray(startIdx until y.size)
        val n = minOf(xWindow.size, yWindow.size)
        
        if (n < 64) {
            return CrossCorrResult(
                lagMs = 0.0, corrScore = 0.0, peakSharpness = 0.0,
                isValid = false, message = "Insufficient samples for GCC-PHAT (need ≥64)"
            )
        }
        
        // Zero-pad to next power of 2
        val nfft = com.vivopulse.signal.FastFourierTransform.nextPowerOf2(n * 2)
        
        val xPad = DoubleArray(nfft)
        val yPad = DoubleArray(nfft)
        System.arraycopy(xWindow, 0, xPad, 0, minOf(n, xWindow.size))
        System.arraycopy(yWindow, 0, yPad, 0, minOf(n, yWindow.size))
        
        // Remove mean (DC removal)
        val xMean = xPad.take(n).average()
        val yMean = yPad.take(n).average()
        for (i in 0 until n) { xPad[i] -= xMean; yPad[i] -= yMean }
        
        // Forward FFT
        val (xReal, xImag) = com.vivopulse.signal.FastFourierTransform.fft(xPad)
        val (yReal, yImag) = com.vivopulse.signal.FastFourierTransform.fft(yPad)
        
        // Cross-power spectrum: G = X* · Y
        // Then PHAT whitening: W = G / |G|^beta
        val gReal = DoubleArray(nfft)
        val gImag = DoubleArray(nfft)
        
        for (i in 0 until nfft) {
            // X* · Y = (xR - j·xI)(yR + j·yI) = (xR·yR + xI·yI) + j(xR·yI - xI·yR)
            val cpReal = xReal[i] * yReal[i] + xImag[i] * yImag[i]
            val cpImag = xReal[i] * yImag[i] - xImag[i] * yReal[i]
            
            val magnitude = sqrt(cpReal * cpReal + cpImag * cpImag)
            
            if (magnitude > 1e-12) {
                // PHAT whitening: divide by |G|^beta
                val weight = magnitude.pow(beta)
                gReal[i] = cpReal / weight
                gImag[i] = cpImag / weight
            }
        }
        
        // IFFT to get GCC-PHAT correlation
        val (rReal, _) = com.vivopulse.signal.FastFourierTransform.ifft(gReal, gImag)
        
        // Extract lags in physiological range [minLagMs, maxLagMs]
        val minLagSamples = (minLagMs * fsHz / 1000.0).toInt()
        val maxLagSamples = (maxLagMs * fsHz / 1000.0).toInt()
        
        // In IFFT output, positive lags are at indices [0, nfft/2)
        // and negative lags at indices [nfft/2, nfft)
        // We want lags in [minLag, maxLag] (positive direction: y delayed relative to x)
        var bestLag = 0
        var bestVal = -Double.MAX_VALUE
        
        for (lag in minLagSamples..minOf(maxLagSamples, nfft / 2 - 1)) {
            if (rReal[lag] > bestVal) {
                bestVal = rReal[lag]
                bestLag = lag
            }
        }
        
        // Also check negative lags (face delayed relative to finger — unusual but possible)
        for (lag in minLagSamples..minOf(maxLagSamples, nfft / 2 - 1)) {
            val idx = nfft - lag
            if (idx >= 0 && idx < nfft && rReal[idx] > bestVal) {
                bestVal = rReal[idx]
                bestLag = -lag
            }
        }
        
        // Sub-sample refinement via quadratic interpolation
        val peakIdx = if (bestLag >= 0) bestLag else nfft + bestLag
        val refinedLag = if (peakIdx > 0 && peakIdx < nfft - 1) {
            val y1 = rReal[peakIdx - 1]
            val y2 = rReal[peakIdx]
            val y3 = rReal[peakIdx + 1]
            val a = (y1 + y3) / 2.0 - y2
            val b = (y3 - y1) / 2.0
            val delta = if (abs(a) > 1e-10) -b / (2.0 * a) else 0.0
            bestLag.toDouble() + delta
        } else {
            bestLag.toDouble()
        }
        
        val lagMs = refinedLag * 1000.0 / fsHz

        // Peak sharpness: ratio of peak to mean of its neighbourhood
        val sharpness = if (peakIdx > 2 && peakIdx < nfft - 3) {
            val neighborMean = (rReal[peakIdx - 2] + rReal[peakIdx - 1] + 
                                rReal[peakIdx + 1] + rReal[peakIdx + 2]) / 4.0
            if (neighborMean > 1e-12) bestVal / neighborMean else 0.0
        } else 0.0
        
        // Normalize correlation score to [0, 1]
        val maxR = rReal.maxOrNull() ?: 1.0
        val corrScore = if (maxR > 1e-12) (bestVal / maxR).coerceIn(0.0, 1.0) else 0.0
        
        // Confidence decay beyond 200ms
        val lagConfidence = if (abs(lagMs) > 200.0) {
            1.0 - ((abs(lagMs) - 200.0) / 300.0).coerceIn(0.0, 0.5)
        } else 1.0
        
        return CrossCorrResult(
            lagMs = lagMs,
            corrScore = corrScore,
            peakSharpness = sharpness.coerceIn(0.0, 1.0),
            isValid = true,
            lagSamples = refinedLag,
            lagConfidence = lagConfidence,
            message = "GCC-PHAT: PTT=${"%.2f".format(lagMs)}ms, β=${"%.1f".format(beta)}, Sharp=${"%.3f".format(sharpness)}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // R3-B: Multi-Window Delay Stability
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Compute GCC-PHAT lag across multiple overlapping subwindows.
     *
     * Measures delay stability: if lag varies wildly across windows,
     * it's likely noise, not a real physiological delay.
     *
     * @param x First signal (face PPG)
     * @param y Second signal (finger PPG)
     * @param fsHz Sample rate in Hz
     * @param windowSec Subwindow duration (default 5.0s)
     * @param overlapFrac Overlap fraction (default 0.5 = 50%)
     * @param minLagMs Minimum physiological lag (default 30ms)
     * @param maxLagMs Maximum physiological lag (default 400ms)
     * @return MultiWindowResult with median lag, MAD, stability score
     */
    fun multiWindowLag(
        x: DoubleArray,
        y: DoubleArray,
        fsHz: Double,
        windowSec: Double = 5.0,
        overlapFrac: Double = 0.5,
        minLagMs: Double = 30.0,
        maxLagMs: Double = 400.0
    ): MultiWindowResult {
        val windowSamples = (windowSec * fsHz).toInt()
        val stepSamples = ((1.0 - overlapFrac) * windowSamples).toInt()
        val n = minOf(x.size, y.size)
        
        if (n < windowSamples) {
            // Signal too short for multi-window — single-window fallback
            val single = gccPhatLag(x, y, fsHz, minLagMs, maxLagMs, windowSec)
            return MultiWindowResult(
                medianLagMs = single.lagMs,
                madMs = 0.0,
                stabilityScore = if (single.isValid) 0.5 else 0.0, // Single window = uncertain
                perWindowLagMs = listOf(single.lagMs),
                perWindowCorr = listOf(single.corrScore),
                isValid = single.isValid,
                message = "Single window (signal too short for multi-window)"
            )
        }
        
        val windowLags = mutableListOf<Double>()
        val windowCorrs = mutableListOf<Double>()
        
        var start = 0
        while (start + windowSamples <= n) {
            val xSub = x.sliceArray(start until start + windowSamples)
            val ySub = y.sliceArray(start until start + windowSamples)
            
            val result = gccPhatLag(xSub, ySub, fsHz, minLagMs, maxLagMs, windowSec)
            if (result.isValid) {
                windowLags.add(result.lagMs)
                windowCorrs.add(result.corrScore)
            }
            
            start += stepSamples
        }
        
        if (windowLags.size < 2) {
            return MultiWindowResult(
                medianLagMs = windowLags.firstOrNull() ?: 0.0,
                madMs = 0.0,
                stabilityScore = 0.0,
                perWindowLagMs = windowLags,
                perWindowCorr = windowCorrs,
                isValid = false,
                message = "Too few valid subwindows (${windowLags.size})"
            )
        }
        
        // Median lag
        val sorted = windowLags.sorted()
        val median = if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        } else {
            sorted[sorted.size / 2]
        }
        
        // MAD (Median Absolute Deviation)
        val absDevs = windowLags.map { abs(it - median) }.sorted()
        val mad = if (absDevs.size % 2 == 0) {
            (absDevs[absDevs.size / 2 - 1] + absDevs[absDevs.size / 2]) / 2.0
        } else {
            absDevs[absDevs.size / 2]
        }
        
        // Stability score: 1.0 if MAD < 5ms, decays linearly, 0.0 if MAD > 20ms
        val stabilityScore = (1.0 - (mad / 20.0)).coerceIn(0.0, 1.0)
        
        return MultiWindowResult(
            medianLagMs = median,
            madMs = mad,
            stabilityScore = stabilityScore,
            perWindowLagMs = windowLags,
            perWindowCorr = windowCorrs,
            isValid = true,
            message = "MultiWindow: n=${windowLags.size}, median=${"%.1f".format(median)}ms, MAD=${"%.1f".format(mad)}ms, stability=${"%.2f".format(stabilityScore)}"
        )
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
    val lagConfidence: Double = 1.0, // P3-B: confidence decay for large lags
    val message: String = ""
)

/**
 * Multi-window delay stability result (R3-B).
 */
data class MultiWindowResult(
    val medianLagMs: Double,
    val madMs: Double,
    val stabilityScore: Double,     // 1.0 = perfectly stable; 0.0 = unstable
    val perWindowLagMs: List<Double>,
    val perWindowCorr: List<Double>,
    val isValid: Boolean,
    val message: String = ""
)
