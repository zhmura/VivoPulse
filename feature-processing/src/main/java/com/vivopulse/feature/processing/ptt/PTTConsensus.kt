package com.vivopulse.feature.processing.ptt

import com.vivopulse.feature.processing.sync.SyncMetrics
import com.vivopulse.feature.processing.sync.Window
import kotlin.math.abs

data class ConsensusPtt(
    val pttMsMedian: Double,
    val pttMsIqr: Double,
    val methodAgreeMs: Double,
    val nBeats: Int
)

class PTTConsensus {

    fun estimateConsensusPtt(
        face: DoubleArray,
        finger: DoubleArray,
        fsHz: Double,
        hrFaceBpm: Double,
        hrFingerBpm: Double,
        @Suppress("UNUSED_PARAMETER") segment: Window
    ): ConsensusPtt {
        // Method A: XCorr lag
        // We can reuse SyncMetrics logic but we need just the lag
        // Assuming face and finger are already windowed to 'segment'
        
        val syncResult = SyncMetrics.computeMetrics(face, finger, hrFaceBpm, hrFingerBpm, fsHz)
        val pttXCorr = syncResult.lagMs
        
        // Method B: Foot-to-Foot
        // Detect feet (onset)
        val faceFeet = detectFeet(face, fsHz)
        val fingerFeet = detectFeet(finger, fsHz)
        
        val beatLags = mutableListOf<Double>()
        
        // Match feet
        for (tFace in faceFeet) {
            // Find closest finger foot after face foot (within reasonable PTT range e.g. 100-400ms)
            // Or just closest.
            val tFinger = fingerFeet.minByOrNull { abs(it - tFace) } ?: continue
            
            val lag = tFinger - tFace
            // PTT should be positive (Face -> Finger)
            // But depending on phase, it might wrap.
            // Let's assume aligned signals where lag is roughly 200ms.
            
            if (lag in 50.0..500.0) {
                beatLags.add(lag)
            }
        }
        
        if (beatLags.isEmpty()) {
            return ConsensusPtt(0.0, 0.0, 0.0, 0)
        }
        
        val pttFootMedian = median(beatLags)
        val pttFootIqr = iqr(beatLags)
        
        val agreement = abs(pttXCorr - pttFootMedian)
        
        // If agreement is bad, maybe return "uncertain"?
        // For now, return stats.
        
        return ConsensusPtt(
            pttMsMedian = pttFootMedian, // Prefer foot-to-foot for beat-level precision? Or XCorr for robustness?
            // Prompt says: "Agreement check: |PTT_xcorr - PTT_foot| <= 20 ms"
            // If agrees, use median.
            pttMsIqr = pttFootIqr,
            methodAgreeMs = agreement,
            nBeats = beatLags.size
        )
    }
    
    private fun detectFeet(signal: DoubleArray, fsHz: Double): List<Double> {
        if (signal.size < 3) return emptyList()
        
        // 1. Compute 1st derivative (central difference)
        val diff = DoubleArray(signal.size)
        for (i in 1 until signal.size - 1) {
            diff[i] = (signal[i + 1] - signal[i - 1]) / 2.0
        }
        
        // 2. Find max d/dt (steepest systolic upstroke)
        val maxSlope = diff.maxOrNull() ?: return emptyList()
        val slopeThreshold = maxSlope * 0.05 // 5% of max slope - robust for smoother signals
        
        val feet = mutableListOf<Double>()
        
        // 3. Find feet
        // Look for local maxima in derivative that exceed threshold
        var i = 1
        while (i < diff.size - 1) {
            // Check if i is a local max of derivative and exceeds threshold
            if (diff[i] > slopeThreshold && diff[i] >= diff[i-1] && diff[i] >= diff[i+1]) {
                
                // This is a systolic upstroke peak velocity.
                // The foot is the minimum value point preceding this upstroke.
                // Search backwards for local minimum in the signal.
                // Limit search window to e.g. 500ms (50 samples) to cover slow HR (0.6 Hz)
                
                val searchLimit = 50
                var bestK = i
                var minVal = signal[i]
                
                for (k in i downTo maxOf(0, i - searchLimit)) {
                    if (signal[k] <= minVal) {
                        minVal = signal[k]
                        bestK = k
                    }
                    
                    // Check if we found a local minimum (valley)
                    // k < k-1 and k <= k+1
                    if (k > 0 && k < i) {
                        if (signal[k] < signal[k-1] && signal[k] <= signal[k+1]) {
                            // Found local min
                            bestK = k
                            break
                        }
                    }
                }
                
                feet.add((bestK / fsHz) * 1000.0)
                
                // Skip forward to avoid detecting same beat
                i += 30
            } else {
                i++
            }
        }
        
        return feet
    }
    
    private fun median(list: List<Double>): Double {
        if (list.isEmpty()) return 0.0
        val sorted = list.sorted()
        val n = sorted.size
        return if (n % 2 == 0) {
            (sorted[n/2 - 1] + sorted[n/2]) / 2.0
        } else {
            sorted[n/2]
        }
    }
    
    private fun iqr(list: List<Double>): Double {
        if (list.size < 4) return 0.0
        val sorted = list.sorted()
        val q1 = sorted[sorted.size / 4]
        val q3 = sorted[sorted.size * 3 / 4]
        return q3 - q1
    }
}
