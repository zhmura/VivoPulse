package com.vivopulse.feature.processing.sync

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Metrics for synchronization between Face and Finger signals.
 */
data class SyncMetricsResult(
    val correlation: Double,
    val lagMs: Double,
    val fwhmMs: Double,
    val hrDeltaBpm: Double
)

object SyncMetrics {

    /**
     * Compute synchronization metrics.
     *
     * @param faceSig Face signal (filtered, 100Hz)
     * @param fingerSig Finger signal (filtered, 100Hz)
     * @param hrFaceBpm Heart rate from face
     * @param hrFingerBpm Heart rate from finger
     * @param fsHz Sampling frequency (e.g. 100.0)
     * @return SyncMetricsResult
     */
    fun computeMetrics(
        faceSig: DoubleArray,
        fingerSig: DoubleArray,
        hrFaceBpm: Double,
        hrFingerBpm: Double,
        fsHz: Double
    ): SyncMetricsResult {
        val hrDelta = abs(hrFaceBpm - hrFingerBpm)
        
        // Cross-correlation
        val (corr, lagSamples, fwhmSamples) = computeCrossCorrelation(faceSig, fingerSig)
        
        val lagMs = (lagSamples / fsHz) * 1000.0
        val fwhmMs = (fwhmSamples / fsHz) * 1000.0
        
        return SyncMetricsResult(
            correlation = corr,
            lagMs = lagMs,
            fwhmMs = fwhmMs,
            hrDeltaBpm = hrDelta
        )
    }
    
    private fun computeCrossCorrelation(
        x: DoubleArray,
        y: DoubleArray
    ): Triple<Double, Double, Double> {
        if (x.size != y.size || x.isEmpty()) return Triple(0.0, 0.0, 0.0)
        
        val n = x.size
        val maxLag = n / 2 // +/- half window
        
        var maxCorr = -1.0
        var bestLag = 0
        
        // Normalize inputs
        val xMean = x.average()
        val yMean = y.average()
        
        var xVar = 0.0
        var yVar = 0.0
        
        val xNorm = DoubleArray(n)
        val yNorm = DoubleArray(n)
        
        for (i in 0 until n) {
            xNorm[i] = x[i] - xMean
            yNorm[i] = y[i] - yMean
            xVar += xNorm[i] * xNorm[i]
            yVar += yNorm[i] * yNorm[i]
        }
        
        val denom = sqrt(xVar * yVar)
        if (denom < 1e-9) return Triple(0.0, 0.0, 0.0)
        
        // Compute cross-corr for lags
        // O(N*M) - okay for small windows (e.g. 8s @ 100Hz = 800 samples)
        // For larger, use FFT.
        
        val corrs = DoubleArray(2 * maxLag + 1)
        
        for (lag in -maxLag..maxLag) {
            var sum = 0.0
            var count = 0
            
            for (i in 0 until n) {
                val j = i + lag
                if (j in 0 until n) {
                    sum += xNorm[i] * yNorm[j]
                    count++
                }
            }
            
            // Unbiased estimator? Or biased?
            // Standard Pearson uses N.
            // Here we just want peak shape.
            val r = if (count > 0) sum / denom else 0.0
            corrs[lag + maxLag] = r
            
            if (r > maxCorr) {
                maxCorr = r
                bestLag = lag
            }
        }
        
        // Sub-sample interpolation for lag (Parabolic)
        val peakIdx = bestLag + maxLag
        var refinedLag = bestLag.toDouble()
        
        if (peakIdx > 0 && peakIdx < corrs.size - 1) {
            val alpha = corrs[peakIdx - 1]
            val beta = corrs[peakIdx]
            val gamma = corrs[peakIdx + 1]
            val p = 0.5 * (alpha - gamma) / (alpha - 2 * beta + gamma)
            refinedLag += p
        }
        
        // FWHM (Full Width at Half Maximum)
        // Find width where corr >= maxCorr / 2
        val halfMax = maxCorr * 0.5
        var leftIdx = peakIdx
        while (leftIdx > 0 && corrs[leftIdx] > halfMax) {
            leftIdx--
        }
        var rightIdx = peakIdx
        while (rightIdx < corrs.size - 1 && corrs[rightIdx] > halfMax) {
            rightIdx++
        }
        
        val fwhm = (rightIdx - leftIdx).toDouble()
        
        return Triple(maxCorr, refinedLag, fwhm)
    }
}
