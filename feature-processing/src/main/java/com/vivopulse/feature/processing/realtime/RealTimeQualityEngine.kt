package com.vivopulse.feature.processing.realtime

import com.vivopulse.feature.processing.ptt.HeartRate
import com.vivopulse.feature.processing.ptt.PeakDetect
import com.vivopulse.feature.processing.signal.SnrEstimator
import com.vivopulse.signal.DspFunctions
import com.vivopulse.signal.RingBufferDouble
import com.vivopulse.signal.SignalSample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

enum class QualityStatus { GREEN, YELLOW, RED }
enum class ChannelType { FACE, FINGER }

data class ChannelQualityIndicator(
    val channel: ChannelType,
    val status: QualityStatus,
    val snrDb: Double?,
    val saturationPct: Double?,
    val motionRmsPx: Double?,
    val imuRmsG: Double?,
    val hrEstimateBpm: Double?,
    val acDcRatio: Double?,
    val sparkline: List<Double>,
    val diagnostics: List<String> = emptyList()
)

data class RealTimeQualityState(
    val face: ChannelQualityIndicator,
    val finger: ChannelQualityIndicator,
    val hrAgreementDeltaBpm: Double?,
    val tip: String?,
    val updatedAtMs: Long
)

/**
 * Near-real-time quality engine that consumes lightweight [SignalSample]s
 * and produces UI-friendly quality indicators at ~2-4 Hz.
 */
