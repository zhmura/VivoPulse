package com.vivopulse.benchmark

import com.vivopulse.feature.processing.SignalPipeline
import com.vivopulse.feature.processing.PttCalculator
import com.vivopulse.feature.processing.ProcessedSeries
import com.vivopulse.feature.processing.ptt.*
import com.vivopulse.feature.processing.sync.SyncMetrics
import com.vivopulse.signal.DspFunctions
import com.vivopulse.signal.SavitzkyGolay
import java.io.File
import java.util.Locale
import kotlin.math.*

/** Exercises production Kotlin implementations. No alternate estimator is substituted. */
fun main(args: Array<String>) {
    Locale.setDefault(Locale.US)
    val output = File(args.firstOrNull { !it.startsWith("--") } ?: "build/synthetic-benchmark/results")
    output.mkdirs()
    val cases = if ("--probes-only" in args) emptyList() else SyntheticCases.all()
    val rows = cases.map {
        exportFixture(it, output)
        evaluateCase(it)
    }
    writeCsv(File(output, "results.csv"), rows)
    val probes = numericalProbes()
    writeCsv(File(output, "probes.csv"), probes)
    val scenarioFailures = rows.count { it["gate_result"] == "FAIL" }
    val diagnostics = rows.count { it["gate_result"] == "DIAGNOSTIC" }
    val probeFailures = probes.count { it["gate_result"] == "FAIL" }
    val stats = summaryStats(rows)
    writeCsv(File(output, "summary_stats.csv"), stats)
    val summary = "Synthetic scenarios=${rows.size}; failed=$scenarioFailures; diagnostic=$diagnostics. " +
        "Numerical probes=${probes.size}; failed=$probeFailures. " +
        "These are engineering checks, not clinical validation."
    File(output, "summary.txt").writeText(summary + "\n" + stats.joinToString("\n") { it.toString() } + "\n", Charsets.UTF_8)
    println(summary)
    println("Results: ${output.absolutePath}")
    if (scenarioFailures + probeFailures > 0) kotlin.system.exitProcess(1)
}

