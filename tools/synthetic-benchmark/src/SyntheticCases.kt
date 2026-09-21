package com.vivopulse.benchmark

import com.vivopulse.feature.processing.RawSeriesBuffer
import com.vivopulse.feature.processing.SignalProvenance
import com.vivopulse.feature.processing.timestamp.TimestampedValue
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlin.math.sin

/** Engineering fixtures, not a physiological population or a blood-pressure simulator. */
data class SyntheticCase(
    val id: String,
    val category: String,
    val description: String,
    val raw: RawSeriesBuffer,
    val expectedDelayMs: Double?,
    val expectValid: Boolean,
    val toleranceMs: Double = 10.0,
    val expectedHrBpm: Double? = null,
    val gate: String = if (expectValid) "accurate_or_reject" else "must_reject",
    val seed: Long = 0L,
    val latentPropagationDelayMs: Double? = expectedDelayMs,
    val effectiveOpticalDelayMs: Double? = expectedDelayMs,
    val nativeFaceFps: Double? = null,
    val nativeFingerFps: Double? = null,
    val truthNote: String = "",
    val beatOnsetsSec: List<Double> = emptyList(),
    val mainPulsePeakOffsetMs: Double = 130.0,
    val reflectedPulseOnsetOffsetMs: Double = 260.0,
    val timeOriginNs: Long = 10_000_000_000L,
    val fingerMainPulsePeakOffsetMs: Double = mainPulsePeakOffsetMs,
    val fingerReflectedPulseOnsetOffsetMs: Double = reflectedPulseOnsetOffsetMs
)

object SyntheticCases {
    private const val BASE_SEED = 20260921L
    private const val ORIGIN_NS = 10_000_000_000L
    private const val DURATION_SEC = 24.0

    private data class Spec(
        val id: String,
        val category: String,
        val description: String,
        val delayMs: Double = 100.0,
        val faceFps: Double = 60.0,
        val fingerFps: Double = faceFps,
        val hrBpm: Double = 72.0,
        val hrVariationBpm: Double = 0.0,
        val durationSec: Double = DURATION_SEC,
        val jitterMs: Double = 0.0,
        val dropFraction: Double = 0.0,
        val gapStartSec: Double = Double.POSITIVE_INFINITY,
        val gapEndSec: Double = Double.NEGATIVE_INFINITY,
        val fingerClockOffsetMs: Double = 0.0,
        val fingerClockDriftPpm: Double = 0.0,
        val timingVerified: Boolean = true,
        val missingFace: Boolean = false,
        val missingFinger: Boolean = false,
        val flatFace: Boolean = false,
        val flatFinger: Boolean = false,
        val noiseOnly: Boolean = false,
        val noiseSd: Double = 0.0,
        val fingerGain: Double = 1.0,
        val amplitudeModulation: Double = 0.0,
        val fingerRiseSec: Double = 0.130,
        val reflectionGain: Double = 0.22,
        val reflectionOffsetSec: Double = 0.260,
        val localDelayMs: Double = 0.0,
        val localDelayVariationMs: Double = 0.0,
        val clipFinger: Boolean = false,
        val allSaturated: Boolean = false,
        val flaggedMotion: Boolean = false,
        val sharedArtifact: Boolean = false,
        val gate: String = "accurate_or_reject",
        val toleranceMs: Double = 10.0,
        val uniqueDelayTruth: Boolean = true,
        val truthNote: String = ""
    )

