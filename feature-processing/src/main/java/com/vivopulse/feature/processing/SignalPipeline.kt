package com.vivopulse.feature.processing

import com.vivopulse.feature.processing.ptt.PttEngine
import com.vivopulse.feature.processing.ptt.PttOutput
import com.vivopulse.feature.processing.timestamp.TimestampSync
import com.vivopulse.feature.processing.timestamp.TimestampedValue
import com.vivopulse.signal.DspFunctions
import android.util.Log
import com.vivopulse.signal.PulseLog
import com.vivopulse.signal.AppLogger
import com.vivopulse.feature.processing.wavelet.WaveletDenoiser
import com.vivopulse.feature.processing.motion.ImuMotionAnalyzer
import com.vivopulse.feature.processing.motion.StepNotchFilter
import com.vivopulse.signal.HarmonicFeatureExtractor
import com.vivopulse.signal.PosExtractor
import com.vivopulse.signal.ProcessedSignal
import com.vivopulse.feature.processing.signal.SnrEstimator

/**
 * Signal processing pipeline for dual camera PPG signals.
 * 
 * **MVP Requirements Validation:**
 * - **FR-S1 (Preprocessing):** Orchestrates resampling (100Hz), detrending, and bandpass filtering (0.7-4.0Hz).
 * - **FR-S4 (GoodSync):** Computes cross-correlation and quality gating to identify valid PTT windows.
 * - **FR-P1/P2 (PTT):** Executes PTT algorithms (XCorr + Foot-to-Foot) and computes consensus.
 * - **FR-E1 (Export):** Produces `ProcessedSeries` object structured for JSON/CSV export.
 * 
 * **Functional Goal:**
 * - Transform loose `RawSeriesBuffer` (random timestamps) into a scientifically valid, aligned `ProcessedSeries`.
 * - Act as the "Black Box" that takes raw frames and outputs clinical metrics.
 */
