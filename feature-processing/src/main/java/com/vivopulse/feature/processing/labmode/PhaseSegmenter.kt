package com.vivopulse.feature.processing.labmode

import com.vivopulse.feature.processing.ProcessedSeries

/**
 * Phase segmenter for lab mode protocols.
 * 
 * Segments processed signals by protocol phase and computes per-phase metrics.
 */
class PhaseSegmenter {
    
    /**
     * Segment processed series by protocol phases.
     * 
     * @param series Processed signal series
     * @param phaseTransitions Phase transitions from protocol executor
     * @return Map of phase ID to phase data
     */
    fun segmentByPhase(
        series: ProcessedSeries,
        phaseTransitions: List<PhaseTransition>
    ): Map<String, PhaseData> {
        val phaseDataMap = mutableMapOf<String, PhaseData>()
        
        for (transition in phaseTransitions) {
            // Find samples in this phase
            val phaseStartMs = transition.startS * 1000.0
            val phaseEndMs = transition.endS * 1000.0
            
            val indices = series.timeMillis.indices.filter { i ->
                series.timeMillis[i] >= phaseStartMs && series.timeMillis[i] < phaseEndMs
            }
            
            if (indices.isEmpty()) continue
            
            // Extract phase signals
            val faceSegment = indices.map { series.faceSignal[it] }.toDoubleArray()
            val fingerSegment = indices.map { series.fingerSignal[it] }.toDoubleArray()
            val timeSegment = indices.map { series.timeMillis[it] }.toDoubleArray()
            
            phaseDataMap[transition.phaseId] = PhaseData(
                phaseId = transition.phaseId,
                phaseName = transition.phaseName,
                startS = transition.startS,
                endS = transition.endS,
                durationS = transition.durationS,
                faceSignal = faceSegment,
                fingerSignal = fingerSegment,
                timeMs = timeSegment,
                sampleCount = indices.size
            )
        }
        
        return phaseDataMap
    }
    
    /**
     * Tag samples with phase IDs.
     * 
     * @param timeMs Time array in milliseconds
     * @param phaseTransitions Phase transitions
     * @return Array of phase IDs (or null for samples outside phases)
     */
    fun tagSamples(
        timeMs: DoubleArray,
        phaseTransitions: List<PhaseTransition>
    ): Array<String?> {
        val tags = Array<String?>(timeMs.size) { null }
        
        for (i in timeMs.indices) {
            val timeS = timeMs[i] / 1000.0
            
            for (transition in phaseTransitions) {
                if (timeS >= transition.startS && timeS < transition.endS) {
                    tags[i] = transition.phaseId
                    break
                }
            }
        }
        
        return tags
    }
}

/**
 * Phase-specific signal data.
 */
data class PhaseData(
    val phaseId: String,
    val phaseName: String,
    val startS: Int,
    val endS: Int,
    val durationS: Int,
    val faceSignal: DoubleArray,
    val fingerSignal: DoubleArray,
    val timeMs: DoubleArray,
    val sampleCount: Int
)

