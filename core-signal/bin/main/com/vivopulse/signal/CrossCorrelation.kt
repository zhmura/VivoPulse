package com.vivopulse.signal

import kotlin.math.*

/**
 * Cross-correlation utilities for signal lag estimation.
 * 
 * Used for computing Pulse Transit Time (PTT) between face and finger PPG signals.
 */
object CrossCorrelation {
    
    private var debugEnabled = false
    
    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
    }
    
    private fun debug(message: String) {
        if (debugEnabled) {
            println("CrossCorrelation: $message")
        }
    }
    
    /**
     * Compute normalized cross-correlation and lag between two signals.
     * 
     * Finds the time delay that maximizes correlation between signals.
     * Uses quadratic interpolation for sub-sample accuracy.
     * 
     * @param signalX First signal (e.g., face PPG)
     * @param signalY Second signal (e.g., finger PPG)
     * @param sampleRateHz Sample rate in Hz
     * @param maxLagSamples Maximum lag to search (default: ±2 seconds worth)
     * @return CrossCorrelationResult with lag, correlation score, and confidence
     */
    fun computeLag(
        signalX: DoubleArray,
        signalY: DoubleArray,
        sampleRateHz: Double,
        maxLagSamples: Int = (sampleRateHz * 2.0).toInt()
    ): CrossCorrelationResult {
        if (signalX.isEmpty() || signalY.isEmpty()) {
            return CrossCorrelationResult(
                lagMs = 0.0,
                correlationScore = 0.0,
                isValid = false,
                message = "Empty signals"
            )
        }
        
        if (signalX.size != signalY.size) {
            return CrossCorrelationResult(
                lagMs = 0.0,
                correlationScore = 0.0,
                isValid = false,
                message = "Signal length mismatch: ${signalX.size} vs ${signalY.size}"
            )
        }
        
        // Compute cross-correlation
        val xcorr = normalizedCrossCorrelation(signalX, signalY, maxLagSamples)
        
        debug("xcorr size=${xcorr.size}, maxLagSamples=$maxLagSamples")
        
        // Find peak correlation
        val maxIndex = xcorr.indices.maxByOrNull { xcorr[it] } ?: return CrossCorrelationResult(
            lagMs = 0.0,
            correlationScore = 0.0,
            isValid = false,
            message = "Failed to find correlation peak"
        )
        
        val maxCorr = xcorr[maxIndex]
        
        // Convert index to lag (index 0 = lag -maxLagSamples, index maxLagSamples = lag 0)
        val lagSamplesInt = maxIndex - maxLagSamples
        
        // Refine with quadratic interpolation for sub-sample accuracy
        val refinedLagSamples = refineWithQuadraticInterpolation(xcorr, maxIndex, lagSamplesInt.toDouble())
        
        // Convert to milliseconds
        val lagMs = refinedLagSamples * 1000.0 / sampleRateHz
        
        debug("maxIndex=$maxIndex, lagSamplesInt=$lagSamplesInt, refinedLagSamples=$refinedLagSamples, lagMs=$lagMs, maxCorr=$maxCorr")
        
        return CrossCorrelationResult(
            lagMs = lagMs,
            correlationScore = maxCorr,
            isValid = true,
            lagSamples = refinedLagSamples,
            peakIndex = maxIndex,
            message = "Lag: ${"%.2f".format(lagMs)} ms, Corr: ${"%.3f".format(maxCorr)}"
        )
    }
    
    /**
     * Compute normalized cross-correlation between two signals.
     * 
     * Returns correlation values for lags from -maxLag to +maxLag.
     * Convention: y is shifted relative to x
     * Positive lag means y is delayed relative to x
     * 
     * @param x First signal
     * @param y Second signal  
     * @param maxLag Maximum lag in samples
     * @return Array of correlation values (length = 2*maxLag + 1)
     */
    private fun normalizedCrossCorrelation(
        x: DoubleArray,
        y: DoubleArray,
        maxLag: Int
    ): DoubleArray {
        val n = x.size
        val xcorr = DoubleArray(2 * maxLag + 1)
        
        // Compute means
        val meanX = x.average()
        val meanY = y.average()
        
        // Compute variances for normalization
        val varX = x.map { (it - meanX).pow(2) }.sum()
        val varY = y.map { (it - meanY).pow(2) }.sum()
        
        if (varX < 1e-10 || varY < 1e-10) {
            // One or both signals are constant
            debug("Constant signal detected: varX=$varX, varY=$varY")
            return xcorr
        }
        
        // Compute cross-correlation for each lag
        // Convention: positive lag means y is delayed relative to x
        // R[τ] = Σ x[i] * y[i+τ]
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
                // Normalized correlation (Pearson correlation coefficient)
                xcorr[lagIdx] = sum / sqrt(sumXX * sumYY)
            }
        }
        
        return xcorr
    }
    
    /**
     * Refine peak location using quadratic interpolation.
     * 
     * Fits a parabola through the peak and its neighbors to estimate
     * sub-sample peak location.
     * 
     * @param correlation Correlation array
     * @param peakIndex Index of peak
     * @param initialLag Initial lag estimate
     * @return Refined lag with sub-sample accuracy
     */
    private fun refineWithQuadraticInterpolation(
        correlation: DoubleArray,
        peakIndex: Int,
        initialLag: Double
    ): Double {
        // Need neighbors for interpolation
        if (peakIndex <= 0 || peakIndex >= correlation.size - 1) {
            return initialLag
        }
        
        val y1 = correlation[peakIndex - 1]
        val y2 = correlation[peakIndex]
        val y3 = correlation[peakIndex + 1]
        
        // Quadratic interpolation formula
        // Peak offset = (y1 - y3) / (2 * (y1 - 2*y2 + y3))
        val denominator = 2.0 * (y1 - 2.0 * y2 + y3)
        
        if (abs(denominator) < 1e-10) {
            // Can't interpolate (flat peak)
            return initialLag
        }
        
        val offset = (y1 - y3) / denominator
        
        // Refined lag
        return initialLag + offset
    }
    
    /**
     * Compute lag stability using sliding windows.
     * 
     * Analyzes lag variation across multiple windows to assess stability.
     * 
     * @param signalX First signal
     * @param signalY Second signal
     * @param sampleRateHz Sample rate
     * @param windowSizeS Window size in seconds (default 10s)
     * @param overlapS Overlap between windows in seconds (default 5s)
     * @return LagStabilityResult with mean, std, and per-window lags
     */
    fun computeLagStability(
        signalX: DoubleArray,
        signalY: DoubleArray,
        sampleRateHz: Double,
        windowSizeS: Double = 10.0,
        overlapS: Double = 5.0
    ): LagStabilityResult {
        val windowSize = (windowSizeS * sampleRateHz).toInt()
        val stepSize = ((windowSizeS - overlapS) * sampleRateHz).toInt()
        
        if (signalX.size < windowSize || signalY.size < windowSize) {
            return LagStabilityResult(
                meanLagMs = 0.0,
                stdLagMs = 0.0,
                isValid = false,
                message = "Signals too short for stability analysis"
            )
        }
        
        val lags = mutableListOf<Double>()
        val correlations = mutableListOf<Double>()
        
        var start = 0
        while (start + windowSize <= signalX.size) {
            val windowX = signalX.sliceArray(start until start + windowSize)
            val windowY = signalY.sliceArray(start until start + windowSize)
            
            val result = computeLag(windowX, windowY, sampleRateHz)
            
            if (result.isValid && result.correlationScore > 0.3) {
                lags.add(result.lagMs)
                correlations.add(result.correlationScore)
            }
            
            start += stepSize
        }
        
        if (lags.isEmpty()) {
            return LagStabilityResult(
                meanLagMs = 0.0,
                stdLagMs = 0.0,
                isValid = false,
                message = "No valid lag estimates found"
            )
        }
        
        // Compute statistics
        val meanLag = lags.average()
        val variance = lags.map { (it - meanLag).pow(2) }.average()
        val stdLag = sqrt(variance)
        val meanCorr = correlations.average()
        
        return LagStabilityResult(
            meanLagMs = meanLag,
            stdLagMs = stdLag,
            meanCorrelation = meanCorr,
            windowCount = lags.size,
            isValid = true,
            message = "PTT: ${"%.1f".format(meanLag)} ± ${"%.1f".format(stdLag)} ms (n=${lags.size})"
        )
    }
}