    /** Each stream is sampled from the same continuous-time source, never shifted by array index. */
    fun all(): List<SyntheticCase> {
        val specs = mutableListOf<Spec>()
        for (fps in listOf(30.0, 60.0, 100.0)) {
            for (delay in listOf(60.5, 100.0, 137.5, 180.0)) {
                specs += Spec(
                    id = "clean_${fps.toInt()}fps_${delay.toString().replace('.', 'p')}ms",
                    category = "clean",
                    description = "Identical continuous pulse shapes; independently phased native sampling; fractional delay $delay ms.",
                    delayMs = delay, faceFps = fps, gate = "must_measure"
                )
            }
        }
        specs += listOf(
            Spec("zero_delay", "sign", "Zero-delay diagnostic outside positive physiological acceptance range.", delayMs = 0.0, gate = "diagnostic"),
            Spec("negative_delay", "sign", "Finger leads face by 100 ms; sign diagnostic, not a physiological claim.", delayMs = -100.0, gate = "diagnostic"),
            Spec("unequal_30_60fps", "sampling", "Face 30 fps and finger 60 fps share a true clock.", faceFps = 30.0, fingerFps = 60.0),
            Spec("unequal_60_30fps", "sampling", "Face 60 fps and finger 30 fps share a true clock.", faceFps = 60.0, fingerFps = 30.0),
            Spec("jitter_1ms", "sampling", "Independent bounded timestamp/acquisition jitter of +/-1 ms.", jitterMs = 1.0),
            Spec("jitter_8ms", "sampling", "Independent +/-8 ms acquisition jitter; timestamps remain truthful.", jitterMs = 8.0),
            Spec("drop_random_2pct", "dropouts", "Independent seeded 2% frame loss in each camera.", dropFraction = 0.02),
            Spec("drop_random_60pct", "dropouts", "60% frame loss; insufficient acquisition quality must reject.", dropFraction = 0.60, gate = "must_reject"),
            Spec("gap_finger_50ms", "dropouts", "Finger frames missing from 11.0 to 11.05 seconds.", gapStartSec = 11.0, gapEndSec = 11.05),
            Spec("gap_finger_8s", "dropouts", "Eight-second internal finger outage must not be interpolated into evidence.", gapStartSec = 8.0, gapEndSec = 16.0, gate = "must_reject"),
            Spec("clock_unverified", "clock", "Clock relation is not verified even though waveforms look clean.", timingVerified = false, gate = "must_reject"),
            Spec("clock_offset_unverified", "clock", "Finger timestamp labels have an unverified +40 ms offset.", fingerClockOffsetMs = 40.0, timingVerified = false, gate = "must_reject"),
            Spec("clock_offset_falsely_trusted", "clock", "Injected +40 ms label offset with falsely asserted clock trust; identifiability diagnostic.", fingerClockOffsetMs = 40.0, gate = "diagnostic", truthNote = "Observable labeled delay is 140 ms; physical optical delay remains 100 ms. The pipeline cannot infer an unknown constant clock offset from these signals alone."),
            Spec("clock_drift_200ppm", "clock", "Finger labels drift 200 ppm; no stationary labeled delay truth.", fingerClockDriftPpm = 200.0, timingVerified = false, gate = "must_reject", uniqueDelayTruth = false),
            Spec("clock_drift_falsely_trusted", "clock", "Finger labels drift 5000 ppm with falsely asserted clock trust.", fingerClockDriftPpm = 5000.0, gate = "diagnostic", uniqueDelayTruth = false),
            Spec("missing_face", "invalid_signal", "No face samples.", missingFace = true, gate = "must_reject", uniqueDelayTruth = false),
            Spec("missing_finger", "invalid_signal", "No finger samples.", missingFinger = true, gate = "must_reject", uniqueDelayTruth = false),
            Spec("flat_face", "invalid_signal", "Face contains only a constant DC level.", flatFace = true, gate = "must_reject", uniqueDelayTruth = false),
            Spec("flat_finger", "invalid_signal", "Finger contains only a constant DC level.", flatFinger = true, gate = "must_reject", uniqueDelayTruth = false),
            Spec("flat_both", "invalid_signal", "Both channels are flat.", flatFace = true, flatFinger = true, gate = "must_reject", uniqueDelayTruth = false),
            Spec("independent_noise", "invalid_signal", "Independent seeded Gaussian noise without pulse information.", noiseOnly = true, noiseSd = 3.0, gate = "must_reject", uniqueDelayTruth = false),
            Spec("pulse_with_noise", "noise", "Pulse plus independent Gaussian noise, SD 0.10 in normalized pulse units.", noiseSd = 0.10, toleranceMs = 15.0),
            Spec("finger_partial_clipping", "optical", "Finger waveform clipped above 0.55 pulse units; morphology changes.", clipFinger = true, gate = "diagnostic", uniqueDelayTruth = false),
            Spec("finger_saturated", "invalid_signal", "Finger is constant 255 with 100% saturation metadata.", allSaturated = true, gate = "must_reject", uniqueDelayTruth = false),
            Spec("pressure_amplitude_only", "pressure_sensitivity", "Finger gain reduced to 0.25; waveform timing unchanged.", fingerGain = 0.25),
            Spec("pressure_slow_amplitude", "pressure_sensitivity", "Finger amplitude varies smoothly by +/-40%; no injected transit change.", amplitudeModulation = 0.40, toleranceMs = 15.0),
            Spec("pressure_wider_upstroke", "pressure_sensitivity", "Finger main-component peak shifts from 130 to 180 ms after unchanged onset.", fingerRiseSec = 0.180, gate = "diagnostic", uniqueDelayTruth = false),
            Spec("pressure_reflection_change", "pressure_sensitivity", "Finger reflected component is stronger and begins earlier, with latent delay fixed.", reflectionGain = 0.65, reflectionOffsetSec = 0.180, gate = "diagnostic", uniqueDelayTruth = false),
            Spec("local_optical_delay_25ms", "pressure_sensitivity", "Injected local optical delay adds 25 ms to fixed 100 ms propagation.", localDelayMs = 25.0, truthNote = "125 ms optical delay does not mean propagation increased: the injected local component is 25 ms. No pressure in mmHg is simulated."),
            Spec("local_optical_delay_variable", "pressure_sensitivity", "Local optical shift varies +/-25 ms; latent propagation stays 100 ms.", localDelayVariationMs = 25.0, gate = "diagnostic", uniqueDelayTruth = false),
            Spec("hr_48bpm", "heart_rate", "Identical shapes at 48 bpm.", hrBpm = 48.0),
            Spec("hr_120bpm", "heart_rate", "Identical shapes at 120 bpm with overlapping reflected tails.", hrBpm = 120.0),
            Spec("hr_variable", "heart_rate", "Deterministic beat-to-beat HR variation, 72 +/-8 bpm.", hrVariationBpm = 8.0),
            Spec("motion_flagged", "motion", "Global IMU RMS is 0.30 g throughout; must reject delay.", flaggedMotion = true, gate = "must_reject"),
            Spec("common_optical_artifact", "identifiability", "Nonphysiological pulse-shaped illumination with a synthetic 100 ms channel-response delay.", sharedArtifact = true, gate = "diagnostic", uniqueDelayTruth = false, truthNote = "Coherence alone cannot prove a physiological origin. A common artificial waveform has a delayed channel response, not physiological propagation."),
            Spec("short_5s", "duration", "Only five seconds acquired; insufficient supported clean duration.", durationSec = 5.0, gate = "must_reject")
        )
        return specs.map { spec -> generate(spec, BASE_SEED + spec.id.hashCode().toLong()) }
    }

