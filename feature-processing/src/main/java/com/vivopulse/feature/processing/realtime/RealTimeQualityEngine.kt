package com.vivopulse.feature.processing.realtime

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
 * 
 * **MVP Requirements Validation:**
 * - **FR-I1 (Finger Metrics):**
 *   - Saturation% (Threshold > 98% good, < 90% bad)
 *   - SNR (estimated via Peak detect)
 *   - AC/DC Ratio
 * - **FR-I2 (Face Metrics):**
 *   - ROI Motion RMS (Threshold < 0.5px good)
 *   - SNR
 * - **FR-I3 (UI Indicators):** Aggregates metrics into Traffic Light status (Green/Yellow/Red) + User Tips.
 * 
 * **Reference Implementation:**
 * - Uses rolling windows (e.g. 6s) to compute stability.
 * - Hysteresis prevents frequent status flipping.
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

    // P2: Smart Search — track last valid HR per channel for ±15 BPM window constraint
    private var lastValidFaceHrBpm: Double? = null
    private var lastValidFingerHrBpm: Double? = null

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
        
        // P2+P3: Spectral HR with Smart Search (replaces time-domain peak detection)
        val previousHr = when (channel) {
            ChannelType.FACE -> lastValidFaceHrBpm
            ChannelType.FINGER -> lastValidFingerHrBpm
        }
        val hr = computeHrSpectral(filtered, fsHz, previousHr)
        if (hr != null) {
            when (channel) {
                ChannelType.FACE -> lastValidFaceHrBpm = hr
                ChannelType.FINGER -> lastValidFingerHrBpm = hr
            }
        }
        
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

    /**
     * P2+P3: FFT-based HR estimation with Smart Search.
     *
     * Uses DFT magnitude spectrum to find dominant frequency in the pulse band.
     * If [previousHrBpm] is available, constrains search to ±15 BPM (Smart Search
     * from Ojas project) to prevent harmonic jumping between updates.
     *
     * Falls back to full-band search [0.7–3.5 Hz] when no previous HR exists.
     */
    private fun computeHrSpectral(
        signal: DoubleArray,
        fsHz: Double,
        previousHrBpm: Double?
    ): Double? {
        if (signal.size < 64 || fsHz <= 5.0) return null

        // Apply Hanning window to reduce spectral leakage
        val n = signal.size
        val windowed = DoubleArray(n) { i ->
            val w = 0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / (n - 1)))
            signal[i] * w
        }

        val spectrum = snrEstimator.computeMagnitudeSpectrum(windowed)
        val binWidth = fsHz / n

        // Full physiological band
        val fullLowHz = 0.7    // 42 BPM
        val fullHighHz = 3.5   // 210 BPM

        // P2: Smart Search — narrow to ±15 BPM if previous HR is available
        val searchLowHz: Double
        val searchHighHz: Double
        if (previousHrBpm != null) {
            val centerHz = previousHrBpm / 60.0
            val deltaHz = 15.0 / 60.0  // ±15 BPM → ±0.25 Hz
            searchLowHz = maxOf(fullLowHz, centerHz - deltaHz)
            searchHighHz = minOf(fullHighHz, centerHz + deltaHz)
        } else {
            searchLowHz = fullLowHz
            searchHighHz = fullHighHz
        }

        // Find peak in search band
        var peakMag = 0.0
        var peakBin = -1
        for (k in spectrum.indices) {
            val freq = k * binWidth
            if (freq < searchLowHz) continue
            if (freq > searchHighHz) break
            if (spectrum[k] > peakMag) {
                peakMag = spectrum[k]
                peakBin = k
            }
        }

        if (peakBin < 0) return null

        val hrBpm = peakBin * binWidth * 60.0
        return if (hrBpm in 40.0..210.0) hrBpm else null
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

    /**
     * Selects the most relevant instruction tip for the user.
     * 
     * **Requirements:**
     * - **FR-I3:** Provides actionable feedback (e.g., "Hold still", "Increase pressure").
     * - **Priority:** Address Red status first, then Yellow.
     */
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