class RealTimeQualityEngine(
    bufferSeconds: Double = 20.0,
    private val windowSeconds: Double = 8.0,
    private val updateIntervalMs: Long = 400
) {
    private val snrEstimator = SnrEstimator()
    private val windowNs = (windowSeconds * 1e9).toLong()

    private val assumedMaxFs = 90 // generous upper bound for capacity
    private val bufferCapacity = max(32, (bufferSeconds * assumedMaxFs).toInt())
    private val faceBuffer = RingBufferDouble(bufferCapacity)
    private val fingerBuffer = RingBufferDouble(bufferCapacity)
    private val faceMotionBuffer = RingBufferDouble(bufferCapacity)
    private val fingerSaturationBuffer = RingBufferDouble(bufferCapacity)
    private val imuBuffer = RingBufferDouble(bufferCapacity)

    private var lastEmitMs = 0L
    private var lastTorchEnabled = false
    private var lastState: RealTimeQualityState? = null

    fun addSample(sample: SignalSample): RealTimeQualityState? {
        sample.faceMeanLuma?.let { faceBuffer.add(it, sample.timestampNs) }
        sample.fingerMeanLuma?.let { fingerBuffer.add(it, sample.timestampNs) }
        sample.faceMotionRmsPx?.let { faceMotionBuffer.add(it, sample.timestampNs) }
        sample.fingerSaturationPct?.let { fingerSaturationBuffer.add(it, sample.timestampNs) }
        sample.imuRmsG?.let { imuBuffer.add(it, sample.timestampNs) }
        lastTorchEnabled = sample.torchEnabled

        val nowMs = sample.timestampNs / 1_000_000
        if (nowMs - lastEmitMs < updateIntervalMs) {
            return null
        }

        val faceWindow = faceBuffer.snapshot(windowNs)
        val fingerWindow = fingerBuffer.snapshot(windowNs)
        
        // In sequential mode, one buffer might be empty. We should still emit what we have.
        val hasFace = faceWindow != null && faceWindow.values.size >= 30 // Reduced requirement
        val hasFinger = fingerWindow != null && fingerWindow.values.size >= 30
        
        if (!hasFace && !hasFinger) return null

        val faceMotion = faceMotionBuffer.snapshot(windowNs)?.values?.average()
        val fingerSat = fingerSaturationBuffer.snapshot(windowNs)?.values?.average()
        val imuRms = imuBuffer.snapshot(windowNs)?.values?.average()

        val faceIndicator = if (hasFace && faceWindow != null) {
            computeChannelIndicator(
                channel = ChannelType.FACE,
                window = faceWindow,
                auxMetric = faceMotion,
                saturationMetric = null,
                imuMetric = imuRms
            )
        } else {
            // Placeholder for inactive channel
            ChannelQualityIndicator(
                channel = ChannelType.FACE,
                status = QualityStatus.GREEN, // Default to green to not alarm
                snrDb = null, saturationPct = null, motionRmsPx = null, 
                imuRmsG = null, hrEstimateBpm = null, acDcRatio = null, 
                sparkline = emptyList(), diagnostics = listOf("Inactive")
            )
        }

        val fingerIndicator = if (hasFinger && fingerWindow != null) {
            computeChannelIndicator(
                channel = ChannelType.FINGER,
                window = fingerWindow,
                auxMetric = fingerSat,
                saturationMetric = fingerSat,
                imuMetric = imuRms
            )
        } else {
            ChannelQualityIndicator(
                channel = ChannelType.FINGER,
                status = QualityStatus.GREEN,
                snrDb = null, saturationPct = null, motionRmsPx = null,
                imuRmsG = null, hrEstimateBpm = null, acDcRatio = null,
                sparkline = emptyList(), diagnostics = listOf("Inactive")
            )
        }

        val hrDelta = if (faceIndicator.hrEstimateBpm != null && fingerIndicator.hrEstimateBpm != null) {
            abs(faceIndicator.hrEstimateBpm - fingerIndicator.hrEstimateBpm)
        } else null

        val tip = selectTip(faceIndicator, fingerIndicator, hrDelta)
        val state = RealTimeQualityState(
            face = faceIndicator,
            finger = fingerIndicator,
            hrAgreementDeltaBpm = hrDelta,
            tip = tip,
            updatedAtMs = nowMs
        )
        lastEmitMs = nowMs
        lastState = state
        return state
    }

    fun debugStats(): DebugStats {
        return DebugStats(
            faceSamples = faceBuffer.size(),
            fingerSamples = fingerBuffer.size(),
            lastFaceSnrDb = lastState?.face?.snrDb,
            lastFingerSnrDb = lastState?.finger?.snrDb
        )
    }

    data class DebugStats(
        val faceSamples: Int,
        val fingerSamples: Int,
        val lastFaceSnrDb: Double?,
        val lastFingerSnrDb: Double?
    )

    private fun computeChannelIndicator(
        channel: ChannelType,
        window: RingBufferDouble.SignalWindow,
        auxMetric: Double?,
        saturationMetric: Double?,
        imuMetric: Double?
    ): ChannelQualityIndicator {
        val fsHz = window.sampleRateHz()
        val detrended = DspFunctions.detrend(window.values)
        val filtered = DspFunctions.butterworthBandpass(
            signal = detrended,
            lowCutoffHz = 0.7,
            highCutoffHz = 4.0,
            sampleRateHz = fsHz
        )
        val snrDb = if (fsHz > 5.0) snrEstimator.computeSnrDb(filtered, fsHz) else null
        val hr = if (fsHz > 5.0) computeHr(filtered, fsHz) else null
        val acDc = computeAcDcRatio(window.values)
        val sparkline = window.normalized()

        val (status, diagnostics) = when (channel) {
            ChannelType.FACE -> evaluateFaceStatus(snrDb, auxMetric, imuMetric, hr)
            ChannelType.FINGER -> evaluateFingerStatus(snrDb, saturationMetric, imuMetric, hr)
        }

        return ChannelQualityIndicator(
            channel = channel,
            status = status,
            snrDb = snrDb,
            saturationPct = if (channel == ChannelType.FINGER) saturationMetric else null,
            motionRmsPx = if (channel == ChannelType.FACE) auxMetric else null,
            imuRmsG = imuMetric,
            hrEstimateBpm = hr,
            acDcRatio = acDc,
            sparkline = sparkline,
            diagnostics = diagnostics
        )
    }

    private fun computeHr(signal: DoubleArray, fsHz: Double): Double? {
        val peaks = PeakDetect.detectPeaks(signal, fsHz)
        val hrResult = HeartRate.computeHeartRate(peaks)
        return if (hrResult.isValid && HeartRate.isHrPlausible(hrResult.hrBpm)) {
            hrResult.hrBpm
        } else null
    }

    private fun computeAcDcRatio(values: DoubleArray): Double? {
        if (values.isEmpty()) return null
        val mean = values.average()
        val ac = sqrt(values.map { (it - mean).pow(2) }.average())
        val dc = abs(mean)
        if (dc < 1e-3) return null
        return ac / dc
    }

    private fun evaluateFaceStatus(
        snrDb: Double?,
        motion: Double?,
        imu: Double?,
        hr: Double?
    ): Pair<QualityStatus, List<String>> {
        val diagnostics = mutableListOf<String>()
        var status = QualityStatus.GREEN

        if (snrDb == null || snrDb < 3.0) {
            diagnostics.add("Face SNR < 3 dB")
            return QualityStatus.RED to diagnostics
        } else if (snrDb < 6.0) {
            diagnostics.add("Face SNR < 6 dB")
            status = QualityStatus.YELLOW
        }

        if (motion != null) {
            if (motion > 1.0) {
                diagnostics.add("Face motion > 1 px/frame")
                return QualityStatus.RED to diagnostics
            } else if (motion > 0.5) {
                diagnostics.add("Face motion > 0.5 px/frame")
                status = degrade(status, QualityStatus.YELLOW)
            }
        }
        
        if (imu != null && imu > 0.05) {
            diagnostics.add("High device motion")
            status = degrade(status, QualityStatus.YELLOW)
        }

        if (hr == null) {
            diagnostics.add("Face HR unresolved")
            status = degrade(status, QualityStatus.YELLOW)
        }

        return status to diagnostics
    }

    private fun evaluateFingerStatus(
        snrDb: Double?,
        saturationPct: Double?,
        imu: Double?,
        hr: Double?
    ): Pair<QualityStatus, List<String>> {
        val diagnostics = mutableListOf<String>()
        var status = QualityStatus.GREEN

        if (snrDb == null || snrDb < 4.0) {
            diagnostics.add("Finger SNR < 4 dB")
            return QualityStatus.RED to diagnostics
        } else if (snrDb < 10.0) {
            diagnostics.add("Finger SNR < 10 dB")
            status = QualityStatus.YELLOW
        }

        if (saturationPct != null) {
            when {
                saturationPct > 0.15 -> {
                    diagnostics.add("Saturation > 15%")
                    return QualityStatus.RED to diagnostics
                }
                saturationPct > 0.05 -> {
                    diagnostics.add("Saturation > 5%")
                    status = degrade(status, QualityStatus.YELLOW)
                }
            }
        }
        
        if (imu != null && imu > 0.05) {
            diagnostics.add("High device motion")
            status = degrade(status, QualityStatus.YELLOW)
        }

        if (hr == null) {
            diagnostics.add("Finger HR unresolved")
            status = degrade(status, QualityStatus.YELLOW)
        }

        return status to diagnostics
    }

    private fun selectTip(
        face: ChannelQualityIndicator,
        finger: ChannelQualityIndicator,
        hrDelta: Double?
    ): String? {
        val tips = mutableListOf<String>()
        val saturation = finger.saturationPct
        val fingerSnr = finger.snrDb ?: Double.NEGATIVE_INFINITY

        if (saturation != null && saturation > 0.05) {
            tips.add("Reduce finger pressure slightly")
        }
        if (fingerSnr < 8.0) {
            tips.add(if (lastTorchEnabled) "Increase ambient light" else "Enable torch for finger camera")
        }
        val faceMotion = face.motionRmsPx
        if (faceMotion != null && faceMotion > 0.5) {
            tips.add("Hold head steady")
        }
        if (hrDelta != null && hrDelta > 5.0) {
            tips.add("Stay still until both signals align")
        }
        return tips.firstOrNull()
    }

    private fun degrade(current: QualityStatus, candidate: QualityStatus): QualityStatus {
        return if (candidate.ordinal > current.ordinal) candidate else current
    }
}


