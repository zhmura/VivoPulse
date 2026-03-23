package com.vivopulse.feature.processing.ptt

import com.vivopulse.feature.processing.sync.SyncMetrics
import com.vivopulse.feature.processing.sync.Window
import kotlin.math.abs

/**
 * P1.6: Multi-method PTT consensus with Kalman fusion.
 *
 * Fuses up to 4 estimation methods:
 *   A) NCC cross-correlation (existing)
 *   B) ITM foot-to-foot (P1.5 upgrade)
 *   C) GCC-PHAT / multi-window (existing)
 *   D) Cross-spectral phase-slope (P1.4 upgrade, new)
 *
 * Instead of threshold-based switching ("if disagree > 50ms → use xcorr"),
 * each method provides (value, variance) and a scalar Kalman filter fuses
 * them with outlier gating.
 */
data class ConsensusPtt(
    val pttMsMedian: Double,
    val pttMsIqr: Double,
    val methodAgreeMs: Double,
    val nBeats: Int,
    val delayStabilityScore: Double = 1.0
)

class PTTConsensus {

    private val kalman = PttKalmanFuser()

    fun estimateConsensusPtt(
        face: DoubleArray,
        finger: DoubleArray,
        fsHz: Double,
        hrFaceBpm: Double,
        hrFingerBpm: Double,
        @Suppress("UNUSED_PARAMETER") segment: Window,
        footDetectionEnabled: Boolean = true
    ): ConsensusPtt {

        val tag = "PTTConsensus"
        val measurements = mutableListOf<PttKalmanFuser.Measurement>()
        val hrAvgBpm = if (hrFaceBpm > 0 && hrFingerBpm > 0)
            (hrFaceBpm + hrFingerBpm) / 2.0 else maxOf(hrFaceBpm, hrFingerBpm)

        // ──────────── Method A: NCC cross-correlation ────────────
        val syncResult = SyncMetrics.computeMetrics(face, finger, hrFaceBpm, hrFingerBpm, fsHz)
        val pttXCorr = syncResult.lagMs
        val xcorrVar = PttKalmanFuser.corrToVariance(syncResult.correlation, pttXCorr)
        measurements.add(PttKalmanFuser.Measurement("XCorr", pttXCorr, xcorrVar))
        android.util.Log.d(tag, "XCorr: lag=${"%.1f".format(pttXCorr)}ms corr=${"%.2f".format(syncResult.correlation)} var=${"%.0f".format(xcorrVar)}")

        // ──────────── Method B: ITM Foot-to-Foot (P1.5) ────────────
        var nBeats = 0
        var footIqr = 0.0
        if (footDetectionEnabled) {
            val facePeaks = PeakDetect.detectPeaks(face, fsHz)
            val fingerPeaks = PeakDetect.detectPeaks(finger, fsHz)

            if (facePeaks.isValid && fingerPeaks.isValid) {
                val faceFeet = IntersectingTangentFoot.detectFeet(face, fsHz, facePeaks.indices)
                val fingerFeet = IntersectingTangentFoot.detectFeet(finger, fsHz, fingerPeaks.indices)
                val f2fPtts = IntersectingTangentFoot.computeFootToFootPtt(faceFeet, fingerFeet)

                if (f2fPtts.size >= 2) {
                    val sorted = f2fPtts.sorted()
                    val pttFoot = median(sorted)
                    footIqr = iqr(sorted)
                    val footVar = PttKalmanFuser.iqrToVariance(footIqr.coerceAtLeast(5.0))
                    nBeats = f2fPtts.size

                    measurements.add(PttKalmanFuser.Measurement("F2F", pttFoot, footVar))
                    android.util.Log.d(tag, "ITM F2F: median=${"%.1f".format(pttFoot)}ms iqr=${"%.1f".format(footIqr)}ms nBeats=$nBeats var=${"%.0f".format(footVar)}")
                } else {
                    android.util.Log.d(tag, "ITM F2F: insufficient beats (${f2fPtts.size})")
                }
            }
        }

        // ──────────── Method C: GCC-PHAT / Multi-window ────────────
        val gccResult = CrossCorr.gccPhatLag(face, finger, fsHz)
        val multiResult = CrossCorr.multiWindowLag(face, finger, fsHz)

        if (multiResult.isValid) {
            val madVar = PttKalmanFuser.madToVariance(multiResult.madMs.coerceAtLeast(3.0))
            measurements.add(PttKalmanFuser.Measurement("GCC-MW", multiResult.medianLagMs, madVar))
            android.util.Log.d(tag, "GCC-MW: lag=${"%.1f".format(multiResult.medianLagMs)}ms mad=${"%.1f".format(multiResult.madMs)}ms stability=${"%.2f".format(multiResult.stabilityScore)} var=${"%.0f".format(madVar)}")
        } else {
            val gccVar = PttKalmanFuser.corrToVariance(gccResult.peakSharpness, gccResult.lagMs)
            measurements.add(PttKalmanFuser.Measurement("GCC", gccResult.lagMs, gccVar))
            android.util.Log.d(tag, "GCC: lag=${"%.1f".format(gccResult.lagMs)}ms var=${"%.0f".format(gccVar)}")
        }

        // ──────────── Method D: Cross-spectral phase delay (P1.4) ────────────
        if (hrAvgBpm > 30) {
            val cspResult = CrossSpectralPhaseDelay.estimateDelay(
                face, finger, fsHz, hrAvgBpm,
                maxHarmonics = 4, gammaMin = 0.10
            )
            if (cspResult.standardErrorMs < 200) {
                val cspVar = PttKalmanFuser.cspToVariance(cspResult.standardErrorMs)
                measurements.add(PttKalmanFuser.Measurement("CSP", cspResult.delayMs, cspVar))
                android.util.Log.d(tag, "CSP: delay=${"%.1f".format(cspResult.delayMs)}ms se=${"%.1f".format(cspResult.standardErrorMs)}ms coh=${"%.2f".format(cspResult.meanCoherence)} bins=${cspResult.nBins} var=${"%.0f".format(cspVar)}")
            } else {
                android.util.Log.d(tag, "CSP: rejected (SE=${"%.0f".format(cspResult.standardErrorMs)}ms)")
            }
        }

        // ──────────── Kalman Fusion (P1.6) ────────────
        kalman.reset(pttXCorr, 2500.0) // Reset per session with XCorr as prior
        val fusion = kalman.fuse(measurements)

        val stability = multiResult.stabilityScore.coerceIn(0.0, 1.0)

        // Method agreement: max pairwise difference among valid measurements
        val validVals = measurements.filter {
            it.valueMsOrNull != null && it.varianceMs2.isFinite()
        }.mapNotNull { it.valueMsOrNull }
        val methodAgree = if (validVals.size >= 2) {
            validVals.maxOrNull()!! - validVals.minOrNull()!!
        } else {
            Double.MAX_VALUE
        }

        android.util.Log.i(tag, "FUSION | ptt=${"%.1f".format(fusion.pttMs)}ms ±${"%.1f".format(fusion.confidenceInterval)}ms | " +
              "methods=${fusion.methodsUsed}/${measurements.size} rejected=${fusion.methodsRejected} | " +
              "agree=${"%.1f".format(methodAgree)}ms stable=${fusion.isStable}")

        return ConsensusPtt(
            pttMsMedian = fusion.pttMs,
            pttMsIqr = footIqr,
            methodAgreeMs = methodAgree,
            nBeats = nBeats,
            delayStabilityScore = stability
        )
    }

    private fun median(list: List<Double>): Double {
        if (list.isEmpty()) return 0.0
        val sorted = list.sorted()
        val n = sorted.size
        return if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0 else sorted[n / 2]
    }

    private fun iqr(list: List<Double>): Double {
        if (list.size < 4) return 0.0
        val sorted = list.sorted()
        return sorted[sorted.size * 3 / 4] - sorted[sorted.size / 4]
    }
}