class SignalPipeline(
    private val targetSampleRateHz: Double = 100.0,
    private val lowCutoffHz: Double = 0.7,
    private val highCutoffHz: Double = 4.0,
    private val correlationWindowSec: Double = 20.0,
    private val walkingModeEnabled: Boolean = false,
    private val motionRejectionThresholdG: Double = 0.1
) {
    private val tag = PulseLog.PIPELINE
    private val imuAnalyzer = ImuMotionAnalyzer()
    private val snrEstimator = SnrEstimator()
    
    companion object {
        // P1: Spectral SNR thresholds (dB).
        // Ojas rule: Peak > 2× Noise ≈ 6dB power. Conservative thresholds:
        //   Face:   3 dB (weaker signal, ambient light)
        //   Finger: 6 dB (stronger signal, torch illumination)
        const val FACE_SNR_GATE_DB = 3.0
        const val FINGER_SNR_GATE_DB = 6.0
    }
    
    /**
     * Result of motion rejection with optional PTT gate.
     */
    data class MotionRejectionResult(
        val buffer: RawSeriesBuffer,
        val pttGate: String? = null  // null = OK, "ALL_MOTION" = all frames exceeded threshold
    )
    
    /**
     * Trim warm-up transient: discard the first [warmUpNs] nanoseconds of each stream.
     * Each stream is trimmed from its own start time independently (they may start at different times).
     * This prevents AE settling ramps and ISP stabilization artifacts from entering the pipeline.
     */
    private fun trimWarmUp(rawBuffer: RawSeriesBuffer, warmUpNs: Long): RawSeriesBuffer {
        if (warmUpNs <= 0) return rawBuffer

        // Safety: skip trim if recording is too short (< 5s) to avoid removing meaningful data
        val faceDurationNs = if (rawBuffer.faceData.size >= 2)
            rawBuffer.faceData.last().timestampNs - rawBuffer.faceData.first().timestampNs else 0L
        val fingerDurationNs = if (rawBuffer.fingerData.size >= 2)
            rawBuffer.fingerData.last().timestampNs - rawBuffer.fingerData.first().timestampNs else 0L
        val minDurationNs = minOf(faceDurationNs, fingerDurationNs)
        
        if (minDurationNs < 5_000_000_000L) { // < 5 seconds
            Log.d(tag, "WARMUP_TRIM | skipped (recording too short: ${"%.1f".format(minDurationNs / 1e9)}s)")
            return rawBuffer
        }

        fun <T> trimByTimestamp(data: List<T>?, extractTs: (T) -> Long, cutoffNs: Long): List<T>? {
            if (data.isNullOrEmpty()) return data
            return data.filter { extractTs(it) >= cutoffNs }
        }

        // Calculate cutoff timestamps per stream (independent start times)
        val faceCutoff = rawBuffer.faceData.firstOrNull()?.timestampNs?.plus(warmUpNs) ?: 0L
        val fingerCutoff = rawBuffer.fingerData.firstOrNull()?.timestampNs?.plus(warmUpNs) ?: 0L

        return RawSeriesBuffer(
            faceData = rawBuffer.faceData.filter { it.timestampNs >= faceCutoff },
            fingerData = rawBuffer.fingerData.filter { it.timestampNs >= fingerCutoff },
            faceRgb = trimByTimestamp(rawBuffer.faceRgb, { it.first }, faceCutoff),
            fingerRgb = trimByTimestamp(rawBuffer.fingerRgb, { it.first }, fingerCutoff),
            faceMotion = trimByTimestamp(rawBuffer.faceMotion, { it.timestampNs }, faceCutoff),
            fingerSaturation = trimByTimestamp(rawBuffer.fingerSaturation, { it.timestampNs }, fingerCutoff),
            imuRms = rawBuffer.imuRms, // IMU is global, keep all
            faceRoi = trimByTimestamp(rawBuffer.faceRoi, { it.first }, faceCutoff)
        )
    }

    /**
     * Filter out high-motion frames based on IMU RMS threshold.
     * 
     * P1-A: If ALL frames exceed threshold, do NOT keep them (old fail-safe was harmful).
     * Instead, return empty buffer with ALL_MOTION gate to prevent garbage processing.
     */
    private fun applyMotionRejection(rawBuffer: RawSeriesBuffer): MotionRejectionResult {
        val imuData = rawBuffer.imuRms ?: return MotionRejectionResult(rawBuffer)
        if (imuData.isEmpty()) return MotionRejectionResult(rawBuffer)
        
        // Compute windowed motion score for each IMU sample (±250ms window)
        val windowNs = 250_000_000L // ±250ms
        val imuTimestamps = imuData.map { it.timestampNs }
        val imuValues = imuData.map { it.value }
        
        // For each IMU sample, compute mean RMS in the surrounding window
        val windowedScores = DoubleArray(imuData.size)
        for (i in imuData.indices) {
            val center = imuTimestamps[i]
            var sum = 0.0
            var count = 0
            // Scan outward from center
            for (j in imuData.indices) {
                if (kotlin.math.abs(imuTimestamps[j] - center) <= windowNs) {
                    sum += imuValues[j]
                    count++
                }
            }
            windowedScores[i] = if (count > 0) sum / count else imuValues[i]
        }
        
        // Build set of timestamps where the ENTIRE window is calm
        val calmTimestamps = imuData.indices
            .filter { windowedScores[it] <= motionRejectionThresholdG }
            .map { imuTimestamps[it] }
            .toSet()
        
        if (calmTimestamps.isEmpty()) {
            // P1-A: ALL windows exceeded threshold — do NOT process garbage.
            val fingerDurationS = if (rawBuffer.fingerData.size >= 2) {
                (rawBuffer.fingerData.last().timestampNs - rawBuffer.fingerData.first().timestampNs) / 1e9
            } else 0.0
            
            Log.w(tag, "Motion rejection: ALL windows exceeded threshold ($motionRejectionThresholdG G). " +
                  "PTT unavailable. Finger duration: ${"%.1f".format(fingerDurationS)}s")
            
            return MotionRejectionResult(rawBuffer, pttGate = "ALL_MOTION")
        }
        
        val rejectedCount = imuData.size - calmTimestamps.size
        val rejectionRate = (rejectedCount * 100.0 / imuData.size)
        Log.d(tag, "Motion rejection (windowed ±250ms): $rejectedCount frames (${"%.1f".format(rejectionRate)}%) exceeded ${motionRejectionThresholdG}G threshold")
        
        // Keep frames whose nearest calm IMU sample is within one window span
        fun isInCalmWindow(ts: Long): Boolean = calmTimestamps.any { kotlin.math.abs(it - ts) <= windowNs }
        
        val filtered = rawBuffer.copy(
            faceData = rawBuffer.faceData.filter { isInCalmWindow(it.timestampNs) },
            fingerData = rawBuffer.fingerData.filter { isInCalmWindow(it.timestampNs) },
            faceMotion = rawBuffer.faceMotion?.filter { isInCalmWindow(it.timestampNs) },
            fingerSaturation = rawBuffer.fingerSaturation?.filter { isInCalmWindow(it.timestampNs) },
            imuRms = rawBuffer.imuRms.filter { isInCalmWindow(it.timestampNs) }
        )
        return MotionRejectionResult(filtered)
    }
    
    data class ChannelResult(
        val mainSignal: DoubleArray,
        val denoisedSignal: DoubleArray?,
        val mainHarmonics: HarmonicFeatureExtractor.HarmonicFeatures,
        val denoisedHarmonics: HarmonicFeatureExtractor.HarmonicFeatures?,
        val posSignal: DoubleArray? = null
    )

    fun process(
        rawBuffer: RawSeriesBuffer,
        preProcessedSignals: List<ProcessedSignal>? = null
    ): ProcessedSeries {
        Log.d(tag, "Starting pipeline process. Raw buffer size: ${rawBuffer.faceData.size} (face), ${rawBuffer.fingerData.size} (finger)")
        
        // Fix 1: Trim warm-up transient — discard first ~2 seconds to remove AE settling ramp
        val warmUpNs = 2_000_000_000L // 2 seconds in nanoseconds
        val trimmedBuffer = trimWarmUp(rawBuffer, warmUpNs)
        Log.i(tag, "WARMUP_TRIM | face: ${rawBuffer.faceData.size}->${trimmedBuffer.faceData.size} | " +
              "finger: ${rawBuffer.fingerData.size}->${trimmedBuffer.fingerData.size} | " +
              "trimmedFace=${rawBuffer.faceData.size - trimmedBuffer.faceData.size} | " +
              "trimmedFinger=${rawBuffer.fingerData.size - trimmedBuffer.fingerData.size}")
        
        // Motion rejection: filter out high-motion frames (P1-A)
        val motionResult = applyMotionRejection(trimmedBuffer)
        val filteredBuffer = motionResult.buffer
        val motionGate = motionResult.pttGate
        Log.d(tag, "After motion rejection: ${filteredBuffer.faceData.size} (face), ${filteredBuffer.fingerData.size} (finger)" +
              if (motionGate != null) " [GATED: $motionGate]" else "")
        
        var avgFaceSqi = 100
        var avgFingerSqi = 100
        if (preProcessedSignals != null && preProcessedSignals.isNotEmpty()) {
            avgFaceSqi = preProcessedSignals.map { it.faceSqi }.average().toInt()
            avgFingerSqi = preProcessedSignals.map { it.fingerSqi }.average().toInt()
        }
        Log.d(tag, "SQI Baseline: Face=$avgFaceSqi, Finger=$avgFingerSqi")
        
        // Calculate and Compensate Drift
        val stream1Timestamps = filteredBuffer.faceData.map { it.timestampNs }
        val stream2Timestamps = filteredBuffer.fingerData.map { it.timestampNs }
        
        val driftResult = TimestampSync.analyzeSynchronization(stream1Timestamps, stream2Timestamps)
        Log.d(tag, "Sync Analysis: ${driftResult.message}")
        
        // Log detailed metrics for debugging
        if (!driftResult.isValid || driftResult.stream1DropRate > 0.1 || driftResult.stream2DropRate > 0.1) {
            Log.w(tag, "Sync Warning: Jitter=${"%.1f".format(driftResult.stream1JitterMs)}/${"%.1f".format(driftResult.stream2JitterMs)} ms, " +
                       "Drops=${"%.1f".format(driftResult.stream1DropRate*100)}%/${"%.1f".format(driftResult.stream2DropRate*100)}%")
        }

        // Low FPS Warning (Gating): PTT requires high temporal resolution.
        // If effective rate < 25Hz, timing error > 40ms, which is too coarse for PTT.
        if (driftResult.stream1Rate < 25.0 || driftResult.stream2Rate < 25.0) {
            Log.w(tag, "Low FPS Warning: Rates ${"%.1f".format(driftResult.stream1Rate)}/${"%.1f".format(driftResult.stream2Rate)} fps. " +
                       "PTT accuracy may be degraded. Ensure better lighting.")
        }
        
        // P2-A: Conditional resampling — choose effective rate based on actual FPS quality
        val minFps = minOf(driftResult.stream1Rate, driftResult.stream2Rate)
        val isClean = driftResult.stream1JitterMs <= 5.0 && driftResult.stream2JitterMs <= 5.0 &&
                      driftResult.stream1DropRate <= 0.1 && driftResult.stream2DropRate <= 0.1
        val effectiveSampleRateHz = when {
            minFps >= 25.0 && isClean -> targetSampleRateHz  // 100Hz — full pipeline
            minFps >= 15.0 -> minOf(50.0, 2.0 * minFps)     // 30-50Hz — reduced precision
            else -> maxOf(20.0, minFps * 2.0)                // Minimal (safety floor: 20Hz)
        }
        val footDetectionAllowed = effectiveSampleRateHz >= 100.0
        Log.i(tag, "RESAMPLE | minFps=${"%.1f".format(minFps)} | clean=$isClean | " +
              "effectiveHz=${"%.0f".format(effectiveSampleRateHz)} | " +
              "footDetection=$footDetectionAllowed | rateRatio=${"%.6f".format(driftResult.rateRatio)}")

        val resampled = TimestampSync.resampleToUnifiedTimeline(
            stream1Data = filteredBuffer.faceData,
            stream2Data = filteredBuffer.fingerData,
            targetFrequencyHz = effectiveSampleRateHz
        )
        
        if (!resampled.isValid) {
            Log.w(tag, "Resampling failed: ${resampled.message}")
            return ProcessedSeries(
                timeMillis = emptyList(),
                faceSignal = doubleArrayOf(),
                fingerSignal = doubleArrayOf(),
                sampleRateHz = effectiveSampleRateHz,
                isValid = false,
                message = resampled.message,
                mainHarmonicsFace = HarmonicFeatureExtractor.HarmonicFeatures.empty(),
                mainHarmonicsFinger = HarmonicFeatureExtractor.HarmonicFeatures.empty()
            )
        }
        
        val rawFaceSignal = resampled.stream1Values.toDoubleArray()
        val rawFingerSignal = resampled.stream2Values.toDoubleArray()
        
        val timeMillis = resampled.unifiedTimestamps.map { it / 1_000_000.0 }
        
        var faceMotion: DoubleArray = doubleArrayOf()
        var fingerSat: DoubleArray = doubleArrayOf()
        var imuRms: DoubleArray = doubleArrayOf()
        var faceSqi: IntArray = intArrayOf()
        var fingerSqi: IntArray = intArrayOf()
        var consensusPtt: Double? = null
        
        if (rawBuffer.faceMotion != null && rawBuffer.fingerSaturation != null && resampled.isValid) {
            // Priority 1: Use raw buffer metrics if available (resampled to unified timeline)
            val interpMotion = TimestampSync.interpolateStream(rawBuffer.faceMotion, resampled.unifiedTimestamps)
            val interpSat = TimestampSync.interpolateStream(rawBuffer.fingerSaturation, resampled.unifiedTimestamps)
            val interpImu = if (rawBuffer.imuRms != null) {
                TimestampSync.interpolateStream(rawBuffer.imuRms, resampled.unifiedTimestamps)
            } else {
                List(timeMillis.size) { 0.0 }
            }
            
            faceMotion = interpMotion.toDoubleArray()
            fingerSat = interpSat.toDoubleArray()
            imuRms = interpImu.toDoubleArray()
            
            // Calculate simplistic SQI for now or reuse preProcessed if available
            // If preProcessed passed, we might want to still use its SQI?
            if (preProcessedSignals != null && preProcessedSignals.isNotEmpty()) {
                 val sourceSize = preProcessedSignals.size
                 val targetSize = timeMillis.size
                 faceSqi = IntArray(targetSize)
                 fingerSqi = IntArray(targetSize)
                 for (i in 0 until targetSize) {
                    val sourceIndex = (i.toDouble() / targetSize * sourceSize).toInt().coerceIn(0, sourceSize - 1)
                    val signal = preProcessedSignals[sourceIndex]
                    faceSqi[i] = signal.faceSqi
                    fingerSqi[i] = signal.fingerSqi
                 }
                 consensusPtt = preProcessedSignals.mapNotNull { it.consensusPtt }.lastOrNull()
            } else {
                 faceSqi = IntArray(timeMillis.size) { avgFaceSqi }
                 fingerSqi = IntArray(timeMillis.size) { avgFingerSqi }
            }
            
        } else if (preProcessedSignals != null && preProcessedSignals.isNotEmpty()) {
            // Priority 2: Use preProcessedSignals (Legacy path)
            faceMotion = DoubleArray(timeMillis.size)
            fingerSat = DoubleArray(timeMillis.size)
            imuRms = DoubleArray(timeMillis.size)
            faceSqi = IntArray(timeMillis.size)
            fingerSqi = IntArray(timeMillis.size)
            
            val sourceSize = preProcessedSignals.size
            val targetSize = timeMillis.size
            
            for (i in 0 until targetSize) {
                val sourceIndex = (i.toDouble() / targetSize * sourceSize).toInt().coerceIn(0, sourceSize - 1)
                val signal = preProcessedSignals[sourceIndex]
                
                faceMotion[i] = signal.faceMotionRms
                fingerSat[i] = signal.fingerSaturationPct
                imuRms[i] = signal.imuRmsG
                faceSqi[i] = signal.faceSqi
                fingerSqi[i] = signal.fingerSqi
            }
            
            consensusPtt = preProcessedSignals.mapNotNull { it.consensusPtt }.lastOrNull()
        }
        
        val motionFeatures = if (walkingModeEnabled && imuRms.isNotEmpty()) {
            imuAnalyzer.analyze(imuRms, effectiveSampleRateHz)
        } else {
            ImuMotionAnalyzer.MotionFeatures(null, 0.0, false)
        }
        
        val rawFaceRgbList = rawBuffer.faceRgb?.let { interpolateRgb(it, resampled.unifiedTimestamps) }
        val faceResult = processChannel(rawFaceSignal, avgFaceSqi, motionFeatures, rawFaceRgbList)
        
        val rawFingerRgbList = rawBuffer.fingerRgb?.let { interpolateRgb(it, resampled.unifiedTimestamps) }
        val fingerResult = processChannel(rawFingerSignal, avgFingerSqi, motionFeatures, rawFingerRgbList)
        
        // P1: Spectral SNR — compute after bandpass for gating
        val faceSnrDb = if (faceResult.mainSignal.size >= 64)
            snrEstimator.computeSnrDb(faceResult.mainSignal, effectiveSampleRateHz)
        else 0.0
        val fingerSnrDb = if (fingerResult.mainSignal.size >= 64)
            snrEstimator.computeSnrDb(fingerResult.mainSignal, effectiveSampleRateHz)
        else 0.0
        Log.i(tag, "SPECTRAL_SNR | faceSnrDb=${"%.1f".format(faceSnrDb)} | fingerSnrDb=${"%.1f".format(fingerSnrDb)}")
        
        val pttOutput = PttEngine.computePtt(
            faceSig = faceResult.mainSignal,
            fingerSig = fingerResult.mainSignal,
            faceRaw = rawFaceSignal,
            fingerRaw = rawFingerSignal,
            fsHz = effectiveSampleRateHz,
            faceMotionPenalty = 100.0,
            footDetectionEnabled = footDetectionAllowed
        )
        // Critical: Log result to file to survive logcat rotation/crash
        AppLogger.log(tag, "Pipeline Result: PTT=${pttOutput.pttMs} ms, Conf=${"%.2f".format(pttOutput.confidence)}, Valid=${pttOutput.isValid}")
        Log.i(tag, "Pipeline Result: PTT=${pttOutput.pttMs} ms, Conf=${"%.2f".format(pttOutput.confidence)}, Valid=${pttOutput.isValid}")
        
        var pttDenoised: PttOutput? = null
        if (faceResult.denoisedSignal != null && fingerResult.denoisedSignal != null) {
             pttDenoised = PttEngine.computePtt(
                faceSig = faceResult.denoisedSignal,
                fingerSig = fingerResult.denoisedSignal,
                faceRaw = rawFaceSignal,
                fingerRaw = rawFingerSignal,
                fsHz = effectiveSampleRateHz,
                faceMotionPenalty = 100.0,
                footDetectionEnabled = footDetectionAllowed
            )
            
            // P3-E DIAGNOSTIC: Compare main vs denoised PTT to detect wavelet foot-shift
            val mainPtt = pttOutput.pttMs
            val denoisedPtt = pttDenoised.pttMs
            if (mainPtt != null && denoisedPtt != null) {
                val shift = kotlin.math.abs(mainPtt - denoisedPtt)
                Log.i(tag, "WAVELET_DIAG | mainPttMs=${"%.1f".format(mainPtt)} | denoisedPttMs=${"%.1f".format(denoisedPtt)} | " +
                      "shiftMs=${"%.1f".format(shift)} | ${if (shift > 10.0) "⚠ SIGNIFICANT SHIFT > 10ms" else "OK"}")
            } else {
                Log.i(tag, "WAVELET_DIAG | mainPtt=${mainPtt?.let { "%.1f".format(it) } ?: "null"} | " +
                      "denoisedPtt=${denoisedPtt?.let { "%.1f".format(it) } ?: "null"} | cannot compare")
            }
        }
        
        var effectivePtt: PttOutput? = pttOutput
        var effectivePttDenoised: PttOutput? = pttDenoised
        var msg = "Processed ${timeMillis.size} samples"

        // ═══════════════════════════════════════════════════════════════
        // P0.1: Soft Gating — sigmoid membership → logit-space combination
        // Instead of binary pass/fail, each gate contributes a continuous
        // quality score μ ∈ (0,1). Combined in logit space so no single
        // gate collapses the result to zero.
        // ═══════════════════════════════════════════════════════════════
        val minRate = minOf(driftResult.stream1Rate, driftResult.stream2Rate)
        val clockDriftPpm = kotlin.math.abs(driftResult.rateRatio - 1.0) * 1_000_000
        val maxJitterMs = maxOf(driftResult.stream1JitterMs, driftResult.stream2JitterMs)
        val maxDropRate = maxOf(driftResult.stream1DropRate, driftResult.stream2DropRate)
        val interval1Ms = if (driftResult.stream1Rate > 0) 1000.0 / driftResult.stream1Rate else 1000.0
        val interval2Ms = if (driftResult.stream2Rate > 0) 1000.0 / driftResult.stream2Rate else 1000.0
        val maxIntervalMs = maxOf(interval1Ms, interval2Ms)
        val stabilityThresholdMs = maxOf(5.0, 0.50 * maxIntervalMs)

        // Sigmoid helper: σ((x - center) / scale)
        fun sigmoid(x: Double): Double = 1.0 / (1.0 + kotlin.math.exp(-x))
        fun logit(p: Double): Double = kotlin.math.ln(p / (1.0 - p))
        val eps = 0.02

        // ── Gate memberships: higher μ = better quality ──
        val muFps = sigmoid((minRate - 20.0) / 3.0).coerceIn(eps, 1.0 - eps)
        val muFaceSnr = sigmoid((faceSnrDb - FACE_SNR_GATE_DB) / 2.0).coerceIn(eps, 1.0 - eps)
        val muFingerSnr = sigmoid((fingerSnrDb - FINGER_SNR_GATE_DB) / 2.0).coerceIn(eps, 1.0 - eps)
        val muJitter = sigmoid((5.0 - maxJitterMs) / 2.0).coerceIn(eps, 1.0 - eps)
        val muDrops = sigmoid((0.10 - maxDropRate) / 0.03).coerceIn(eps, 1.0 - eps)
        val muClockDrift = sigmoid((2000.0 - clockDriftPpm) / 500.0).coerceIn(eps, 1.0 - eps)
        val muOffset = sigmoid((stabilityThresholdMs - driftResult.offsetStdMs) / 2.0).coerceIn(eps, 1.0 - eps)
        val muMotion = if (motionGate != null) 0.15 else (1.0 - eps)
        val muOffsetValid = if (driftResult.offsetValid) (1.0 - eps) else 0.20

        // ── Weighted logit-space combination ──
        data class GateEntry(val name: String, val weight: Double, val mu: Double)
        val gates = listOf(
            GateEntry("FPS", 2.0, muFps),
            GateEntry("FACE_SNR", 1.5, muFaceSnr),
            GateEntry("FINGER_SNR", 1.5, muFingerSnr),
            GateEntry("JITTER", 1.0, muJitter),
            GateEntry("DROPS", 1.0, muDrops),
            GateEntry("CLOCK_DRIFT", 1.5, muClockDrift),
            GateEntry("OFFSET_STABLE", 1.0, muOffset),
            GateEntry("MOTION", 1.0, muMotion),
            GateEntry("OFFSET_VALID", 1.0, muOffsetValid)
        )
        
        var logitSum = 0.0
        var weightSum = 0.0
        val weakGates = mutableListOf<String>()
        for (g in gates) {
            logitSum += g.weight * logit(g.mu)
            weightSum += g.weight
            if (g.mu < 0.30) weakGates += g.name // flag weak gates for logging
        }
        val pipelineQuality = sigmoid(logitSum / weightSum)

        // ── CRITICAL hard gates: only truly catastrophic conditions ──
        val criticalFailure = minRate < 15.0 || clockDriftPpm > 5000

        Log.i(tag, "SOFT_GATES | quality=${"%.3f".format(pipelineQuality)} | " +
              "fps=${"%.2f".format(muFps)} snrF=${"%.2f".format(muFaceSnr)} snrFi=${"%.2f".format(muFingerSnr)} " +
              "jitter=${"%.2f".format(muJitter)} drops=${"%.2f".format(muDrops)} clock=${"%.2f".format(muClockDrift)} " +
              "offset=${"%.2f".format(muOffset)} motion=${"%.2f".format(muMotion)} " +
              "weak=[${weakGates.joinToString(",")}] critical=$criticalFailure")

        // Fix 3: Extract bandSQI from raw pttOutput BEFORE gate nullification
        val rawSqiFace = pttOutput.sqiFace
        val rawSqiFinger = pttOutput.sqiFinger
        val rawConfidence = pttOutput.confidence
        val rawNBeats = pttOutput.nBeats

        if (criticalFailure) {
            effectivePtt = null
            effectivePttDenoised = null
            msg += " (CRITICAL: ${if (minRate < 15.0) "FPS<15" else "CLOCK>5000ppm"} — PTT Rejected)"
            AppLogger.warn(tag, "PTT CRITICAL rejection: fps=${"%.1f".format(minRate)}, drift=${"%.0f".format(clockDriftPpm)}ppm")
            Log.w(tag, "PTT CRITICAL rejection: fps=${"%.1f".format(minRate)}, drift=${"%.0f".format(clockDriftPpm)}ppm")
        } else if (weakGates.isNotEmpty()) {
            msg += " (soft-gated: ${weakGates.joinToString("|")})"
            Log.i(tag, "PTT soft-gated (quality=${"%.3f".format(pipelineQuality)}): ${weakGates.joinToString("|")}")
        }

        // Per-session debug summary — enriched for next-run diagnosis
        val gateReason = if (criticalFailure) "CRITICAL" else if (weakGates.isEmpty()) "NONE" else weakGates.joinToString("|")
        val sharedClockLikely = clockDriftPpm <= 500
        val qualityTier = effectivePtt?.qualityTier?.name ?: "NULL"
        val summaryMsg = "SESSION_SUMMARY | " +
            "fpsFace=${"%.1f".format(driftResult.stream1Rate)} | " +
            "fpsFinger=${"%.1f".format(driftResult.stream2Rate)} | " +
            "dropsFace=${"%.0f".format(driftResult.stream1DropRate*100)}% | " +
            "dropsFinger=${"%.0f".format(driftResult.stream2DropRate*100)}% | " +
            "jitterFaceMs=${"%.2f".format(driftResult.stream1JitterMs)} | " +
            "jitterFingerMs=${"%.2f".format(driftResult.stream2JitterMs)} | " +
            "sharedClockLikely=$sharedClockLikely | " +
            "rateRatio=${"%.6f".format(driftResult.rateRatio)} (${"%.0f".format(clockDriftPpm)}ppm) | " +
            "offsetMeanMs=${"%.1f".format(driftResult.offsetMs)} | " +
            "offsetStdMs=${"%.1f".format(driftResult.offsetStdMs)} | " +
            "bandSqiFace=$rawSqiFace | " +
            "bandSqiFinger=$rawSqiFinger | " +
            "faceSnrDb=${"%.1f".format(faceSnrDb)} | " +
            "fingerSnrDb=${"%.1f".format(fingerSnrDb)} | " +
            "confidence=${"%.3f".format(rawConfidence)} | " +
            "pipelineQuality=${"%.3f".format(pipelineQuality)} | " +
            "qualityTier=$qualityTier | " +
            "lagMedianMs=${effectivePtt?.pttMs?.let { "%.1f".format(it) } ?: "null"} | " +
            "nBeats=$rawNBeats | " +
            "weakGates=$gateReason | " +
            "pttMs=${effectivePtt?.pttMs?.let { "%.1f".format(it) } ?: "null"}"
        
        // Use AppLogger to ensure this critical summary is written to file
        AppLogger.log(PulseLog.SUMMARY, summaryMsg)
        Log.i(PulseLog.SUMMARY, summaryMsg)
        
        return ProcessedSeries(
            timeMillis = timeMillis,
            faceSignal = faceResult.mainSignal,
            fingerSignal = fingerResult.mainSignal,
            faceSignalDenoised = faceResult.denoisedSignal,
            fingerSignalDenoised = fingerResult.denoisedSignal,
            rawFaceSignal = rawFaceSignal,
            rawFingerSignal = rawFingerSignal,
            sampleRateHz = effectiveSampleRateHz,
            isValid = true,
            pttOutput = effectivePtt,
            pttOutputDenoised = effectivePttDenoised,
            message = msg,
            faceMotionRms = faceMotion,
            fingerSaturationPct = fingerSat,
            imuRmsG = imuRms,
            faceSqi = faceSqi,
            fingerSqi = fingerSqi,
            consensusPtt = consensusPtt,
            mainHarmonicsFace = faceResult.mainHarmonics,
            mainHarmonicsFinger = fingerResult.mainHarmonics,
            denoisedHarmonicsFace = faceResult.denoisedHarmonics,
            denoisedHarmonicsFinger = fingerResult.denoisedHarmonics,
            rawFaceRgb = rawFaceRgbList,
            rawFingerRgb = rawFingerRgbList,
            faceSignalPos = faceResult.posSignal,
            fingerSignalPos = fingerResult.posSignal,
            faceSnrDb = faceSnrDb,
            fingerSnrDb = fingerSnrDb
        )
    }

    private fun interpolateRgb(
        rawRgb: List<Pair<Long, Triple<Double, Double, Double>>>?,
        timestamps: List<Long>
    ): List<Triple<Double, Double, Double>>? {
        if (rawRgb == null || timestamps.isEmpty()) return null

        val rStream = rawRgb.map { TimestampedValue(it.first, it.second.first) }
        val gStream = rawRgb.map { TimestampedValue(it.first, it.second.second) }
        val bStream = rawRgb.map { TimestampedValue(it.first, it.second.third) }

        val rInterp = TimestampSync.interpolateStream(rStream, timestamps)
        val gInterp = TimestampSync.interpolateStream(gStream, timestamps)
        val bInterp = TimestampSync.interpolateStream(bStream, timestamps)

        return rInterp.indices.map { i ->
            Triple(rInterp[i], gInterp[i], bInterp[i])
        }
    }
    
    private fun processChannel(
        rawSignal: DoubleArray, 
        sqi: Int = 100, 
        motionFeatures: ImuMotionAnalyzer.MotionFeatures? = null,
        rgbTrace: List<Triple<Double, Double, Double>>? = null
    ): ChannelResult {
        if (rawSignal.isEmpty()) return ChannelResult(
            doubleArrayOf(), null, 
            HarmonicFeatureExtractor.HarmonicFeatures.empty(), null, null
        )
        
        // Remove mean first to minimize DC step response
        val zeroMean = DspFunctions.removeMean(rawSignal)
        val detrended = DspFunctions.detrendIIR(zeroMean, 0.5, targetSampleRateHz)
        
        // Gap E: Step-span exclusion — repair baseline change-points before bandpass
        // Pressure slips, residual AE steps cause step-like artifacts that ring
        // severely after bandpass filtering
        val stepResult = com.vivopulse.feature.processing.signal.ChangePointDetector.detectAndRepair(
            signal = detrended,
            sampleRate = targetSampleRateHz,
            cutoffHz = 0.3,
            threshold = 2.0,
            spanSeconds = 0.5
        )
        val stepCleaned = stepResult.cleaned
        if (stepResult.changePointIndices.isNotEmpty()) {
            Log.d(tag, "STEP_EXCL | ${stepResult.changePointIndices.size} change-points repaired")
        }

        // Pad 1.5s (covers >1 period of 0.7Hz cutoff), clamped to signal length
        val safePad = minOf(150, stepCleaned.size - 1)
        
        var mainFiltered = DspFunctions.filtfilt(
            signal = stepCleaned,
            padLength = safePad
        ) { sig ->
            DspFunctions.butterworthBandpass(
                signal = sig,
                lowCutoffHz = lowCutoffHz,
                highCutoffHz = highCutoffHz,
                sampleRateHz = targetSampleRateHz,
                order = 4
            )
        }
        
        var denoisedFiltered: DoubleArray? = null
        if (sqi in 40..80) {
            val waveletCleaned = WaveletDenoiser.denoise(stepCleaned, WaveletDenoiser.Config(levels = 4))
            denoisedFiltered = DspFunctions.filtfilt(
                signal = waveletCleaned,
                padLength = safePad
            ) { sig ->
                DspFunctions.butterworthBandpass(
                    signal = sig,
                    lowCutoffHz = lowCutoffHz,
                    highCutoffHz = highCutoffHz,
                    sampleRateHz = targetSampleRateHz,
                    order = 4
                )
            }
        }
        
        if (motionFeatures != null && walkingModeEnabled) {
            mainFiltered = StepNotchFilter.apply(mainFiltered, motionFeatures, targetSampleRateHz)
            if (denoisedFiltered != null) {
                denoisedFiltered = StepNotchFilter.apply(denoisedFiltered, motionFeatures, targetSampleRateHz)
            }
        }
        
        val mainNormalized = DspFunctions.zscoreNormalize(mainFiltered)
        val denoisedNormalized = if (denoisedFiltered != null) DspFunctions.zscoreNormalize(denoisedFiltered) else null
        
        val mainHarmonics = HarmonicFeatureExtractor.extractHarmonicFeatures(mainNormalized, targetSampleRateHz)
        val denoisedHarmonics = if (denoisedNormalized != null) {
            HarmonicFeatureExtractor.extractHarmonicFeatures(denoisedNormalized, targetSampleRateHz)
        } else {
            null
        }
        
        var posSignal: DoubleArray? = null
        if (rgbTrace != null && rgbTrace.isNotEmpty()) {
            val r = rgbTrace.map { it.first }.toDoubleArray()
            val g = rgbTrace.map { it.second }.toDoubleArray()
            val b = rgbTrace.map { it.third }.toDoubleArray()
            
            val rawPos = PosExtractor.computePosSignal(r, g, b, targetSampleRateHz)
            
            val detrendedPos = DspFunctions.detrendIIR(rawPos, 0.5, targetSampleRateHz)
            val filteredPos = DspFunctions.butterworthBandpass(
                signal = detrendedPos, 
                lowCutoffHz = lowCutoffHz, 
                highCutoffHz = highCutoffHz, 
                sampleRateHz = targetSampleRateHz
            )
            posSignal = DspFunctions.zscoreNormalize(filteredPos)
        }
        
        return ChannelResult(mainNormalized, denoisedNormalized, mainHarmonics, denoisedHarmonics, posSignal)
    }
}

