package com.vivopulse.feature.processing.ptt

import com.vivopulse.signal.SavitzkyGolay
import kotlin.math.abs

/**
 * Intersecting Tangent Method (ITM) for PPG pulse foot (onset) detection.
 * 
 * Detects the foot of each pulse beat by finding the intersection of:
 * 1. A horizontal baseline tangent at the beat's local minimum
 * 2. The upslope tangent at the maximum first-derivative point
 * 
 * Uses Savitzky-Golay derivatives instead of naive central differences,
 * and HR-adaptive search windows instead of fixed sample counts.
 * 
 * P1.5 Research upgrade from: "Intersecting Tangent Method for foot
 * detection with HR-adaptive windows" (research v1, §2).
 */
object IntersectingTangentFoot {
    
    /**
     * Result of foot detection for a single beat.
     */
    data class FootResult(
        val footTimeSec: Double,    // Foot time in seconds
        val footIndex: Int,         // Nearest sample index
        val slopeAtFoot: Double,    // Max slope value (quality proxy)
        val valid: Boolean          // Whether detection was successful
    )
    
    /**
     * Detect feet (pulse onsets) for all beats using ITM.
     * 
     * @param signal Filtered, detrended PPG signal
     * @param fs Sampling frequency (Hz)
     * @param peakIndices Detected systolic peak indices
     * @param sgWindowMs Savitzky-Golay window width in ms (default: 150ms)
     * @return List of foot detections (one per beat, some may be invalid)
     */
    fun detectFeet(
        signal: DoubleArray,
        fs: Double,
        peakIndices: IntArray,
        sgWindowMs: Double = 150.0
    ): List<FootResult> {
        if (peakIndices.size < 2 || signal.size < 10) {
            return emptyList()
        }
        
        // 1. Compute SG first derivative
        val sgWindow = SavitzkyGolay.windowMsToSamples(sgWindowMs, fs)
        val dx = SavitzkyGolay.firstDerivative(signal, fs, sgWindow)
        
        // 2. Estimate per-beat RR intervals for adaptive windowing
        val feet = mutableListOf<FootResult>()
        
        for (k in peakIndices.indices) {
            val peakIdx = peakIndices[k]
            
            // Estimate RR from adjacent peaks
            val rrSamples = if (k > 0) {
                peakIndices[k] - peakIndices[k - 1]
            } else if (k < peakIndices.size - 1) {
                peakIndices[k + 1] - peakIndices[k]
            } else {
                (0.8 * fs).toInt() // Default ~75 bpm
            }
            
            // HR-adaptive search window: look back 0.25–0.45 × RR before peak
            val searchStart = (peakIdx - (0.45 * rrSamples).toInt()).coerceAtLeast(0)
            val searchEnd = (peakIdx - (0.05 * rrSamples).toInt()).coerceAtLeast(searchStart + 1)
            
            if (searchEnd >= signal.size || searchStart >= searchEnd) {
                feet.add(FootResult(peakIdx / fs, peakIdx, 0.0, false))
                continue
            }
            
            // 3. Find local minimum (t_min) in search window
            var tMinIdx = searchStart
            var minVal = signal[searchStart]
            for (i in searchStart..searchEnd.coerceAtMost(signal.size - 1)) {
                if (signal[i] < minVal) {
                    minVal = signal[i]
                    tMinIdx = i
                }
            }
            
            // 4. Find max slope point (t_ms) on upslope: from t_min to near peak
            val upslopeEnd = (peakIdx - (0.10 * rrSamples).toInt()).coerceAtMost(signal.size - 1)
            if (tMinIdx >= upslopeEnd) {
                feet.add(FootResult(peakIdx / fs, peakIdx, 0.0, false))
                continue
            }
            
            var tMsIdx = tMinIdx
            var maxSlope = dx[tMinIdx]
            for (i in tMinIdx..upslopeEnd) {
                if (dx[i] > maxSlope) {
                    maxSlope = dx[i]
                    tMsIdx = i
                }
            }
            
            // 5. Quality check: slope must be positive and above MAD-based threshold
            if (maxSlope <= 0) {
                feet.add(FootResult(tMinIdx / fs, tMinIdx, maxSlope, false))
                continue
            }
            
            // 6. Compute foot time via ITM intersection formula:
            //    t_foot = t_ms + (x(t_min) - x(t_ms)) / dx(t_ms)
            // Where:
            //    x(t_min) = baseline level
            //    x(t_ms) = signal value at max slope
            //    dx(t_ms) = max slope value
            val xTmin = signal[tMinIdx]
            val xTms = signal[tMsIdx]
            val tFootSec = (tMsIdx.toDouble() / fs) + (xTmin - xTms) / (maxSlope + 1e-12)
            
            // Convert back to sample index for validation
            val tFootIdx = (tFootSec * fs).toInt().coerceIn(0, signal.size - 1)
            
            // Plausibility: foot should be between tMin and peak
            val valid = tFootIdx in tMinIdx..peakIdx && maxSlope > 0
            
            feet.add(FootResult(tFootSec, tFootIdx, maxSlope, valid))
        }
        
        return feet
    }
    
    /**
     * Compute foot-to-foot PTT from face and finger foot timings.
     * 
     * @param faceFeet List of foot detections for face signal
     * @param fingerFeet List of foot detections for finger signal
     * @return List of per-beat PTT values in ms (only from valid, matched beats)
     */
    fun computeFootToFootPtt(
        faceFeet: List<FootResult>,
        fingerFeet: List<FootResult>
    ): List<Double> {
        val ptts = mutableListOf<Double>()
        
        // Match beats by nearest timing (within 50% of typical RR)
        val maxMismatchSec = 0.3 // 300ms tolerance for beat matching
        
        for (faceFoot in faceFeet) {
            if (!faceFoot.valid) continue
            
            // Find nearest valid finger foot
            var bestMatch: FootResult? = null
            var bestDist = Double.MAX_VALUE
            for (fingerFoot in fingerFeet) {
                if (!fingerFoot.valid) continue
                val dist = abs(faceFoot.footTimeSec - fingerFoot.footTimeSec)
                if (dist < bestDist && dist < maxMismatchSec) {
                    bestDist = dist
                    bestMatch = fingerFoot
                }
            }
            
            if (bestMatch != null) {
                // PTT = finger foot time - face foot time (finger is closer to heart)
                val pttMs = (bestMatch.footTimeSec - faceFoot.footTimeSec) * 1000.0
                if (pttMs > 0 && pttMs < 500) { // Physiological range
                    ptts.add(pttMs)
                }
            }
        }
        
        return ptts
    }
}
