package com.vivopulse.feature.processing.sync

import kotlin.math.min

class GoodSyncDetectorImpl : GoodSyncDetector() {

    /**
     * Detects good windows over a longer session by analyzing sub-windows.
     * 
     * Implements morphological closing to bridge gaps <= 1s.
     * Requires segments >= 5s.
     */
    fun detectSessionSegmentsActual(
        fullFace: DoubleArray,
        fullFinger: DoubleArray,
        fsHz: Double
    ): List<GoodSyncSegment> {
        val winSamples = (8.0 * fsHz).toInt()
        val stepSamples = (1.0 * fsHz).toInt()
        val minSegmentSamples = (5.0 * fsHz).toInt()
        val maxGapSamples = (1.0 * fsHz).toInt()
        
        if (fullFace.size < winSamples || fullFinger.size < winSamples) {
            return emptyList()
        }
        
        val windows = mutableListOf<GoodSyncSegment>()
        
        // Sliding window analysis
        for (i in 0 until fullFace.size - winSamples step stepSamples) {
            val end = i + winSamples
            val faceWin = fullFace.sliceArray(i until end)
            val fingerWin = fullFinger.sliceArray(i until end)
            
            // Placeholder ROI stats - in real implementation, these would be passed in
            // or computed per-window if raw metadata available
            val roiStats = RoiStats(
                faceMotionRmsPx = 0.1,
                fingerSaturationPct = 0.01,
                snrDbFace = 10.0,
                snrDbFinger = 15.0,
                imuRmsG = 0.01
            )
            
            val detected = detectGoodSyncWindows(
                faceWin, fingerWin, fsHz, null, roiStats
            )
            
            if (detected.isNotEmpty()) {
                // Map window-relative time to session time
                val sessionWin = Window(
                    (i / fsHz * 1000).toLong(),
                    (end / fsHz * 1000).toLong()
                )
                
                windows.add(detected[0].copy(window = sessionWin))
            }
        }
        
        if (windows.isEmpty()) return emptyList()
        
        // Merge adjacent/overlapping windows
        val merged = mutableListOf<GoodSyncSegment>()
        var currentStart = windows[0].window.tStartMs
        var currentEnd = windows[0].window.tEndMs
        var bestSegment = windows[0] // Track best segment in current run for metadata
        
        for (i in 1 until windows.size) {
            val next = windows[i]
            val gap = next.window.tStartMs - currentEnd
            
            if (gap <= 1000) { // Gap <= 1s (morphological closing)
                currentEnd = kotlin.math.max(currentEnd, next.window.tEndMs)
                // Keep best correlation
                if (next.corr > bestSegment.corr) {
                    bestSegment = next
                }
            } else {
                // Segment finished
                if (currentEnd - currentStart >= 5000) { // Min duration 5s
                    merged.add(bestSegment.copy(
                        window = Window(currentStart, currentEnd)
                    ))
                }
                currentStart = next.window.tStartMs
                currentEnd = next.window.tEndMs
                bestSegment = next
            }
        }
        
        // Add last segment
        if (currentEnd - currentStart >= 5000) {
            merged.add(bestSegment.copy(
                window = Window(currentStart, currentEnd)
            ))
        }
        
        return merged
    }
}