private fun evaluateCase(case: SyntheticCase): Map<String, Any?> {
    val row = linkedMapOf<String, Any?>(
        "id" to case.id, "category" to case.category, "description" to case.description,
        "gate" to case.gate, "expected_valid" to case.expectValid,
        "expected_delay_ms" to case.expectedDelayMs, "tolerance_ms" to case.toleranceMs,
        "expected_hr_bpm" to case.expectedHrBpm,
        "seed" to case.seed, "latent_propagation_delay_ms" to case.latentPropagationDelayMs,
        "effective_optical_delay_ms" to case.effectiveOpticalDelayMs,
        "native_face_fps" to case.nativeFaceFps, "native_finger_fps" to case.nativeFingerFps,
        "time_origin_ns" to case.timeOriginNs, "truth_note" to case.truthNote,
        "face_main_component_peak_offset_ms" to case.mainPulsePeakOffsetMs,
        "finger_main_component_peak_offset_ms" to case.fingerMainPulsePeakOffsetMs,
        "face_reflected_component_onset_offset_ms" to case.reflectedPulseOnsetOffsetMs,
        "finger_reflected_component_onset_offset_ms" to case.fingerReflectedPulseOnsetOffsetMs,
        "native_face_samples" to case.raw.faceData.size,
        "native_finger_samples" to case.raw.fingerData.size
    )
    val start = System.nanoTime()
    try {
        val series = SignalPipeline(targetSampleRateHz = 100.0).process(case.raw)
        val output = series.pttOutput
        val result = PttCalculator.computePtt(series)
        val pipelineReported = output?.isValid == true && output.pttMs?.isFinite() == true
        val wrapperReported = result.isValid && result.isPlausible && result.pttMs.isFinite()
        val expected = case.expectedDelayMs
        val pipelineError = if (pipelineReported && expected != null) output!!.pttMs!! - expected else null
        val wrapperError = if (wrapperReported && expected != null) result.pttMs - expected else null
        row.putAll(linkedMapOf(
            "series_valid" to series.isValid, "series_message" to series.message,
            "effective_hz" to series.sampleRateHz, "output_samples" to series.faceSignal.size,
            "pipeline_reported" to pipelineReported, "pipeline_numeric_ms" to output?.pttMs,
            "pipeline_valid" to output?.isValid, "pipeline_error_ms" to pipelineError,
            "pipeline_confidence_0_1" to output?.confidence, "pipeline_quality" to output?.qualityTier,
            "model_uncertainty_ms" to output?.kalmanCiMs,
            "pipeline_guidance" to output?.guidance?.joinToString(" | "),
            "wrapper_reported" to wrapperReported, "wrapper_numeric_ms" to result.pttMs,
            "wrapper_error_ms" to wrapperError, "wrapper_valid" to result.isValid,
            "wrapper_window_sd_ms" to result.stabilityMs, "wrapper_window_count" to result.windowCount,
            "wrapper_is_stable" to result.isStable,
            "wrapper_reliable" to result.isReliable, "wrapper_message" to result.message,
            "face_sqi" to output?.sqiFace, "finger_sqi" to output?.sqiFinger,
            "coherence" to output?.meanCoherenceAtHr, "foot_coverage" to output?.beatCoverage
        ))
        val noNumber = output?.pttMs == null
        val pipelineAccurate = pipelineError != null && abs(pipelineError) <= case.toleranceMs
        val wrapperAccurate = wrapperError != null && abs(wrapperError) <= case.toleranceMs
        val pass = when (case.gate) {
            "must_measure" -> pipelineReported && wrapperReported && pipelineAccurate && wrapperAccurate
            "must_reject" -> !pipelineReported && !wrapperReported && noNumber
            "accurate_or_reject" -> (!pipelineReported || pipelineAccurate) &&
                (!wrapperReported || wrapperAccurate) && (pipelineReported || noNumber)
            "diagnostic" -> true
            else -> error("Unknown gate: ${case.gate}")
        }
        row["gate_result"] = if (case.gate == "diagnostic") "DIAGNOSTIC" else if (pass) "PASS" else "FAIL"

        // Algorithm diagnostics on the same conditioned samples. These do not
        // override the production result or its rejection decision.
        if (series.isValid && series.faceSignal.size >= 100 && series.isAligned()) {
            val face = series.faceSignal
            val finger = series.fingerSignal
            val fs = series.sampleRateHz
            val facePeaks = PeakDetect.detectPeaks(face, fs)
            val fingerPeaks = PeakDetect.detectPeaks(finger, fs)
            val faceHr = HeartRate.computeHeartRate(facePeaks)
            val fingerHr = HeartRate.computeHeartRate(fingerPeaks)
            row["face_peak_count"] = facePeaks.indices.size
            row["finger_peak_count"] = fingerPeaks.indices.size
            row["face_hr_bpm"] = faceHr.hrBpm
            row["finger_hr_bpm"] = fingerHr.hrBpm
            row["face_hr_error_bpm"] = case.expectedHrBpm?.let { faceHr.hrBpm - it }
            row["finger_hr_error_bpm"] = case.expectedHrBpm?.let { fingerHr.hrBpm - it }
            diagnostic(row, "ncc") {
                val v = SyncMetrics.computeMetrics(face, finger, faceHr.hrBpm, fingerHr.hrBpm, fs)
                mapOf("ms" to v.lagMs, "correlation" to v.correlation, "error_ms" to expected?.let { v.lagMs - it })
            }
            diagnostic(row, "gcc") {
                val v = CrossCorr.gccPhatLag(face, finger, fs)
                mapOf("ms" to v.lagMs, "valid" to v.isValid, "error_ms" to expected?.let { v.lagMs - it })
            }
            diagnostic(row, "csp") {
                val v = CrossSpectralPhaseDelay.estimateDelay(face, finger, fs, (faceHr.hrBpm + fingerHr.hrBpm) / 2)
                mapOf("ms" to v.delayMs, "se_ms" to v.standardErrorMs, "bins" to v.nBins,
                    "error_ms" to expected?.let { v.delayMs - it })
            }
            diagnostic(row, "foot") {
                val values = IntersectingTangentFoot.computeFootToFootPtt(
                    IntersectingTangentFoot.detectFeet(face, fs, facePeaks.indices),
                    IntersectingTangentFoot.detectFeet(finger, fs, fingerPeaks.indices)
                ).sorted()
                val median = medianOrNull(values)
                mapOf("count" to values.size, "median_ms" to median,
                    "error_ms" to if (median != null && expected != null) median - expected else null)
            }
        }
    } catch (e: Exception) {
        row["gate_result"] = "FAIL"
        row["exception"] = "${e.javaClass.simpleName}: ${e.message}"
    }
    row["elapsed_ms"] = (System.nanoTime() - start) / 1e6
    println("${row["gate_result"]}: ${case.id} (delay=${row["pipeline_numeric_ms"]}, wrapper=${row["wrapper_numeric_ms"]})")
    return row
}

