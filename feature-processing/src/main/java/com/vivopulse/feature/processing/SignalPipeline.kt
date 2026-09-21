package com.vivopulse.feature.processing

import com.vivopulse.feature.processing.ptt.PttEngine
import com.vivopulse.feature.processing.ptt.PttOutput
import com.vivopulse.feature.processing.timestamp.TimestampSync
import com.vivopulse.feature.processing.timestamp.TimestampedValue
import com.vivopulse.feature.processing.signal.SnrEstimator
import com.vivopulse.signal.DspFunctions
import com.vivopulse.signal.HarmonicFeatureExtractor
import com.vivopulse.signal.PosExtractor
import com.vivopulse.signal.ProcessedSignal
import kotlin.math.abs

/**
 * Conservative offline pipeline for experimental inter-site optical pulse delay.
 * A reportable delay requires every acquisition gate and the estimator to pass.
 * These engineering thresholds are not clinical accuracy guarantees.
 */
class SignalPipeline(
    private val targetSampleRateHz: Double = 100.0,
    private val lowCutoffHz: Double = 0.7,
    private val highCutoffHz: Double = 4.0,
    private val correlationWindowSec: Double = 20.0,
    private val walkingModeEnabled: Boolean = false,
    private val motionRejectionThresholdG: Double = 0.1
) {
    companion object {
        const val FACE_SNR_GATE_DB = 3.0
        const val FINGER_SNR_GATE_DB = 6.0
        const val MAX_INTERPOLATION_GAP_MS = 100.0
        const val MIN_USABLE_DURATION_SECONDS = 8.0
    }

    fun process(rawBuffer: RawSeriesBuffer, preProcessedSignals: List<ProcessedSignal>? = null): ProcessedSeries {
        val reasons = mutableListOf<String>()
        fun reject(reason: String) { if (reason !in reasons) reasons.add(reason) }
        fun invalid(reason: String): ProcessedSeries {
            reject(reason)
            return ProcessedSeries(emptyList(), doubleArrayOf(), doubleArrayOf(),
                sampleRateHz = targetSampleRateHz, isValid = false,
                message = reasons.joinToString("|"), invalidReasons = reasons.toList(),
                provenance = rawBuffer.provenance, timingVerified = rawBuffer.timingVerified,
                // Preserve source observations even when no common timeline is available.
                sourceFaceData = rawBuffer.faceData, sourceFingerData = rawBuffer.fingerData)
        }
        if (!targetSampleRateHz.isFinite() || targetSampleRateHz <= 2 * highCutoffHz || lowCutoffHz <= 0 || highCutoffHz <= lowCutoffHz)
            return invalid("INVALID_PROCESSING_CONFIGURATION")
        if (rawBuffer.faceData.isEmpty() || rawBuffer.fingerData.isEmpty()) return invalid("MISSING_CHANNEL")
        if ((rawBuffer.faceData + rawBuffer.fingerData).any { !it.value.isFinite() }) return invalid("NONFINITE_SAMPLE")
        if (!TimestampSync.validateMonotonicity(rawBuffer.faceData.map { it.timestampNs }).isValid ||
            !TimestampSync.validateMonotonicity(rawBuffer.fingerData.map { it.timestampNs }).isValid)
            return invalid("NONMONOTONIC_TIMESTAMPS")
        if (!rawBuffer.timingVerified) reject("UNVERIFIED_TIMEBASE")
        if (!rawBuffer.hardwarePttCapable) reject("HARDWARE_HR_ONLY")
        if (walkingModeEnabled) reject("WALKING_DELAY_NOT_VALIDATED")

        // Trim one common absolute warmup interval, preserving all relative timing.
        val warmupEnd = maxOf(rawBuffer.faceData.first().timestampNs, rawBuffer.fingerData.first().timestampNs) + 2_000_000_000L
        val faceData = rawBuffer.faceData.filter { it.timestampNs >= warmupEnd }
        val fingerData = rawBuffer.fingerData.filter { it.timestampNs >= warmupEnd }
        if (faceData.size < 3 || fingerData.size < 3) return invalid("INSUFFICIENT_DURATION")
        val sync = TimestampSync.analyzeSynchronization(faceData.map { it.timestampNs }, fingerData.map { it.timestampNs })
        if (!sync.isValid) return invalid("INVALID_TIMELINE")
        if (sync.overlapDurationMs < MIN_USABLE_DURATION_SECONDS * 1000) reject("INSUFFICIENT_DURATION")
        val nativeRate = minOf(sync.stream1Rate, sync.stream2Rate)
        if (nativeRate < 25.0) reject("LOW_NATIVE_FPS")
        if (maxOf(sync.stream1JitterMs, sync.stream2JitterMs) > 5.0) reject("TIMESTAMP_JITTER")
        if (maxOf(sync.stream1DropRate, sync.stream2DropRate) > 0.10) reject("FRAME_DROPS")
        // Interpolation density is a computation setting, not an improvement in sensor precision.
        val effectiveRate = if (nativeRate >= 25.0) targetSampleRateHz else minOf(targetSampleRateHz, maxOf(20.0, 2 * nativeRate))
        val sampled = TimestampSync.resampleToUnifiedTimeline(faceData, fingerData, effectiveRate, MAX_INTERPOLATION_GAP_MS)
        if (!sampled.isValid) return invalid(sampled.message)
        val rawFace = sampled.stream1Values.toDoubleArray()
        val rawFinger = sampled.stream2Values.toDoubleArray()
        if (!hasVariation(rawFace) || !hasVariation(rawFinger)) reject("FLAT_SIGNAL")

        val start = sampled.unifiedTimestamps.first()
        val end = sampled.unifiedTimestamps.last()
        fun inWindow(data: List<TimestampedValue>?) = data.orEmpty().filter { it.timestampNs in start..end }
        val motion = inWindow(rawBuffer.imuRms)
        val faceMotion = inWindow(rawBuffer.faceMotion)
        val saturation = inWindow(rawBuffer.fingerSaturation)
        if ((motion + faceMotion + saturation).any { !it.value.isFinite() }) reject("INVALID_QUALITY_METADATA")
        // Conservative whole-session rejection: do not remove motion frames then bridge their gaps.
        if (motion.any { it.value > motionRejectionThresholdG }) reject("MOTION")
        if (faceMotion.any { it.value > 2.0 }) reject("FACE_MOTION")
        // Capture stores saturation as a fraction, despite the legacy Pct suffix.
        if (saturation.any { it.value > 0.05 }) reject("SATURATION")
        // Legacy live summaries do not carry the sensor timebase, so they cannot
        // identify this retained interval. Quality comes from timestamped raw
        // acquisition metrics and independent raw spectral checks below.

        val filteredFace = filter(rawFace, effectiveRate)
        val filteredFinger = filter(rawFinger, effectiveRate)
        if (filteredFace.any { !it.isFinite() } || filteredFinger.any { !it.isFinite() }) return invalid("NONFINITE_FILTER_OUTPUT")
        // Assess signal/noise before bandpass, avoiding self-certified SNR after suppression.
        val estimator = SnrEstimator()
        val faceSnr = estimator.computeSnrDb(rawFace, effectiveRate)
        val fingerSnr = estimator.computeSnrDb(rawFinger, effectiveRate)
        if (!faceSnr.isFinite() || faceSnr < FACE_SNR_GATE_DB) reject("LOW_FACE_SNR")
        if (!fingerSnr.isFinite() || fingerSnr < FINGER_SNR_GATE_DB) reject("LOW_FINGER_SNR")
        if (hasAbruptStep(rawFace) || hasAbruptStep(rawFinger)) reject("PHOTOMETRIC_STEP")

        // POS is diagnostic until its fiducial timing has independent validation.
        val faceRgb = interpolateRgb(rawBuffer.faceRgb, sampled.unifiedTimestamps)
        val fingerRgb = interpolateRgb(rawBuffer.fingerRgb, sampled.unifiedTimestamps)
        val output = if (reasons.isEmpty()) PttEngine.computePtt(
            filteredFace, filteredFinger, rawFace, rawFinger, effectiveRate,
            footDetectionEnabled = nativeRate >= 25.0
        ) else null
        if (output != null && (!output.isValid || output.pttMs?.isFinite() != true)) reject("ESTIMATOR_REJECTED")
        val authoritative = output?.takeIf { reasons.isEmpty() && it.isValid && it.pttMs?.isFinite() == true }
        fun qualityTrace(data: List<TimestampedValue>?) = if (data.isNullOrEmpty()) doubleArrayOf()
            else TimestampSync.interpolateStream(data, sampled.unifiedTimestamps).toDoubleArray()
        return ProcessedSeries(
            timeMillis = sampled.unifiedTimestamps.map { (it - start) / 1_000_000.0 },
            faceSignal = filteredFace, fingerSignal = filteredFinger,
            rawFaceSignal = rawFace, rawFingerSignal = rawFinger,
            sampleRateHz = effectiveRate, isValid = true, pttOutput = authoritative,
            message = if (reasons.isEmpty()) "Experimental optical delay; clinical accuracy not established" else reasons.joinToString("|"),
            invalidReasons = reasons.toList(), provenance = rawBuffer.provenance,
            timingVerified = rawBuffer.timingVerified,
            timelineOriginNs = start,
            sourceFaceData = rawBuffer.faceData, sourceFingerData = rawBuffer.fingerData,
            faceMotionRms = qualityTrace(rawBuffer.faceMotion), fingerSaturationPct = qualityTrace(rawBuffer.fingerSaturation),
            imuRmsG = qualityTrace(rawBuffer.imuRms),
            mainHarmonicsFace = HarmonicFeatureExtractor.extractHarmonicFeatures(filteredFace, effectiveRate),
            mainHarmonicsFinger = HarmonicFeatureExtractor.extractHarmonicFeatures(filteredFinger, effectiveRate),
            rawFaceRgb = faceRgb, rawFingerRgb = fingerRgb,
            faceSignalPos = diagnosticPos(faceRgb, effectiveRate), fingerSignalPos = diagnosticPos(fingerRgb, effectiveRate),
            faceSnrDb = faceSnr, fingerSnrDb = fingerSnr,
            nativeFaceRateHz = sync.stream1Rate, nativeFingerRateHz = sync.stream2Rate)
    }

    private fun hasVariation(signal: DoubleArray): Boolean {
        val mean = signal.average()
        return signal.map { (it - mean) * (it - mean) }.average() > 1e-12
    }

    private fun hasAbruptStep(signal: DoubleArray): Boolean {
        if (signal.size < 3) return false
        val differences = signal.asList().zipWithNext { a, b -> abs(b - a) }
        val sorted = differences.sorted()
        val median = sorted[sorted.size / 2]
        val amplitude = (signal.maxOrNull() ?: 0.0) - (signal.minOrNull() ?: 0.0)
        return differences.any { it > maxOf(12 * median, 0.25 * amplitude, 1e-8) }
    }

    private fun filter(signal: DoubleArray, fs: Double): DoubleArray {
        // Identical offline zero-phase filtering for both channels; no reconstructed spans,
        // channel-specific causal detrending, wavelet replacement, or adaptive motion notch.
        val zeroMean = DspFunctions.removeMean(signal)
        val result = DspFunctions.filtfilt(zeroMean, minOf((1.5 * fs).toInt(), signal.size - 1)) {
            DspFunctions.butterworthBandpass(it, lowCutoffHz, highCutoffHz, fs, order = 4)
        }
        return DspFunctions.zscoreNormalize(result)
    }

    private fun interpolateRgb(raw: List<Pair<Long, Triple<Double, Double, Double>>>?, times: List<Long>): List<Triple<Double, Double, Double>>? {
        if (raw.isNullOrEmpty()) return null
        val channels = listOf<(Triple<Double, Double, Double>) -> Double>({ it.first }, { it.second }, { it.third })
            .map { component -> TimestampSync.interpolateStream(raw.map { TimestampedValue(it.first, component(it.second)) }, times) }
        if (channels.any { channel -> channel.any { !it.isFinite() } }) return null
        return times.indices.map { Triple(channels[0][it], channels[1][it], channels[2][it]) }
    }

    private fun diagnosticPos(rgb: List<Triple<Double, Double, Double>>?, fs: Double): DoubleArray? {
        if (rgb == null) return null
        return PosExtractor.computePosSignal(rgb.map { it.first }.toDoubleArray(), rgb.map { it.second }.toDoubleArray(), rgb.map { it.third }.toDoubleArray(), fs)
    }
}