/**
 * Result of cross-correlation lag computation.
 */
data class CrossCorrelationResult(
    val lagMs: Double,
    val correlationScore: Double,
    val isValid: Boolean,
    val lagSamples: Double = 0.0,
    val peakIndex: Int = 0,
    val message: String = ""
) {
    /**
     * Check if correlation is strong enough for reliable PTT.
     */
    fun isReliable(): Boolean = isValid && correlationScore > 0.7
    
    /**
     * Check if lag is in physiologically plausible range.
     * 
     * Typical PTT: 50-150 ms
     * Extended range: 30-200 ms for safety
     */
    fun isPlausible(): Boolean = isValid && lagMs in 30.0..200.0
}

/**
 * Result of lag stability analysis.
 */
data class LagStabilityResult(
    val meanLagMs: Double,
    val stdLagMs: Double,
    val meanCorrelation: Double = 0.0,
    val windowCount: Int = 0,
    val isValid: Boolean,
    val message: String = ""
) {
    /**
     * Check if PTT is stable.
     * 
     * Stable PTT has low standard deviation across windows.
     */
    fun isStable(): Boolean = isValid && stdLagMs < 25.0
    
    /**
     * Get coefficient of variation (CV%).
     */
    fun getCoefficientOfVariation(): Double {
        return if (meanLagMs > 0) {
            (stdLagMs / meanLagMs) * 100.0
        } else {
            0.0
        }
    }
}