private fun diagnostic(row: MutableMap<String, Any?>, prefix: String, block: () -> Map<String, Any?>) {
    try { block().forEach { (key, value) -> row["${prefix}_$key"] = value } }
    catch (e: Exception) { row["${prefix}_exception"] = "${e.javaClass.simpleName}: ${e.message}" }
}

private fun numericalProbes(): List<Map<String, Any?>> {
    val rows = mutableListOf<Map<String, Any?>>()
    fun probe(id: String, criterion: String, block: () -> Pair<Boolean, String>) {
        val row = linkedMapOf<String, Any?>("id" to id, "criterion" to criterion)
        try {
            val (passed, observed) = block()
            row["gate_result"] = if (passed) "PASS" else "FAIL"
            row["observed"] = observed
        } catch (e: Exception) {
            row["gate_result"] = "FAIL"
            row["observed"] = "${e.javaClass.simpleName}: ${e.message}"
        }
        rows += row
        println("${row["gate_result"]}: $id (${row["observed"]})")
    }
    for (delayMs in listOf(-80.0, 0.0, 80.0)) {
        probe("spectral_sign_${delayMs.toInt()}", "CSP must recover signed analytic delay within 5 ms") {
            val fs = 100.0
            val f = 1.171875 // exact Welch bin, with three informative harmonics
            fun pulse(t: Double) = sin(2 * PI * f * t) + 0.4 * sin(4 * PI * f * t) + 0.2 * sin(6 * PI * f * t)
            val face = DoubleArray(6000) { pulse(it / fs) }
            val finger = DoubleArray(6000) { pulse(it / fs - delayMs / 1000.0) }
            val csp = CrossSpectralPhaseDelay.estimateDelay(face, finger, fs, f * 60, maxHarmonics = 3, segmentLength = 512)
            (csp.nBins >= 3 && csp.delayMs.isFinite() && abs(csp.delayMs - delayMs) <= 5) to
                "expected=$delayMs ms; observed=${csp.delayMs} ms; bins=${csp.nBins}; SE=${csp.standardErrorMs} ms"
        }
    }
    probe("cubic_sg_derivative", "Cubic SG reproduces derivative of t^3 at t=0; abs(error) <= 1e-10") {
        val signal = DoubleArray(101) { ((it - 50) / 100.0).pow(3) }
        val observed = SavitzkyGolay.firstDerivative(signal, 100.0, 7, 3)[50]
        (abs(observed) <= 1e-10) to "expected=0; observed=$observed"
    }
    probe("butterworth_fourth_order_edge", "One-pass fourth-order high edge gain at 0.7 Hz approximately -3dB (±0.01 amplitude)") {
        val signal = DoubleArray(12000) { sin(2 * PI * 0.7 * it / 100.0) }
        val filtered = DspFunctions.butterworthBandpass(signal, 0.7, 4.0, 100.0, 4)
        val rms = sqrt(filtered.sliceArray(6000 until 11000).map { it * it }.average())
        val gain = rms * sqrt(2.0)
        (abs(gain - 1 / sqrt(2.0)) <= 0.01) to "expected~0.7071; observed=$gain"
    }
    probe("fusion_no_evidence", "No contributing estimates must not produce a finite delay or stable result") {
        val v = PttKalmanFuser().fuse(emptyList())
        (!v.pttMs.isFinite() && v.methodsUsed == 0 && !v.isStable) to
            "delay=${v.pttMs}; methods=${v.methodsUsed}; stable=${v.isStable}"
    }
    probe("adapter_respects_rejection", "Result adapter must not recompute a rejected/missing pipeline delay or substitute numeric zero") {
        val face = DoubleArray(2000) { sin(2 * PI * 1.2 * it / 100.0) }
        val finger = DoubleArray(2000) { sin(2 * PI * 1.2 * (it / 100.0 - 0.1)) }
        val series = ProcessedSeries((0 until 2000).map { it * 10.0 }, face, finger,
            rawFaceSignal = face, rawFingerSignal = finger, sampleRateHz = 100.0, isValid = true, pttOutput = null)
        val v = PttCalculator.computePtt(series)
        (!v.isValid && !v.pttMs.isFinite()) to "valid=${v.isValid}; delay=${v.pttMs}"
    }
    probe("bounded_clean_window_stability", "Clean periodic delayed pulses must have window SD <=10ms without cardiac-cycle aliases") {
        fun pulse(t: Double) = sin(2 * PI * 1.2 * t) + 0.4 * sin(4 * PI * 1.2 * t) + 0.25 * sin(6 * PI * 1.2 * t)
        val face = DoubleArray(2400) { pulse(it / 100.0) }
        val finger = DoubleArray(2400) { pulse(it / 100.0 - 0.08) }
        val authoritative = PttOutput(pttMs = 80.0, corrScore = 0.99, confidence = 0.9,
            hrFaceBpm = 72.0, hrFingerBpm = 72.0, sqiFace = 90, sqiFinger = 90,
            qualityTier = PttSqi.QualityTier.HIGH, isValid = true)
        val series = ProcessedSeries((0 until 2400).map { it * 10.0 }, face, finger,
            rawFaceSignal = face, rawFingerSignal = finger, sampleRateHz = 100.0,
            isValid = true, pttOutput = authoritative)
        val v = PttCalculator.computePtt(series)
        (v.isValid && v.pttMs == 80.0 && v.windowCount >= 2 && v.stabilityMs.isFinite() &&
            v.stabilityMs <= 10.0 && v.isStable) to
            "delay=${v.pttMs}; windowSD=${v.stabilityMs}; windows=${v.windowCount}; stable=${v.isStable}"
    }
    probe("fusion_correlated_duplicates", "Repeating identical same-data estimates must not reduce model uncertainty") {
        val one = PttKalmanFuser().fuse(listOf(PttKalmanFuser.Measurement("A", 80.0, 100.0)))
        val four = PttKalmanFuser().fuse((1..4).map { PttKalmanFuser.Measurement("A$it", 80.0, 100.0) })
        (four.varianceMs2 + 1e-9 >= one.varianceMs2) to "one=${one.varianceMs2}; four=${four.varianceMs2} ms^2"
    }
    probe("gcc_zero_lag", "Identical nonconstant traces must recover zero delay within 1 ms") {
        val signal = DoubleArray(2000) { sin(2 * PI * 1.2 * it / 100.0) + 0.3 * sin(4 * PI * 1.2 * it / 100.0) }
        val v = CrossCorr.gccPhatLag(signal, signal, 100.0)
        (v.isValid && abs(v.lagMs) <= 1.0) to "delay=${v.lagMs}; valid=${v.isValid}"
    }
    probe("gcc_flat_rejection", "Flat traces must be numerically invalid") {
        val v = CrossCorr.gccPhatLag(DoubleArray(2000) { 128.0 }, DoubleArray(2000) { 128.0 }, 100.0)
        (!v.isValid) to "valid=${v.isValid}; delay=${v.lagMs}"
    }
    probe("gcc_no_pulse_band_rejection", "A pure 8Hz trace must not yield a valid pulse-band delay") {
        val face = DoubleArray(2000) { sin(2 * PI * 8.0 * it / 100.0) }
        val finger = DoubleArray(2000) { sin(2 * PI * 8.0 * (it / 100.0 - 0.08)) }
        val v = CrossCorr.gccPhatLag(face, finger, 100.0)
        (!v.isValid) to "valid=${v.isValid}; delay=${v.lagMs}; reason=${v.message}"
    }
    probe("gcc_configuration_rejection", "Invalid sample rate, window, search range and unequal lengths must be rejected") {
        val signal = DoubleArray(2000) { sin(2 * PI * 1.2 * it / 100.0) }
        val values = listOf(
            CrossCorr.gccPhatLag(signal, signal, 0.0),
            CrossCorr.gccPhatLag(signal, signal, 100.0, windowSec = -1.0),
            CrossCorr.gccPhatLag(signal, signal, 100.0, minLagMs = 500.0, maxLagMs = 400.0),
            CrossCorr.gccPhatLag(signal, signal.copyOf(1000), 100.0)
        )
        values.none { it.isValid } to "valid=${values.map { it.isValid }}"
    }
    probe("foot_matching_unique", "Two face feet cannot reuse one finger foot") {
        fun foot(t: Double) = IntersectingTangentFoot.FootResult(t, (t * 100).toInt(), 1.0, true)
        val v = IntersectingTangentFoot.computeFootToFootPtt(listOf(foot(1.0), foot(1.1)), listOf(foot(1.2)))
        (v.size <= 1) to "matched=${v.size}; delays=$v"
    }
    probe("clean_peak_localization", "20 isolated Gaussian pulses: 20 peaks and maximum sample timing error <=10 ms") {
        val expected = (0 until 20).map { it + 0.5 }
        val signal = DoubleArray(2000) { i -> expected.sumOf { t -> exp(-0.5 * ((i / 100.0 - t) / 0.08).pow(2)) } }
        val peaks = PeakDetect.detectPeaks(signal, 100.0)
        val error = if (peaks.timesMs.size == expected.size) peaks.timesMs.indices.maxOf { abs(peaks.timesMs[it] - expected[it] * 1000) } else Double.POSITIVE_INFINITY
        (peaks.timesMs.size == 20 && error <= 10.0) to "count=${peaks.timesMs.size}; maxError=$error ms"
    }
    probe("sqi_noise_discrimination", "Seeded broadband noise must score below a clean pulse with same sample count") {
        val fs = 100.0
        val random = java.util.Random(123)
        val clean = DoubleArray(2400) { 128 + 5 * sin(2 * PI * 1.2 * it / fs) }
        val noise = DoubleArray(2400) { 128 + 5 * random.nextGaussian() }
        fun sqi(raw: DoubleArray): ChannelSqiResult {
            val filtered = DspFunctions.butterworthBandpass(DspFunctions.removeMean(raw), 0.7, 4.0, fs, 4)
            val peaks = PeakDetect.detectPeaks(filtered, fs)
            return PttSqi.computeChannelSqi(filtered, raw, fs, peaks)
        }
        val c = sqi(clean)
        val n = sqi(noise)
        (c.sqi > n.sqi && c.snrDb > n.snrDb) to "clean=${c.sqi}/${c.snrDb} dB; noise=${n.sqi}/${n.snrDb} dB"
    }
    return rows
}

