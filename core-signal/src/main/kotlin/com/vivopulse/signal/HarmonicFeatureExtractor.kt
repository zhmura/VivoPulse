package com.vivopulse.signal

import kotlin.math.*

/**
 * Extracts harmonic features from PPG signals.
 */
object HarmonicFeatureExtractor {

    data class HarmonicFeatures(
        val fundamentalHz: Double,
        val fundamentalAmp: Double,
        val h2Amp: Double,
        val h3Amp: Double,
        val h2ToH1Ratio: Double,
        val h3ToH1Ratio: Double,
        val spectralEntropy: Double,
        val snrDb: Double
    ) {
        companion object {
            fun empty() = HarmonicFeatures(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    fun extractHarmonicFeatures(
        signal: DoubleArray,
        fsHz: Double,
        expectedHrHzRange: ClosedRange<Double> = 0.7..3.0
    ): HarmonicFeatures {
        if (signal.isEmpty()) return HarmonicFeatures.empty()

        // 1. Zero-pad to next power of 2
        val n = signal.size
        val paddedSize = FastFourierTransform.nextPowerOf2(n)
        val paddedSignal = DoubleArray(paddedSize)
        
        // Apply Hann window to reduce spectral leakage
        for (i in 0 until n) {
            val window = 0.5 * (1 - cos(2 * PI * i / (n - 1)))
            paddedSignal[i] = signal[i] * window
        }

        // 2. FFT
        val (real, imag) = FastFourierTransform.fft(paddedSignal)
        
        // 3. Compute Magnitude Spectrum (first half)
        val numBins = paddedSize / 2 + 1
        val magnitudes = DoubleArray(numBins)
        for (i in 0 until numBins) {
            magnitudes[i] = sqrt(real[i] * real[i] + imag[i] * imag[i])
        }
        
        // 4. Find Fundamental (H1) in range
        val binWidth = fsHz / paddedSize
        val minBin = (expectedHrHzRange.start / binWidth).toInt().coerceAtLeast(1)
        val maxBin = (expectedHrHzRange.endInclusive / binWidth).toInt().coerceAtMost(numBins - 1)
        
        var maxAmp = -1.0
        var maxIdx = -1
        
        for (i in minBin..maxBin) {
            if (magnitudes[i] > maxAmp) {
                maxAmp = magnitudes[i]
                maxIdx = i
            }
        }
        
        if (maxIdx == -1) return HarmonicFeatures.empty()
        
        val fundamentalHz = maxIdx * binWidth
        val fundamentalAmp = maxAmp
        
        // 5. Find Harmonics (H2, H3)
        // Search neighborhood of integer multiples
        val h2Freq = fundamentalHz * 2
        val h3Freq = fundamentalHz * 3
        
        val h2Amp = findPeakNear(magnitudes, h2Freq, binWidth, numBins)
        val h3Amp = findPeakNear(magnitudes, h3Freq, binWidth, numBins)
        
        // 6. Ratios
        val h2ToH1 = if (fundamentalAmp > 0) h2Amp / fundamentalAmp else 0.0
        val h3ToH1 = if (fundamentalAmp > 0) h3Amp / fundamentalAmp else 0.0
        
        // 7. Spectral Entropy
        // Calculate over cardiac band (0.5 - 5.0 Hz)
        val startEnt = (0.5 / binWidth).toInt().coerceAtLeast(0)
        val endEnt = (5.0 / binWidth).toInt().coerceAtMost(numBins - 1)
        val subSpectrum = magnitudes.sliceArray(startEnt..endEnt)
        val spectralEntropy = computeSpectralEntropy(subSpectrum)
        
        // 8. SNR
        // Signal power = sum of squares near H1, H2, H3
        // Noise power = total power - signal power
        // This is a simplified SNR.
        
        val totalPower = magnitudes.map { it * it }.sum()
        val signalBins = mutableSetOf<Int>()
        addBinsAround(signalBins, maxIdx, 2) // +/- 2 bins around H1
        addBinsAround(signalBins, (h2Freq / binWidth).toInt(), 2)
        addBinsAround(signalBins, (h3Freq / binWidth).toInt(), 2)
        
        var signalPower = 0.0
        for (idx in signalBins) {
            if (idx in magnitudes.indices) {
                signalPower += magnitudes[idx] * magnitudes[idx]
            }
        }
        
        val noisePower = totalPower - signalPower
        val snrDb = if (noisePower > 1e-9) 10 * log10(signalPower / noisePower) else 100.0
        
        return HarmonicFeatures(
            fundamentalHz = fundamentalHz,
            fundamentalAmp = fundamentalAmp,
            h2Amp = h2Amp,
            h3Amp = h3Amp,
            h2ToH1Ratio = h2ToH1,
            h3ToH1Ratio = h3ToH1,
            spectralEntropy = spectralEntropy,
            snrDb = snrDb
        )
    }
    
    private fun findPeakNear(magnitudes: DoubleArray, freq: Double, binWidth: Double, maxBins: Int): Double {
        val centerBin = (freq / binWidth).toInt()
        if (centerBin >= maxBins) return 0.0
        
        // Search +/- 2 bins
        var maxVal = 0.0
        for (i in max(0, centerBin - 2)..min(maxBins - 1, centerBin + 2)) {
            if (magnitudes[i] > maxVal) {
                maxVal = magnitudes[i]
            }
        }
        return maxVal
    }
    
    private fun addBinsAround(set: MutableSet<Int>, center: Int, radius: Int) {
        for (i in center - radius..center + radius) {
            if (i >= 0) set.add(i)
        }
    }
    
    private fun computeSpectralEntropy(spectrum: DoubleArray): Double {
        val sum = spectrum.sum()
        if (sum <= 1e-9) return 0.0
        
        // Normalize to PMF
        val pmf = spectrum.map { it / sum }
        
        // Entropy = -sum(p * log2(p))
        var entropy = 0.0
        for (p in pmf) {
            if (p > 0) {
                entropy -= p * (ln(p) / ln(2.0))
            }
        }
        
        // Normalize by log2(N)
        return if (pmf.isNotEmpty()) entropy / (ln(pmf.size.toDouble()) / ln(2.0)) else 0.0
    }
}