data class RawSeriesBuffer(
    val faceData: List<TimestampedValue>,
    val fingerData: List<TimestampedValue>,
    val faceRgb: List<Pair<Long, Triple<Double, Double, Double>>>? = null,
    val fingerRgb: List<Pair<Long, Triple<Double, Double, Double>>>? = null,
    val faceMotion: List<TimestampedValue>? = null,
    val fingerSaturation: List<TimestampedValue>? = null,
    val imuRms: List<TimestampedValue>? = null,
    val faceRoi: List<Pair<Long, android.graphics.Rect>>? = null
)

data class ProcessedSeries(
    val timeMillis: List<Double>,
    val faceSignal: DoubleArray,
    val fingerSignal: DoubleArray,
    val faceSignalDenoised: DoubleArray? = null,
    val fingerSignalDenoised: DoubleArray? = null,
    val rawFaceSignal: DoubleArray = doubleArrayOf(),
    val rawFingerSignal: DoubleArray = doubleArrayOf(),
    val sampleRateHz: Double,
    val isValid: Boolean,
    val pttOutput: PttOutput? = null,
    val pttOutputDenoised: PttOutput? = null,
    val message: String = "",
    val faceMotionRms: DoubleArray = doubleArrayOf(),
    val fingerSaturationPct: DoubleArray = doubleArrayOf(),
    val imuRmsG: DoubleArray = doubleArrayOf(),
    val faceSqi: IntArray = intArrayOf(),
    val fingerSqi: IntArray = intArrayOf(),
    val consensusPtt: Double? = null,
    val mainHarmonicsFace: HarmonicFeatureExtractor.HarmonicFeatures = HarmonicFeatureExtractor.HarmonicFeatures.empty(),
    val mainHarmonicsFinger: HarmonicFeatureExtractor.HarmonicFeatures = HarmonicFeatureExtractor.HarmonicFeatures.empty(),
    val denoisedHarmonicsFace: HarmonicFeatureExtractor.HarmonicFeatures? = null,
    val denoisedHarmonicsFinger: HarmonicFeatureExtractor.HarmonicFeatures? = null,
    val faceSignalPos: DoubleArray? = null,
    val fingerSignalPos: DoubleArray? = null,
    val rawFaceRgb: List<Triple<Double, Double, Double>>? = null,
    val rawFingerRgb: List<Triple<Double, Double, Double>>? = null,
    val faceRoi: List<android.graphics.Rect?>? = null,
    val faceSnrDb: Double? = null,
    val fingerSnrDb: Double? = null
) {
    fun getSampleCount(): Int = timeMillis.size
    
    fun getDurationSeconds(): Double {
        return if (timeMillis.isNotEmpty()) {
            (timeMillis.last() - timeMillis.first()) / 1000.0
        } else {
            0.0
        }
    }
    
    fun isAligned(): Boolean {
        return timeMillis.size == faceSignal.size && 
               timeMillis.size == fingerSignal.size
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ProcessedSeries
        if (timeMillis != other.timeMillis) return false
        if (!faceSignal.contentEquals(other.faceSignal)) return false
        if (!fingerSignal.contentEquals(other.fingerSignal)) return false
        if (sampleRateHz != other.sampleRateHz) return false
        if (isValid != other.isValid) return false
        return true
    }

    override fun hashCode(): Int {
        var result = timeMillis.hashCode()
        result = 31 * result + faceSignal.contentHashCode()
        result = 31 * result + fingerSignal.contentHashCode()
        result = 31 * result + sampleRateHz.hashCode()
        result = 31 * result + isValid.hashCode()
        return result
    }
}
