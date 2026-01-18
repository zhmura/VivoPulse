package com.vivopulse.signal

import kotlin.math.sqrt

/**
 * Implementation of the Plane-Orthogonal-to-Skin (POS) algorithm for rPPG.
 * 
 * Wang, W., den Brinker, A. C., Stuijk, S., & de Haan, G. (2016). 
 * Algorithmic principles of remote PPG. IEEE Transactions on Biomedical Engineering.
 */
object PosExtractor {

    /**
     * Compute POS signal from RGB traces.
     * 
     * Uses a sliding window approach to calculate the projection plane orthogonal to the skin tone.
     * 
     * @param r Red channel trace
     * @param g Green channel trace
     * @param b Blue channel trace
     * @param fsHz Sample rate in Hz
     * @param windowSec Window size in seconds (default 1.6s)
     * @return Extracted pulse signal
     */
    fun computePosSignal(
        r: DoubleArray, 
        g: DoubleArray, 
        b: DoubleArray, 
        fsHz: Double,
        windowSec: Double = 1.6
    ): DoubleArray {
        val n = r.size
        if (g.size != n || b.size != n) {
            throw IllegalArgumentException("RGB arrays must have same length")
        }
        
        // Window size in samples
        val l = (windowSec * fsHz).toInt()
        if (l >= n) return DoubleArray(n) // Not enough data for one window
        
        val h = DoubleArray(n)
        
        // Sliding window with stride 1 (or larger for efficiency, stride 1 is smoothest)
        // Paper suggests temporal filtering happens naturally via overlap-add
        
        // We will use a standard implementation:
        // iterate t from 0 to N-L
        //   Cn = normalize(C[t:t+L])
        //   S = Xs + alpha*Ys
        //   H[t:t+L] += S + Hanning window
        
        // Simplified non-overlap-add version (block based) usually has artifacts at boundaries.
        // We will use overlap-add.
        
        val window = createHanningWindow(l)
        val normalization = DoubleArray(n)
        
        // Stride: 1 creates best quality but is slow. 
        // Stride: L/2 or L/4 is common. Let's use stride = 1 for now if N is small (< 10000 samples aka 100s).
        // If performance is an issue, we can increase stride.
        val stride = 1
        
        for (t in 0 until n - l step stride) {
            // 1. Temporal normalization in window
            // Cn(t) = C(t) / mean(C)
            var meanR = 0.0
            var meanG = 0.0
            var meanB = 0.0
            
            for (i in 0 until l) {
                meanR += r[t + i]
                meanG += g[t + i]
                meanB += b[t + i]
            }
            meanR /= l
            meanG /= l
            meanB /= l
            
            // To avoid divide by zero
            if (meanR < 1e-6) meanR = 1.0
            if (meanG < 1e-6) meanG = 1.0
            if (meanB < 1e-6) meanB = 1.0
            
            // 2. Projection
            // Xs = G/meanG - B/meanB
            // Ys = G/meanG + B/meanB - 2*R/meanR
            
            // We compute the signal over the window length
            // And calculate alpha = std(Xs) / std(Ys)
            
            val xs = DoubleArray(l)
            val ys = DoubleArray(l)
            
            var sumXs = 0.0
            var sumYs = 0.0
            
            for (i in 0 until l) {
                val rn = r[t + i] / meanR
                val gn = g[t + i] / meanG
                val bn = b[t + i] / meanB
                
                xs[i] = gn - bn
                ys[i] = gn + bn - 2 * rn
                
                sumXs += xs[i]
                sumYs += ys[i]
            }
            
            val meanXs = sumXs / l
            val meanYs = sumYs / l
            
            var stdXs = 0.0
            var stdYs = 0.0
            
            for (i in 0 until l) {
                stdXs += (xs[i] - meanXs) * (xs[i] - meanXs)
                stdYs += (ys[i] - meanYs) * (ys[i] - meanYs)
            }
            stdXs = sqrt(stdXs / l)
            stdYs = sqrt(stdYs / l)
            
            val alpha = if (stdYs > 1e-6) stdXs / stdYs else 0.0
            
            // 3. Fusion
            // S = Xs + alpha * Ys
            for (i in 0 until l) {
                val s = xs[i] + alpha * ys[i]
                
                // Overlap-add with Hanning window
                h[t + i] += s * window[i]
                normalization[t + i] += window[i]
            }
        }
        
        // Restore amplitude (divide by sum of weights)
        for (i in 0 until n) {
            if (normalization[i] > 1e-6) {
                h[i] /= normalization[i]
            }
        }
        
        return h
    }
    
    private fun createHanningWindow(size: Int): DoubleArray {
        return DoubleArray(size) { i ->
            0.5 * (1.0 - kotlin.math.cos(2.0 * kotlin.math.PI * i / (size - 1)))
        }
    }
}
