package com.vivopulse.feature.processing.labmode

import com.vivopulse.feature.processing.PttCalculator
import com.vivopulse.signal.SignalQuality
import com.vivopulse.signal.HarmonicFeatureExtractor
import kotlin.math.abs

/**
 * Vascular reactivity analyzer for lab mode.
 * 
 * Computes ΔPTT, ΔHR, Δcorrelation between protocol phases and assesses
 * reactivity levels (low/normal/high).
 */
class ReactivityAnalyzer {
// ...
    private fun computePhaseMetrics(
        phaseData: PhaseData,
        sampleRateHz: Double
    ): PhaseMetrics {
        // Compute PTT for this phase
        val faceProcessed = com.vivopulse.feature.processing.ProcessedSeries(
            timeMillis = phaseData.timeMs.toList(),
            faceSignal = phaseData.faceSignal,
            fingerSignal = phaseData.fingerSignal,
            sampleRateHz = sampleRateHz,
            isValid = true,
            mainHarmonicsFace = HarmonicFeatureExtractor.HarmonicFeatures.empty(),
            mainHarmonicsFinger = HarmonicFeatureExtractor.HarmonicFeatures.empty()
        )
        val pttResult = PttCalculator.computePtt(faceProcessed)
        
        // Estimate HR from peaks
        val peaks = SignalQuality.findPeaks(
            phaseData.fingerSignal,
            minDistance = (sampleRateHz * 0.4).toInt()
        )
        val hr = if (peaks.size >= 2) {
            val avgIntervalS = (phaseData.durationS.toDouble() / (peaks.size - 1))
            60.0 / avgIntervalS
        } else {
            0.0
        }
        
        return PhaseMetrics(
            phaseId = phaseData.phaseId,
            phaseName = phaseData.phaseName,
            pttMs = pttResult.pttMs,
            pttSdMs = pttResult.stabilityMs,
            correlation = pttResult.correlationScore,
            hrBpm = hr,
            sampleCount = phaseData.sampleCount
        )
    }
    
    /**
     * Compute delta between two phases.
     */
    private fun computePhaseDelta(
        phase1Id: String,
        phase2Id: String,
        metrics1: PhaseMetrics,
        metrics2: PhaseMetrics,
        expectedPttDirection: Direction?,
        expectedHrDirection: Direction?
    ): PhaseDelta {
        val deltaPtt = metrics2.pttMs - metrics1.pttMs
        val deltaCorr = metrics2.correlation - metrics1.correlation
        val deltaHr = metrics2.hrBpm - metrics1.hrBpm
        
        // Check if changes match expectations
        val pttMatchesExpected = if (expectedPttDirection != null) {
            when (expectedPttDirection) {
                Direction.INCREASE -> deltaPtt > 2.0 // Threshold: >2ms increase
                Direction.DECREASE -> deltaPtt < -2.0 // Threshold: <-2ms decrease
                Direction.STABLE -> abs(deltaPtt) < 5.0 // Threshold: ±5ms stable
            }
        } else {
            null
        }
        
        val hrMatchesExpected = if (expectedHrDirection != null) {
            when (expectedHrDirection) {
                Direction.INCREASE -> deltaHr > 2.0 // Threshold: >2 bpm increase
                Direction.DECREASE -> deltaHr < -2.0 // Threshold: <-2 bpm decrease
                Direction.STABLE -> abs(deltaHr) < 3.0 // Threshold: ±3 bpm stable
            }
        } else {
            null
        }
        
        return PhaseDelta(
            phase1Id = phase1Id,
            phase2Id = phase2Id,
            phase1Name = metrics1.phaseName,
            phase2Name = metrics2.phaseName,
            deltaPttMs = deltaPtt,
            deltaCorrScore = deltaCorr,
            deltaHrBpm = deltaHr,
            expectedPttDirection = expectedPttDirection,
            expectedHrDirection = expectedHrDirection,
            pttMatchesExpected = pttMatchesExpected,
            hrMatchesExpected = hrMatchesExpected
        )
    }
    
    /**
     * Assess overall reactivity level.
     */
    private fun assessReactivityLevel(deltas: List<PhaseDelta>): ReactivityLevel {
        val matchedPtt = deltas.count { it.pttMatchesExpected == true }
        val matchedHr = deltas.count { it.hrMatchesExpected == true }
        val totalChecks = deltas.size * 2 // PTT + HR per delta
        
        if (totalChecks == 0) return ReactivityLevel.UNKNOWN
        
        val matchRate = (matchedPtt + matchedHr).toDouble() / totalChecks
        
        return when {
            matchRate >= 0.80 -> ReactivityLevel.NORMAL  // ≥80% match = normal
            matchRate >= 0.50 -> ReactivityLevel.LOW     // 50-80% match = low
            else -> ReactivityLevel.BLUNTED              // <50% match = blunted
        }
    }
}

/**
 * Reactivity analysis result.
 */
data class ReactivityAnalysis(
    val phaseMetrics: Map<String, PhaseMetrics>,
    val deltas: List<PhaseDelta>,
    val reactivityLevel: ReactivityLevel,
    val protocolId: String,
    val protocolName: String
)

/**
 * Per-phase metrics.
 */
data class PhaseMetrics(
    val phaseId: String,
    val phaseName: String,
    val pttMs: Double,
    val pttSdMs: Double,
    val correlation: Double,
    val hrBpm: Double,
    val sampleCount: Int
)

/**
 * Phase-to-phase delta.
 */
data class PhaseDelta(
    val phase1Id: String,
    val phase2Id: String,
    val phase1Name: String,
    val phase2Name: String,
    val deltaPttMs: Double,
    val deltaCorrScore: Double,
    val deltaHrBpm: Double,
    val expectedPttDirection: Direction?,
    val expectedHrDirection: Direction?,
    val pttMatchesExpected: Boolean?,
    val hrMatchesExpected: Boolean?
)

/**
 * Reactivity level enum.
 */
enum class ReactivityLevel {
    NORMAL,    // ≥80% expected changes observed
    LOW,       // 50-80% expected changes observed
    BLUNTED,   // <50% expected changes observed
    UNKNOWN    // Insufficient data
}

