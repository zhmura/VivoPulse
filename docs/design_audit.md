# PTT Pipeline — Design Audit & Risk Register

> Comprehensive risk analysis of the VivoPulse PTT pipeline.
> Each risk is scored by severity, annotated with current status, and has a proposed mitigation.

---

## Risk Classification

| Label | Meaning |
|---|---|
| 🔴 **Critical** | Can produce silently wrong PTT |
| 🟠 **High** | Causes PTT unavailability or significant degradation |
| 🟡 **Medium** | Reduces accuracy or robustness in edge cases |
| 🟢 **Low** | Performance or maintainability concern |

---

## 1. 🔴 SENSOR_TIMESTAMP Clock Domain Assumption

**Current code assumes** `Image.getTimestamp()` is in a shared monotonic clock across concurrent cameras.

| Attribute | Detail |
|---|---|
| **Risk** | Confident but **wrong PTT** on non-Pixel devices if clocks differ |
| **Current status** | Verified on Pixel 8 only. No runtime validation. |
| **Impact** | Offsets look "valid" but are systematically biased → PTT shifts by the inter-clock drift |

**Proposed mitigation**:
```
Runtime clock validation:
1. For both streams, compare Image.timestamp vs CaptureResult.SENSOR_TIMESTAMP
2. Compute offset distribution between the two clocks
3. If offset variance > threshold → force pttCapable=false (HR-only mode)
4. Log clock domain mismatch as CLOCK_MISMATCH gate
```

---

## 2. 🔴 Resampling False Precision at Low FPS

**Current code** resamples to 100Hz unconditionally. At 18fps, ~82% of samples are synthetic.

| Attribute | Detail |
|---|---|
| **Risk** | Derivative-based foot detection amplifies interpolation artifacts → **biased PTT** |
| **Current status** | LOW_FPS gate blocks PTT at <25fps, but no conditional resampling |
| **Impact** | At 25fps (gate boundary), 75% synthetic samples still affect foot timing |

**Proposed mitigation**:
```
Conditional resampling strategy:
- If minFPS ≥ 25 AND jitter low AND drops low → resample to 100Hz (full PTT)
- If minFPS ∈ [15, 25) → resample to min(50Hz, 2×minFPS) (XCorr only, no foot detection)
- If minFPS < 15 → no PTT (HR-only)
```

---

## 3. 🟠 AE/AWB Lock Based on Frame Count (Brittle)

**Current code** locks after 30 frames (~1s), regardless of whether AE has actually converged.

