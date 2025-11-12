package com.vivopulse.app.trend

import android.content.Context
import com.vivopulse.feature.processing.wave.WaveFeatures
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class VascularTrendSummary(
    val index: Int, // 0..100 (50 ~ baseline)
    val deltaPttMs: Double?,
    val deltaRiseTimeMs: Double?,
    val deltaReflectionRatio: Double?
)

/**
 * Rolling baseline store over the last N good sessions.
 *
 * - Stores sessions meeting quality gates:
 *   PTT confidence ≥ 70% and combined SQI ≥ 70
 * - Keeps window of last [windowSize] entries
 */
class VascularTrendStore(
    private val context: Context,
    private val fileName: String = "vascular_trend_store.json",
    private val windowSize: Int = 10
) {
    private val file: File by lazy { File(context.filesDir, fileName) }

    data class Entry(
        val timestampMs: Long,
        val pttMs: Double,
        val riseTimeMs: Double,
        val reflectionRatio: Double
    )

    fun maybeRecordAndSummarize(
        pttMs: Double?,
        pttConfidencePercent: Double,
        combinedSqi: Double,
        profile: WaveFeatures.VascularWaveProfile,
        minBaselineCount: Int = 5
    ): VascularTrendSummary? {
        if (pttMs == null) return null
        if (pttConfidencePercent < 70.0 || combinedSqi < 70.0) return null
        val rise = profile.meanRiseTimeMs ?: return null
        val refl = profile.meanReflectionRatio ?: return null

        // Load history (excluding current)
        val history = load().toMutableList()

        // Compute baseline on previous entries
        val baseline = computeBaseline(history)
        val summary = if (baseline.count >= minBaselineCount) {
            val deltaPtt = pttMs - baseline.meanPtt
            val deltaRise = rise - baseline.meanRise
            val deltaRefl = refl - baseline.meanRefl
            val index = computeIndex(
                deltaPtt, baseline.stdPtt,
                deltaRise, baseline.stdRise,
                deltaRefl, baseline.stdRefl
            )
            VascularTrendSummary(
                index = index,
                deltaPttMs = deltaPtt,
                deltaRiseTimeMs = deltaRise,
                deltaReflectionRatio = deltaRefl
            )
        } else {
            null
        }

        // Append current entry and persist (always keep up to windowSize)
        history.add(
            Entry(
                timestampMs = System.currentTimeMillis(),
                pttMs = pttMs,
                riseTimeMs = rise,
                reflectionRatio = refl
            )
        )
        while (history.size > windowSize) {
            history.removeAt(0)
        }
        save(history)

        return summary
    }

    data class Baseline(
        val count: Int,
        val meanPtt: Double,
        val meanRise: Double,
        val meanRefl: Double,
        val stdPtt: Double,
        val stdRise: Double,
        val stdRefl: Double
    )

    private fun computeBaseline(entries: List<Entry>): Baseline {
        if (entries.isEmpty()) {
            return Baseline(0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        }
        val pttList = entries.map { it.pttMs }
        val riseList = entries.map { it.riseTimeMs }
        val reflList = entries.map { it.reflectionRatio }
        val meanPtt = pttList.average()
        val meanRise = riseList.average()
        val meanRefl = reflList.average()
        val stdPtt = stddev(pttList, meanPtt)
        val stdRise = stddev(riseList, meanRise)
        val stdRefl = stddev(reflList, meanRefl)
        return Baseline(entries.size, meanPtt, meanRise, meanRefl, stdPtt, stdRise, stdRefl)
    }

    private fun stddev(values: List<Double>, mean: Double): Double {
        if (values.isEmpty()) return 1.0
        val varv = values.map { (it - mean) * (it - mean) }.average()
        val std = kotlin.math.sqrt(varv)
        return if (std < 1e-6) 1.0 else std
    }

    /**
     * Map standardized deltas to a 0..100 index (50 ~ baseline).
     *
     * Positive (more elastic-like) contributions:
     * - Higher PTT (longer PTT)
     * - Lower rise time
     * - Lower reflection ratio
     */
    private fun computeIndex(
        deltaPtt: Double, stdPtt: Double,
        deltaRise: Double, stdRise: Double,
        deltaRefl: Double, stdRefl: Double
    ): Int {
        val zPtt = (deltaPtt / stdPtt).coerceIn(-2.0, 2.0)
        val zRise = (deltaRise / stdRise).coerceIn(-2.0, 2.0)
        val zRefl = (deltaRefl / stdRefl).coerceIn(-2.0, 2.0)
        // Elastic composite: PTT positive, rise negative, refl negative
        val composite = (zPtt - zRise - zRefl) / 3.0
        val idx = (50.0 + composite * 12.5).toInt()
        return idx.coerceIn(0, 100)
    }

    private fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONArray(file.readText())
            val out = ArrayList<Entry>(json.length())
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                out.add(
                    Entry(
                        timestampMs = o.optLong("t", 0L),
                        pttMs = o.optDouble("ptt", Double.NaN),
                        riseTimeMs = o.optDouble("rise", Double.NaN),
                        reflectionRatio = o.optDouble("refl", Double.NaN)
                    )
                )
            }
            out.filter { it.pttMs.isFinite() && it.riseTimeMs.isFinite() && it.reflectionRatio.isFinite() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(entries: List<Entry>) {
        try {
            val arr = JSONArray()
            entries.forEach {
                arr.put(
                    JSONObject().apply {
                        put("t", it.timestampMs)
                        put("ptt", it.pttMs)
                        put("rise", it.riseTimeMs)
                        put("refl", it.reflectionRatio)
                    }
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) {
            // no-op persistence failure
        }
    }
}