enum class SignalProvenance { REAL, SYNTHETIC }
data class RawSeriesBuffer(
    val faceData: List<TimestampedValue>,
    val fingerData: List<TimestampedValue>,
    val faceRgb: List<Pair<Long, Triple<Double, Double, Double>>>? = null,
    val fingerRgb: List<Pair<Long, Triple<Double, Double, Double>>>? = null,
    val faceMotion: List<TimestampedValue>? = null,
    val fingerSaturation: List<TimestampedValue>? = null,
    val imuRms: List<TimestampedValue>? = null,
    val faceRoi: List<Pair<Long, android.graphics.Rect>>? = null,
    val provenance: SignalProvenance = SignalProvenance.REAL,
    /** Explicit acquisition evidence, never inferred from timestamp spans. */
    val timingVerified: Boolean = false,
    val hardwarePttCapable: Boolean = false
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
    val fingerSnrDb: Double? = null,
    val invalidReasons: List<String> = emptyList(),
    val provenance: SignalProvenance = SignalProvenance.REAL,
    val timingVerified: Boolean = false,
    val sourceFaceData: List<TimestampedValue> = emptyList(),
    val sourceFingerData: List<TimestampedValue> = emptyList(),
    val nativeFaceRateHz: Double? = null,
    val nativeFingerRateHz: Double? = null,
    val timelineOriginNs: Long? = null
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
        // StateFlow must publish changed validity/provenance even when waveform values match.
        if (pttOutput != other.pttOutput || invalidReasons != other.invalidReasons) return false
        if (provenance != other.provenance || timingVerified != other.timingVerified) return false
        if (message != other.message || timelineOriginNs != other.timelineOriginNs) return false
        if (sourceFaceData != other.sourceFaceData || sourceFingerData != other.sourceFingerData) return false
        if (nativeFaceRateHz != other.nativeFaceRateHz || nativeFingerRateHz != other.nativeFingerRateHz) return false
        return true
    }

    override fun hashCode(): Int {
        var result = timeMillis.hashCode()
        result = 31 * result + faceSignal.contentHashCode()
        result = 31 * result + fingerSignal.contentHashCode()
        result = 31 * result + sampleRateHz.hashCode()
        result = 31 * result + isValid.hashCode()
        result = 31 * result + (pttOutput?.hashCode() ?: 0)
        result = 31 * result + invalidReasons.hashCode()
        result = 31 * result + provenance.hashCode()
        result = 31 * result + timingVerified.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + (timelineOriginNs?.hashCode() ?: 0)
        result = 31 * result + sourceFaceData.hashCode()
        result = 31 * result + sourceFingerData.hashCode()
        result = 31 * result + (nativeFaceRateHz?.hashCode() ?: 0)
        result = 31 * result + (nativeFingerRateHz?.hashCode() ?: 0)
        return result
    }
}