private fun medianOrNull(values: List<Double>): Double? = if (values.isEmpty()) null else
    if (values.size % 2 == 0) (values[values.size / 2 - 1] + values[values.size / 2]) / 2 else values[values.size / 2]

private fun summaryStats(rows: List<Map<String, Any?>>): List<Map<String, Any?>> =
    rows.groupBy { "${it["category"]}/${it["gate"]}" }.map { (group, entries) ->
        val errors = entries.mapNotNull { (it["pipeline_error_ms"] as? Number)?.toDouble() }.filter { it.isFinite() }
        linkedMapOf(
            "group" to group, "cases" to entries.size,
            "reported" to entries.count { it["pipeline_reported"] == true },
            "rejected" to entries.count { it["pipeline_reported"] == false },
            "gate_failures" to entries.count { it["gate_result"] == "FAIL" },
            "error_sample_count" to errors.size,
            "bias_ms" to errors.takeIf { it.isNotEmpty() }?.average(),
            "mae_ms" to errors.takeIf { it.isNotEmpty() }?.map { abs(it) }?.average(),
            "rmse_ms" to errors.takeIf { it.isNotEmpty() }?.let { sqrt(it.map { x -> x * x }.average()) },
            "worst_absolute_error_ms" to errors.maxOfOrNull { abs(it) }
        )
    }

