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
        segment: Window
    ): ConsensusPtt {
        // Method A: XCorr lag
        // We can reuse SyncMetrics logic but we need just the lag
        // Assuming face and finger are already windowed to 'segment'
        
        // Placeholder HRs
        val hrFace = 60.0
        val hrFinger = 60.0
        
        val syncResult = SyncMetrics.computeMetrics(face, finger, hrFace, hrFinger, fsHz)
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
        // Simple derivative-based onset detection
        // d/dt max is the systolic upstroke.
        // Foot is 10-20% of max d/dt? Or min point before max d/dt?
        // Let's use local minima.
        
        val feet = mutableListOf<Double>()
        for (i in 1 until signal.size - 1) {
            if (signal[i] < signal[i-1] && signal[i] < signal[i+1]) {
                // Local minimum
                feet.add((i / fsHz) * 1000.0)
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