| Attribute | Detail |
|---|---|
| **Risk** | In low light or with torch ramp, AE takes longer → lock captures unstable state → staircase persists |
| **Current status** | Fixed 30-frame threshold in [DualCameraController.kt:676](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/DualCameraController.kt#L676) |
| **Impact** | Raw SNR ≈ 0 → SQI collapse → PTT rejected (observed in `logs12.txt`) |

**Proposed mitigation**:
```
Stability-based lock trigger:
1. Compute rolling mean/variance of luma over last 500ms
2. Lock when: abs(mean_change) < ε AND variance < δ for 300–500ms
3. Max wait: 3s (timeout fallback to current count-based lock)
4. For finger: also lock AF or set fixed focus (finger covers lens; AF hunting → brightness shifts)
```

---

## 4. 🟠 Full RGB Conversion Every Frame

**Current code** converts YUV_420_888 → RGB and computes avg R/G/B per frame.

| Attribute | Detail |
|---|---|
| **Risk** | CPU-heavy → self-inflicted FPS drops/jitter → kills PTT |
| **Current status** | Full conversion in [DualCameraController.processFrame](file:///home/ext.siarhei.zhmura/Work/pulse/feature-capture/src/main/java/com/vivopulse/feature/capture/DualCameraController.kt) |
| **Impact** | GC pressure + thermal throttling → variable FPS → HIGH_JITTER gate |

**Proposed mitigation**:
```
Lightweight signal extraction:
- Finger: use Y (luma) directly — no RGB needed (red channel dominates with torch)
- Face: compute "green proxy" from Y + Cb/Cr without full RGB matrix
- Reuse fixed-size buffers (no per-frame allocation)
- Move extraction to background thread with backpressure
```

---

## 5. 🟠 Motion Rejection Fail-Safe Is Harmful

**Current code**: if ALL frames exceed 0.1G threshold → keep ALL frames (fail-safe).

| Attribute | Detail |
|---|---|
| **Risk** | Guarantees noisy buffer downstream; XCorr can "find" spurious lag in noise |
| **Current status** | Fail-safe in [SignalPipeline.applyMotionRejection](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/SignalPipeline.kt#L44-L74) |
| **Impact** | Expensive processing of garbage + misleading consensus (observed: PTT=-4124ms) |

**Proposed mitigation**:
```
Replace fail-safe:
- If no stable window ≥ 5s → return "PTT unavailable: insufficient stable data"
- Allow HR-only if finger channel has ≥3s stable window
- Don't process signals that are known to be all-motion
```

**Also**: 50ms "nearest IMU sample" proximity is coarse. Better: continuous motion scoring per sliding window (e.g., 200ms RMS), not point-proximity.

---

## 6. 🟡 No Timestamp Drift Correction

**Current code** computes offset but does not correct for drift over time.

| Attribute | Detail |
|---|---|
| **Risk** | Over 60s, rate mismatch causes gradual misalignment → PTT degrades in late windows |
| **Current status** | Offset is one-shot median; no linear model |
| **Impact** | XCorr 20s window may straddle drifted region → biased lag estimate |

**Proposed mitigation**:
```
After robust offset, fit linear model:
  t2 ≈ a × t1 + b
Where:
  b = offset (already computed)
  a = relative rate ratio (should be ~1.0)
Use 'a' to compensate mild drift during resampling.
Low effort, high value for recordings > 30s.
```

---

## 7. 🟡 DSP Ordering & Conditioning Concerns

### 7a. Wavelet Denoising Condition
Applied only when SQI ∈ [40, 80]. But SQI is computed on **raw** signal — "moderate SQI" may still be dominated by AE steps. Wavelets may shift foot timing unpredictably.

**Proposed mitigation**: Unit test that wavelet denoising does not shift foot positions by > 2ms on synthetic signals. If it does, remove from PTT path.

### 7b. Hardcoded Bandpass 0.7–4.0 Hz
Low cutoff at 0.7Hz cuts real content for bradycardia (<42bpm) or paced breathing.

**Proposed mitigation**: Adaptive low cutoff: `max(0.5, 0.5 × HR_estimate)` after preliminary HR pass. Or relax to 0.5Hz for recordings > 30s with stable signal.

---

## 8. 🟡 Peak & Foot Detection Fragility

### 8a. Peak Threshold: `mean + 0.3 × std`
Fragile under drift and amplitude distribution changes. May miss peaks or detect false ones.

**Proposed mitigation**: Use MAD-based dynamic threshold or slope-based detector. Enforce prominence constraint (not just amplitude).

### 8b. Foot-to-Foot on Interpolated Data
Derivative amplifies interpolation artifacts. "Search backward up to 500ms for local minimum" can find arbitrary minima in noisy data.

**Proposed mitigation**:
```
Only run foot-to-foot when ALL conditions met:
  - minFPS ≥ 25
  - drops/jitter low
  - SQI > 60
  - exposure stable (no drift detected)
Otherwise: XCorr-only mode
```

---

## 9. 🟡 Consensus Lag Window Mismatch

XCorr scans ±200ms but foot matching allows [30, 500]ms — different physiological assumptions.

| Attribute | Detail |
|---|---|
| **Risk** | "Agreement" comparison is misleading when methods search different windows |
| **Current status** | Unaddressed |
| **Impact** | May choose XCorr when true PTT is >200ms, or vice versa |

**Proposed mitigation**:
- Align both methods to same plausible window (e.g., [30, 400]ms)
- Or extend XCorr to ±500ms but penalize large lags with confidence decay

---

## 10. 🟡 Quality Gating Pitfalls

### 10a. Hierarchical Gate Reports Only First Failure
Fixing FPS may reveal hidden jitter/drops/SQI issues.

**Proposed mitigation**: Log all failing gates as a bitmask:
```kotlin
val failedGates = mutableListOf<String>()
if (!offsetValid) failedGates += "INVALID_OFFSET"
if (minFps < 25) failedGates += "LOW_FPS"
if (maxJitter > 5) failedGates += "HIGH_JITTER"
if (maxDrops > 0.1) failedGates += "HIGH_DROPS"
// Report primary + log all
```

### 10b. Single Raw-SQI May Over-Gate
Raw SQI rejects sessions where filtered signal looks usable (by design). But this means "capture problem" and "physiology problem" are indistinguishable.

**Proposed mitigation**: Compute dual SQI:
- **Raw SQI**: detects exposure drift / clipping (capture quality)
- **Band-limited SQI**: detects pulse presence (signal quality)
- Require both above thresholds; log which fails for diagnostics

---

## 11. 🟢 Memory & Performance Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Concurrent streams + full RGB + resampling + filtfilt + wavelets + xcorr | Frame drops, GC thrashing | Stream processing in fixed-size ring buffers |
| Per-frame RGB array allocation | OOM observed in earlier sessions | Reuse buffers; store only scalar features per frame |
| Thermal throttling → FPS drift | AE extends → SNR drops → PTT rejected | Monitor thermal state; warn user; reduce resolution proactively |

---

## Priority Matrix

| Priority | Risk | Action | ROI |
|---|---|---|---|
| **P0** | #3 AE lock stability | Stability-based trigger | Highest — directly fixes SNR=0 root cause |
| **P0** | #4 RGB conversion overhead | Lightweight extraction | Removes self-inflicted FPS/jitter |
| **P1** | #5 Motion fail-safe | Early termination | Stops expensive garbage processing |
| **P1** | #1 Clock domain validation | Runtime check | Prevents silent wrong PTT on new devices |
| **P2** | #2 Conditional resampling | Adaptive grid | Reduces foot detection false precision |
| **P2** | #6 Drift correction | Linear fit | Improves long-recording accuracy |
| **P2** | #10a Gate bitmask logging | Multi-gate log | Better debuggability |
| **P3** | #7–9 DSP/detection/consensus | Various | Incremental accuracy improvements |