private fun exportFixture(case: SyntheticCase, output: File) {
    val traces = File(output, "traces").apply { mkdirs() }
    val rows = listOf("face" to case.raw.faceData, "finger" to case.raw.fingerData).flatMap { (channel, samples) ->
        samples.mapIndexed { index, sample -> linkedMapOf<String, Any?>(
            "case_id" to case.id, "channel" to channel, "sample_index" to index,
            "timestamp_ns" to sample.timestampNs, "intensity" to sample.value
        ) }
    }
    writeCsv(File(traces, "${case.id}.csv"), rows)
    // Analytic component landmarks, not detected composite maxima. Morphology
    // changes intentionally move composite peaks relative to latent propagation.
    val landmarks = case.beatOnsetsSec.mapIndexed { index, onset -> linkedMapOf<String, Any?>(
        "beat_index" to index, "physical_onset_s" to onset,
        "face_main_component_peak_s" to onset + case.mainPulsePeakOffsetMs / 1000.0,
        "face_reflected_component_onset_s" to onset + case.reflectedPulseOnsetOffsetMs / 1000.0,
        "finger_main_component_peak_s" to case.effectiveOpticalDelayMs?.let { onset + (it + case.fingerMainPulsePeakOffsetMs) / 1000.0 },
        "finger_reflected_component_onset_s" to case.effectiveOpticalDelayMs?.let { onset + (it + case.fingerReflectedPulseOnsetOffsetMs) / 1000.0 },
        "truth_note" to case.truthNote
    ) }
    writeCsv(File(traces, "${case.id}_landmarks.csv"), landmarks)
}

private fun writeCsv(file: File, rows: List<Map<String, Any?>>) {
    val columns = rows.flatMap { it.keys }.distinct()
    fun cell(value: Any?): String = "\"" + (value?.toString() ?: "").replace("\"", "\"\"") + "\""
    file.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.appendLine(columns.joinToString(",") { cell(it) })
        rows.forEach { row -> writer.appendLine(columns.joinToString(",") { cell(row[it]) }) }
    }
}
