package com.vivopulse.feature.processing.reactivity

import com.vivopulse.feature.processing.SessionSummary
import com.vivopulse.feature.processing.wave.WaveFeatures

data class PhaseResult(
    val name: String,
    val summary: SessionSummary?,
    val pttConfidencePercent: Double?,   // from QualityReport (UI will pass)
    val combinedSqi: Double?             // from QualityReport
)

data class ReactivityProtocolSummary(
    val deltaPttMs: Double?,
    val deltaHrBpm: Double?,
    val deltaRiseTimeMs: Double?,
    val deltaReflectionRatio: Double?,
    val recoveryScore: Int?,             // 0..100, null if no recovery phase
    val reliability: Reliability
)

enum class Reliability {
    COMPLETE, LOW, INCOMPLETE
}

/**
 * Mini provocation protocol analyzer.
 *
 * Phases:
 * - Rest
 * - Post-Load
 * - Recovery (optional)
 */
object ReactivityProtocolAnalyzer {
    fun analyze(
        rest: PhaseResult?,
        post: PhaseResult?,
        recovery: PhaseResult?
    ): ReactivityProtocolSummary {
        val reliability = assessReliability(rest, post, recovery)

        val restPtt = rest?.summary?.pttResult?.pttMs
        val postPtt = post?.summary?.pttResult?.pttMs
        val restHr = rest?.summary?.heartRate?.hrBpm
        val postHr = post?.summary?.heartRate?.hrBpm
        val restWave = rest?.summary?.waveProfile
        val postWave = post?.summary?.waveProfile

        val deltaPtt = if (restPtt != null && postPtt != null) postPtt - restPtt else null
        val deltaHr = if (restHr != null && postHr != null) postHr - restHr else null
        val deltaRise = deltaFeature(restWave, postWave) { it.meanRiseTimeMs }
        val deltaRefl = deltaFeature(restWave, postWave) { it.meanReflectionRatio }

        val recScore = computeRecoveryScore(rest, post, recovery)

        return ReactivityProtocolSummary(
            deltaPttMs = deltaPtt,
            deltaHrBpm = deltaHr,
            deltaRiseTimeMs = deltaRise,
            deltaReflectionRatio = deltaRefl,
            recoveryScore = recScore,
            reliability = reliability
        )
    }

    private fun deltaFeature(
        rest: WaveFeatures.VascularWaveProfile?,
        post: WaveFeatures.VascularWaveProfile?,
        selector: (WaveFeatures.VascularWaveProfile) -> Double?
    ): Double? {
        val r = rest?.let(selector)
        val p = post?.let(selector)
        return if (r != null && p != null) p - r else null
    }

    private fun computeRecoveryScore(
        rest: PhaseResult?,
        post: PhaseResult?,
        recovery: PhaseResult?
    ): Int? {
        if (recovery?.summary == null || rest?.summary == null || post?.summary == null) return null

        val rec = recovery.summary
        val rst = rest.summary
        val pst = post.summary

        // For each metric, compute closeness to rest relative to post deviation
        val components = mutableListOf<Double>()

        fun closeness(current: Double?, baseline: Double?, postDelta: Double?): Double? {
            if (current == null || baseline == null || postDelta == null) return null
            val denom = kotlin.math.abs(postDelta).coerceAtLeast(1e-6)
            val dist = kotlin.math.abs(current - baseline)
            return (1.0 - dist / denom).coerceIn(0.0, 1.0)
        }

        val restPtt = rst.pttResult?.pttMs
        val postPtt = pst.pttResult?.pttMs
        val recPtt = rec.pttResult?.pttMs
        val postDeltaPtt = if (restPtt != null && postPtt != null) postPtt - restPtt else null
        closeness(recPtt, restPtt, postDeltaPtt)?.let { components.add(it) }

        val restHr = rst.heartRate?.hrBpm
        val postHr = pst.heartRate?.hrBpm
        val recHr = rec.heartRate?.hrBpm
        val postDeltaHr = if (restHr != null && postHr != null) postHr - restHr else null
        closeness(recHr, restHr, postDeltaHr)?.let { components.add(it) }

        val restRise = rst.waveProfile?.meanRiseTimeMs
        val postRise = pst.waveProfile?.meanRiseTimeMs
        val recRise = rec.waveProfile?.meanRiseTimeMs
        val postDeltaRise = if (restRise != null && postRise != null) postRise - restRise else null
        closeness(recRise, restRise, postDeltaRise)?.let { components.add(it) }

        val restRefl = rst.waveProfile?.meanReflectionRatio
        val postRefl = pst.waveProfile?.meanReflectionRatio
        val recRefl = rec.waveProfile?.meanReflectionRatio
        val postDeltaRefl = if (restRefl != null && postRefl != null) postRefl - restRefl else null
        closeness(recRefl, restRefl, postDeltaRefl)?.let { components.add(it) }

        if (components.isEmpty()) return null
        val avg = components.average()
        return (avg * 100.0).toInt().coerceIn(0, 100)
    }

    private fun assessReliability(
        rest: PhaseResult?,
        post: PhaseResult?,
        recovery: PhaseResult?
    ): Reliability {
        val phases = listOfNotNull(rest, post, recovery)
        if (phases.size < 2) return Reliability.INCOMPLETE

        var anyLow = false
        for (p in phases) {
            val sqi = p.combinedSqi ?: 0.0
            val pttConf = p.pttConfidencePercent ?: 0.0
            val pttOk = p.summary?.pttResult?.isPlausible == true && p.summary.pttResult.isStable
            if (sqi < 70.0 || pttConf < 60.0 || !pttOk) {
                anyLow = true
            }
        }
        return when {
            anyLow -> Reliability.LOW
            else -> Reliability.COMPLETE
        }
    }
}