    private fun generate(spec: Spec, seed: Long): SyntheticCase {
        val beats = mutableListOf<Double>()
        var onset = -3.0
        while (onset <= spec.durationSec + 3.0) {
            beats += onset
            val instantaneousHr = spec.hrBpm + spec.hrVariationBpm * sin(2.0 * PI * 0.08 * onset)
            onset += 60.0 / instantaneousHr
        }

        fun waveform(t: Double, finger: Boolean): Double {
            if (spec.noiseOnly || (finger && spec.flatFinger) || (!finger && spec.flatFace)) return 0.0
            val localShift = if (finger) (spec.localDelayMs + spec.localDelayVariationMs * sin(2.0 * PI * 0.10 * t)) / 1000.0 else 0.0
            val shifted = t - (if (finger) spec.delayMs / 1000.0 else 0.0) - localShift
            val rise = if (finger) spec.fingerRiseSec else 0.130
            val reflectionGain = if (finger) spec.reflectionGain else 0.22
            val reflectionOffset = if (finger) spec.reflectionOffsetSec else 0.260
            var pulse = 0.0
            for (beat in beats) {
                val age = shifted - beat
                if (age >= 0.0 && age < 2.0) {
                    pulse += component(age, rise) + reflectionGain * component(age - reflectionOffset, 0.100)
                }
            }
            // This intentionally creates an indistinguishable signal shape from illumination,
            // but its metadata has no physiological delay truth.
            if (spec.sharedArtifact) return pulse
            val gain = if (finger) spec.fingerGain * (1.0 + spec.amplitudeModulation * sin(2.0 * PI * 0.08 * t)) else 1.0
            return pulse * gain
        }

        fun sample(finger: Boolean): List<TimestampedValue> {
            if ((finger && spec.missingFinger) || (!finger && spec.missingFace)) return emptyList()
            val fps = if (finger) spec.fingerFps else spec.faceFps
            val samplingRng = Random(seed xor if (finger) 0x73912L else 0x29183L)
            val dropRng = Random(seed xor if (finger) 0x63735L else 0x15623L)
            val noiseRng = Random(seed xor if (finger) 0x12691L else 0x91831L)
            val phaseSec = if (finger) 0.007 else 0.003
            val result = mutableListOf<TimestampedValue>()
            val count = (spec.durationSec * fps).toInt()
            for (i in 0 until count) {
                val jitter = (samplingRng.nextDouble() * 2.0 - 1.0) * spec.jitterMs / 1000.0
                // Bounded to <0.49 sample periods so independently jittered samples stay ordered.
                val t = i / fps + phaseSec + jitter.coerceIn(-0.49 / fps, 0.49 / fps)
                if (t < 0.0 || t >= spec.durationSec) continue
                if (dropRng.nextDouble() < spec.dropFraction) continue
                if (finger && t >= spec.gapStartSec && t < spec.gapEndSec) continue
                val clockT = if (finger) t * (1.0 + spec.fingerClockDriftPpm / 1_000_000.0) + spec.fingerClockOffsetMs / 1000.0 else t
                var pulse = waveform(t, finger) + spec.noiseSd * noiseRng.nextGaussian()
                if (finger && spec.clipFinger) pulse = pulse.coerceAtMost(0.55)
                val intensity = if (finger && spec.allSaturated) 255.0 else (if (finger) 130.0 + 14.0 * pulse else 100.0 + 3.0 * pulse)
                result += TimestampedValue(ORIGIN_NS + (clockT * 1e9).roundToLong(), intensity)
            }
            return result
        }

        val face = sample(false)
        val finger = sample(true)
        val raw = RawSeriesBuffer(
            faceData = face,
            fingerData = finger,
            fingerSaturation = if (spec.allSaturated) finger.map { TimestampedValue(it.timestampNs, 1.0) } else null,
            imuRms = if (spec.flaggedMotion) face.map { TimestampedValue(it.timestampNs, 0.30) } else null,
            provenance = SignalProvenance.SYNTHETIC,
            timingVerified = spec.timingVerified,
            hardwarePttCapable = true
        )
        val opticalDelay = if (spec.uniqueDelayTruth) spec.delayMs + spec.localDelayMs else null
        val labeledDelay = opticalDelay?.plus(spec.fingerClockOffsetMs)
        return SyntheticCase(
            id = spec.id, category = spec.category, description = spec.description, raw = raw,
            expectedDelayMs = labeledDelay, expectValid = spec.gate != "must_reject",
            toleranceMs = spec.toleranceMs,
            expectedHrBpm = if (spec.hrVariationBpm == 0.0 && !spec.noiseOnly && !spec.sharedArtifact && !spec.flatFace && !spec.flatFinger && !spec.allSaturated && !spec.missingFace && !spec.missingFinger) spec.hrBpm else null,
            gate = spec.gate, seed = seed,
            latentPropagationDelayMs = if (spec.noiseOnly || spec.sharedArtifact) null else spec.delayMs,
            effectiveOpticalDelayMs = opticalDelay,
            nativeFaceFps = spec.faceFps, nativeFingerFps = spec.fingerFps,
            truthNote = spec.truthNote.ifEmpty {
                if (spec.uniqueDelayTruth) "Truth applies before acquisition noise, filtering and resampling. Positive delay means finger later than face."
                else "No unique constant optical shift exists; latent delay, when present, is not a whole-wave alignment target."
            },
            beatOnsetsSec = if (spec.noiseOnly || spec.sharedArtifact) emptyList() else beats.filter { it >= 0.0 && it < spec.durationSec },
            timeOriginNs = ORIGIN_NS,
            fingerMainPulsePeakOffsetMs = spec.fingerRiseSec * 1000.0,
            fingerReflectedPulseOnsetOffsetMs = spec.reflectionOffsetSec * 1000.0
        )
    }

    /** Causal analytic component: zero before onset; maximum 1 at age == peakSec. */
    private fun component(ageSec: Double, peakSec: Double): Double {
        if (ageSec <= 0.0) return 0.0
        val x = ageSec / peakSec
        return x * x * exp(2.0 - 2.0 * x)
    }
}
