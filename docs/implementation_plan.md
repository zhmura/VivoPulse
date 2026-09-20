# PTT Pipeline Hardening — Implementation Plan

Fixes for all actionable risks from [design_audit.md](file:///home/ext.siarhei.zhmura/Work/pulse/docs/design_audit.md).
Organized in 4 phases (P0→P3) by ROI.

> [!IMPORTANT]
> Each phase is independently deployable and testable. P0 alone should eliminate the majority of observed PTT failures.

---

## Phase 0 — Capture Stability (P0)

Fixes the root causes upstream: AE instability and CPU-heavy extraction.

---

### P0-A: Stability-Based AE Lock (Audit #3)

#### [MODIFY] [DualCameraController.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/DualCameraController.kt)

**Current** (L686-692): Lock after fixed 30 frames.

**Change**: Replace frame-count trigger with luma stability detector.

```kotlin
// New fields
private val lumaSamples = ArrayDeque<Double>(30)  // Rolling window
private val LUMA_SETTLE_MS = 400L
private val LUMA_CHANGE_EPSILON = 1.5    // ~0.6% on 0-255 scale
private val LUMA_VARIANCE_DELTA = 5.0
private val MAX_AE_WAIT_FRAMES = 90      // 3s @ 30fps fallback

// In processFrame(), replace the AE lock block:
fun shouldLockExposure(luma: Double?, tracker: FpsTracker): Boolean {
    luma ?: return false
    lumaSamples.addLast(luma)
    if (lumaSamples.size > 15) lumaSamples.removeFirst()  // ~500ms window
    
    // Hard timeout fallback
    if (tracker.totalFrames > MAX_AE_WAIT_FRAMES) return true
    
    // Need at least 10 samples (~330ms)
    if (lumaSamples.size < 10) return false
    
    // Check stability: mean change and variance
    val mean = lumaSamples.average()
    val firstHalf = lumaSamples.take(lumaSamples.size / 2).average()
    val secondHalf = lumaSamples.drop(lumaSamples.size / 2).average()
    val meanChange = kotlin.math.abs(firstHalf - secondHalf)
    val variance = lumaSamples.map { (it - mean).pow(2) }.average()
    
    return meanChange < LUMA_CHANGE_EPSILON && variance < LUMA_VARIANCE_DELTA
}
```

**Also**: For finger camera, set fixed focus distance via Camera2Interop (AF lock) since finger covers lens:

```kotlin
// In CameraBindingHelper, for back camera builder:
Camera2Interop.Extender(backBuilder)
    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) // infinity
```

---

### P0-B: Lightweight Signal Extraction (Audit #4)

#### [MODIFY] [RgbExtractor.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/RgbExtractor.kt)

**Current**: Per-pixel `YUV→RGB` conversion with floating-point math for every pixel in ROI.

**Change**: Add a fast luma-only path and a green-proxy path. Keep full RGB as fallback.

```kotlin
/**
 * Fast luma-only extraction — no color conversion.
 * For finger PPG (red-dominated with torch, luma sufficient).
 */
fun extractAverageLuma(image: ImageProxy, roi: Rect): Double? {
    val yPlane = image.planes[0]
    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    val constrained = constrainRoi(roi, image.width, image.height),
    var sum = 0L
    var count = 0
    for (y in constrained.top until constrained.bottom step 2) { // 2x subsampling
        for (x in constrained.left until constrained.right step 2) {
            val idx = y * yRowStride + x
            if (idx < yBuffer.limit()) {
                sum += yBuffer.get(idx).toInt() and 0xFF
                count++
            }
        }
    }
    return if (count > 0) sum.toDouble() / count else null
}

/**
 * Green-proxy from YUV — avoids full RGB matrix.
 * G ≈ Y - 0.344*U - 0.714*V (dominant rPPG channel)
 */
fun extractAverageGreenProxy(image: ImageProxy, roi: Rect): Double? {
    // Similar to extractAverageLuma but with U/V correction
    // ~40% faster than full RGB (skips R and B computation)
}
```

#### [MODIFY] [DualCameraController.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/DualCameraController.kt)

**Change** (L639-666): Use fast paths per camera:

```diff
 // Finger camera
-fingerRgb = RgbExtractor.extractAverageRgb(imageProxy, roi)
-fingerLuma = fingerRgb?.g
+fingerLuma = RgbExtractor.extractAverageLuma(imageProxy, roi)

 // Face camera  
-faceRgb = RgbExtractor.extractAverageRgb(imageProxy, faceRoi)
-faceLuma = faceRgb?.g
+faceLuma = RgbExtractor.extractAverageGreenProxy(imageProxy, faceRoi)
```

> [!NOTE]
> Keep `extractAverageRgb()` available for debug/recording frames (not hot path).

---

## Phase 1 — Safety & Correctness (P1)

Eliminates harmful fail-safes and adds cross-device validation.

---

### P1-A: Replace Motion Rejection Fail-Safe (Audit #5)

#### [MODIFY] [SignalPipeline.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/SignalPipeline.kt)

**Current** (L54-57): If all frames exceed threshold → keep all.

**Change**: Return early with `pttGated=ALL_MOTION`. Allow HR-only if finger has ≥3s stable window.

```kotlin
private fun applyMotionRejection(rawBuffer: RawSeriesBuffer): MotionResult {
    val imuData = rawBuffer.imuRms ?: return MotionResult(rawBuffer, gated = null)
    if (imuData.isEmpty()) return MotionResult(rawBuffer, gated = null)

    // Compute per-sample RMS in 200ms sliding windows (not point-proximity)
    val windowNs = 200_000_000L
    val validWindows = computeSlidingMotionScore(imuData, windowNs, motionRejectionThresholdG)
    
    val validTimestamps = validWindows.map { it.timestampNs }.toSet()
    
    if (validTimestamps.isEmpty()) {
        Log.w(tag, "Motion: ALL windows exceeded ${motionRejectionThresholdG}G — PTT unavailable")
        
        // Check if finger has ≥3s of data (allow HR-only)
        val fingerDurationS = rawBuffer.fingerData.let {
            if (it.size >= 2) (it.last().timestampNs - it.first().timestampNs) / 1e9 else 0.0
        }
        return if (fingerDurationS >= 3.0) {
            MotionResult(rawBuffer, gated = "ALL_MOTION")  // HR-only mode
        } else {
            MotionResult(rawBuffer.empty(), gated = "ALL_MOTION_NO_DATA")
        }
    }
    
    // ... rest of filtering logic (same as current) ...
}
```

### P1-B: Clock Domain Runtime Validation (Audit #1)

#### [NEW] [ClockValidator.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/camera/ClockValidator.kt)

Validates that both camera streams share the same clock domain during the first 2 seconds of capture.

```kotlin
object ClockValidator {
    data class ClockResult(
        val isSharedClock: Boolean,
        val offsetVarianceMs: Double,
        val message: String
    )
    
    /**
     * Compare Image.timestamp vs CaptureResult.SENSOR_TIMESTAMP for both streams.
     * If they diverge > threshold, the clock domains are different.
     */
    fun validate(
        frontPairs: List<Pair<Long, Long>>,  // (Image.ts, CaptureResult.ts)
        backPairs: List<Pair<Long, Long>>
    ): ClockResult {
        // For each stream: compute offset = Image.ts - CaptureResult.ts
        // Both should be ~0 if same clock
        // Cross-stream: compute variance of (front_offset - back_offset)
        // If variance > 2ms → CLOCK_MISMATCH
    }
}
```

#### [MODIFY] [DualCameraController.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/DualCameraController.kt)

- Capture `CaptureResult.SENSOR_TIMESTAMP` in the first 60 frames via `Camera2Interop` capture callback
- Feed pairs to `ClockValidator`
- If mismatch → set `pttCapable = false`

#### [MODIFY] [SignalPipeline.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/SignalPipeline.kt)

- Add `CLOCK_MISMATCH` gate in the timing quality gate section (L255)

---

## Phase 2 — Precision Improvements (P2)

Reduces false precision and improves long-recording accuracy.

---

### P2-A: Conditional Resampling (Audit #2)

#### [MODIFY] [TimestampSync.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/timestamp/TimestampSync.kt)

**Current** (L315-381): Always resamples to 100Hz.

**Change**: Make target frequency conditional:

```kotlin
fun resampleToUnifiedTimeline(
    stream1Data: List<TimestampedValue>,
    stream2Data: List<TimestampedValue>,
    targetFrequencyHz: Double = 100.0,
    minFps: Double = 30.0,            // NEW: actual min FPS
    qualityFlags: QualityFlags? = null // NEW: jitter/drops
): ResampledData {
    // Determine effective resampling rate
    val effectiveHz = when {
        minFps >= 25.0 && (qualityFlags?.isClean ?: true) -> targetFrequencyHz  // 100Hz
        minFps >= 15.0 -> minOf(50.0, 2.0 * minFps)   // 30-50Hz, XCorr only
        else -> minFps * 2.0                            // Minimal upsampling
    }
    // ... rest uses effectiveHz instead of targetFrequencyHz ...
}
```

#### [MODIFY] [SignalPipeline.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/SignalPipeline.kt)

- Pass `minFps` and quality flags to `resampleToUnifiedTimeline()`
- Add `footDetectionAllowed` flag based on effective Hz ≥ 100

#### [MODIFY] [PTTConsensus.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/ptt/PTTConsensus.kt)

- Accept `footDetectionAllowed: Boolean` parameter
- If false → skip foot detection, return XCorr-only result

---

### P2-B: Timestamp Drift Correction (Audit #6)

#### [MODIFY] [TimestampSync.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/timestamp/TimestampSync.kt)

**Current** (L214-282): `calculateRobustOffset` returns one-shot median offset.

**Change**: After computing offset, fit simple linear model through paired timestamps:

```kotlin
fun calculateRobustOffsetWithDrift(
    stream1: List<Long>, stream2: List<Long>, n: Int = 30
): DriftCorrectedOffset {
    // 1. Existing robust offset logic → get pairs
    val pairs: List<Pair<Long, Long>> = /* existing pairing logic */
    
    // 2. Linear regression: t2 = a*t1 + b
    //    a = rate ratio (should be ~1.0)
    //    b = offset (already computed as median)
    val (a, b) = linearRegression(pairs)
    
    // 3. Apply drift correction during resampling:
    //    corrected_t2 = t2 / a  (re-normalize to stream1's clock rate)
    
    return DriftCorrectedOffset(
        offsetMs = b / 1_000_000.0,
        rateRatio = a,
        nPairs = pairs.size,
        residualMs = /* mean absolute residual after fit */
    )
}
```

---

### P2-C: Gate Bitmask Logging (Audit #10a)

#### [MODIFY] [SignalPipeline.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/SignalPipeline.kt)

**Current** (L255-278): Hierarchical `if-else` chain reports only first failure.

**Change**: Collect all failures, report primary + log all:

```kotlin
val failedGates = mutableListOf<String>()
if (!driftResult.offsetValid) failedGates += "INVALID_OFFSET"
if (minRate < 25.0) failedGates += "LOW_FPS"
if (driftResult.stream1JitterMs > 5.0 || driftResult.stream2JitterMs > 5.0) failedGates += "HIGH_JITTER"
if (driftResult.stream1DropRate > 0.1 || driftResult.stream2DropRate > 0.1) failedGates += "HIGH_DROPS"
// NEW gates:
if (clockMismatch) failedGates += "CLOCK_MISMATCH"
if (allMotion) failedGates += "ALL_MOTION"

if (failedGates.isNotEmpty()) {
    effectivePtt = null
    effectivePttDenoised = null
    val primary = failedGates.first()
    Log.w(tag, "PTT gated: primary=$primary, all=${failedGates.joinToString("|")}")
}
```

---

## Phase 3 — DSP & Detection Hardening (P3)

Incremental accuracy improvements for edge cases.

---

### P3-A: Dual SQI (Audit #10b)

#### [MODIFY] [PttSqi.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/ptt/PttSqi.kt)

Add band-limited SQI alongside raw SQI:

```kotlin
data class DualSqi(
    val rawSqi: ChannelSqiResult,      // Current behavior (detects capture problems)
    val bandSqi: ChannelSqiResult,     // NEW: computed on filtered signal only
    val captureOk: Boolean,            // rawSqi > threshold
    val signalOk: Boolean              // bandSqi > threshold
)

fun computeDualChannelSqi(
    filteredSignal: DoubleArray,
    rawSignal: DoubleArray,
    fsHz: Double,
    peakResult: PeakDetectResult,
    motionPenalty: Double = 100.0
): DualSqi {
    val rawSqi = computeChannelSqi(filteredSignal, rawSignal, fsHz, peakResult, motionPenalty)
    val bandSqi = computeChannelSqi(filteredSignal, filteredSignal, fsHz, peakResult, motionPenalty)
    return DualSqi(
        rawSqi = rawSqi,
        bandSqi = bandSqi,
        captureOk = rawSqi.sqi >= 20,  // Low bar: detect clipping/AE steps
        signalOk = bandSqi.sqi >= 40   // Higher bar: actual pulse presence
    )
}
```

---

### P3-B: Consensus Lag Window Alignment (Audit #9)

#### [MODIFY] [CrossCorr.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/ptt/CrossCorr.kt)

**Current** (L67): `maxLagSamples = (fsHz * 0.2).toInt()` → ±200ms.

**Change**: Extend to ±400ms with confidence decay:

```diff
-val maxLagSamples = (fsHz * 0.2).toInt() // 200ms
+val maxLagSamples = (fsHz * 0.4).toInt() // 400ms — matches foot detection range
```

Add lag-dependent confidence penalty in `CrossCorrResult`:

```kotlin
val lagConfidence = if (abs(lagMs) > 200) {
    1.0 - ((abs(lagMs) - 200) / 300.0).coerceIn(0.0, 0.5) // Decay from 200-500ms
} else 1.0
```

#### [MODIFY] [PTTConsensus.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/ptt/PTTConsensus.kt)

**Current** (L60): `if (lag in 30.0..500.0)` — foot matching window.
**Change**: Narrow to `30.0..400.0` to align with extended XCorr range.

---

### P3-C: Peak Detection Robustness (Audit #8a)

#### [MODIFY] [PeakDetect.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/ptt/PeakDetect.kt)

Replace `mean + 0.3 * std` threshold with MAD-based:

```kotlin
// Current:
// val threshold = mean + 0.3 * std

// New: MAD-based threshold (robust to outliers)
val sorted = signal.sorted()
val median = sorted[sorted.size / 2]
val mad = sorted.map { abs(it - median) }.sorted()[sorted.size / 2]
val threshold = median + 2.0 * 1.4826 * mad  // 1.4826 = MAD→std conversion
```

Also add prominence check: reject peaks where `prominence < 0.3 * median_prominence`.

---

### P3-D: Adaptive Bandpass Cutoff (Audit #7b)

#### [MODIFY] [SignalPipeline.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/SignalPipeline.kt)

After preliminary HR estimate, adjust low cutoff:

```kotlin
val adaptiveLowCutoff = if (prelimHrBpm != null && prelimHrBpm > 0) {
    maxOf(0.5, (prelimHrBpm / 60.0) * 0.5)  // Half the HR frequency
} else {
    0.7  // Default
}
```

---

### P3-E: Wavelet Foot-Shift Validation (Audit #7a)

#### [NEW] [WaveletFootShiftTest.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/test/java/com/vivopulse/feature/processing/tests/WaveletFootShiftTest.kt)

Unit test to validate wavelet denoising doesn't shift foot positions:

```kotlin
@Test
fun `wavelet denoising preserves foot timing within 2ms`() {
    // Generate synthetic PPG with known foot positions
    // Apply wavelet denoising
    // Re-detect feet
    // Assert max shift < 2ms
}
```

If test fails → remove wavelet from PTT code path (keep for HR-only).

---

## Verification Plan

### Per-Phase Tests

| Phase | Tests | Pass Criteria |
|---|---|---|
| P0-A | Unit: stability detector with synthetic luma ramps | Lock triggers after settled, not during ramp |
| P0-B | Unit: `extractAverageLuma()` matches Y-channel ground truth | < 0.1 diff |
| P0-B | Benchmark: `extractAverageLuma()` vs `extractAverageRgb()` | ≥ 3× speedup |
| P1-A | Unit: all-motion buffer returns `ALL_MOTION` gate | PTT null, gate string set |
| P1-B | Integration: run on non-Pixel device | clockMismatch correctly detected |
| P2-A | Unit: resampling at 20fps produces 40Hz grid | Grid spacing = 25ms |
| P2-B | Unit: synthetic clock drift of 100ppm | Residual < 0.5ms after correction |
| P2-C | Integration: multiple gates fail simultaneously | All logged in bitmask |
| P3-A | Unit: dual SQI on AE-stepped signal | rawSqi low, bandSqi moderate |
| P3-B | Unit: XCorr finds 300ms lag | Correct with decayed confidence |

### Device Tests

```
1. Pixel 8 normal light    → PTT reported, pttCapable=true
2. Pixel 8 low light       → Fallback to HR-only (or extended AE settle)
3. Non-Pixel device         → ClockValidator runs, pttCapable set correctly
4. Walking/high-motion      → ALL_MOTION gate, no garbage processing
5. 60s recording            → Drift correction active, late-window PTT stable
```
