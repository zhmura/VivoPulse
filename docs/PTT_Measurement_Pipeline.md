# VivoPulse PTT Measurement Pipeline

## Technical Specification for Expert Review

**Version:** 1.0  
**Date:** 2026-04-07  
**Platform:** Android (Camera2/CameraX), tested on Google Pixel 9, Android 16  

---

## Table of Contents

1. [Overview](#1-overview)
2. [Signal Acquisition (Camera Layer)](#2-signal-acquisition)
3. [Exposure Stabilization (3A Locking)](#3-exposure-stabilization)
4. [Signal Preprocessing Pipeline](#4-signal-preprocessing-pipeline)
5. [Peak Detection](#5-peak-detection)
6. [Heart Rate Estimation](#6-heart-rate-estimation)
7. [PTT Estimation Methods](#7-ptt-estimation-methods)
8. [Multi-Method Kalman Fusion](#8-multi-method-kalman-fusion)
9. [Signal Quality Assessment (SQI)](#9-signal-quality-assessment)
10. [Combined Confidence Scoring](#10-combined-confidence-scoring)
11. [Pipeline Quality Gates](#11-pipeline-quality-gates)
12. [GoodSync Detection](#12-goodsync-detection)
13. [Output & Reporting](#13-output-and-reporting)
14. [Known Limitations](#14-known-limitations)

---

## 1. Overview

### What is PTT?

**Pulse Transit Time (PTT)** is the time delay between the arrival of a pulse wave at two different body sites. In VivoPulse, we measure PTT between:

- **Face** (proximal site) — blood arrives here first from the heart
- **Fingertip** (distal site) — blood arrives here later

The delay (typically 50–150 ms) correlates with arterial stiffness and blood pressure changes.

### System Architecture

```
┌─────────────────────────────────────────────────────┐
│                SIGNAL ACQUISITION                    │
│  Front camera (face) ──► Green-proxy extraction      │
│  Back camera (finger) ──► V-channel (Cr) extraction  │
│  Both: AE lock ──► Exposure stabilization            │
└─────────────────────┬───────────────────────────────┘
                      │ raw timestamped values
┌─────────────────────▼───────────────────────────────┐
│              SIGNAL PREPROCESSING                    │
│  Warm-up trim → Motion rejection → Timestamp sync →  │
│  Resampling (100Hz) → Detrend → Change-point repair →│
│  Butterworth bandpass (0.7–4.0 Hz) → Z-score norm    │
└─────────────────────┬───────────────────────────────┘
                      │ cleaned, aligned signals
┌─────────────────────▼───────────────────────────────┐
│              PTT ESTIMATION                          │
│  Method A: NCC Cross-Correlation                     │
│  Method B: ITM Foot-to-Foot                          │
│  Method C: GCC-PHAT Multi-Window                     │
│  Method D: Cross-Spectral Phase Delay (CSP)          │
│           ↓                                          │
│  Scalar Kalman Filter (fusion w/ outlier gating)     │
└─────────────────────┬───────────────────────────────┘
                      │ fused PTT ± CI
┌─────────────────────▼───────────────────────────────┐
│              QUALITY ASSESSMENT                      │
│  Per-channel SQI → Photometric SQI → Combined        │
│  confidence (logit-space) → Quality tier → Report    │
└─────────────────────────────────────────────────────┘
```

### Dual-Camera Concurrent Operation

Both cameras operate simultaneously via Android Camera2 ConcurrentCameraManager. Frames are timestamped using `Image.timestamp` (REALTIME clock domain on Pixel devices), providing hardware-level synchronization without manual drift correction.

---

## 2. Signal Acquisition

### 2.1 Face Channel (Front Camera)

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Camera | Front-facing | Non-contact rPPG |
| Color space | YUV 4:2:0 (YUV_420_888) | Standard Android camera format |
| Signal extraction | **Green-proxy** from YUV | Green light is maximally absorbed by hemoglobin (~540 nm), highest rPPG SNR |
| Formula | `G ≈ Y − 0.344·U − 0.714·V` | Avoids full RGB matrix; ~40% faster |
| ROI detection | TFLite face detector → forehead/cheek region | Tracks face landmarks per frame |
| Spatial subsampling | 2× (every other pixel) | Sufficient for spatial average; 4× speedup |
| Target FPS | 30 Hz | Pixel 9 concurrent mode |

**Algorithm: Green-Proxy Extraction**

For each pixel `(x, y)` in the face ROI:
```
Y = luminance plane[y * rowStride + x]          (unsigned byte, 0-255)
U = chroma-blue plane[(y/2) * uvRowStride + (x/2) * uvPixelStride] - 128
V = chroma-red  plane[(y/2) * uvRowStride + (x/2) * uvPixelStride] - 128

Green ≈ Y − 0.344136 · U − 0.714136 · V,  clamped to [0, 255]
```
The spatial average of `Green` over the ROI yields one sample per frame.

### 2.2 Finger Channel (Back Camera + Torch)

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Camera | Rear-facing | Transmissive PPG through fingertip |
| Illumination | LED torch (white) or external LED | Red component penetrates tissue |
| Signal extraction | **V-channel (Cr)** from YUV | Directly measures red chrominance; robust when Y-channel saturates |
| Fallback | Y-channel (luminance) if V unavailable | Original path; works when torch doesn't saturate |
| ROI detection | Automatic (`FingerRoiDetector`) | Re-evaluated every 30 frames |
| Spatial subsampling | 2× | Matches chroma 4:2:0 native resolution |
| Manual focus | AF_MODE_OFF + fixed lens position | Prevents AF hunting when finger covers lens |
| Target FPS | 30 Hz | Pixel 9 concurrent mode |

**Algorithm: V-channel (Cr) Extraction**

For each pixel `(x, y)` in the finger ROI:
```
V = chroma-red plane[(y/2) * uvRowStride + (x/2) * uvPixelStride]   (unsigned byte, 0-255)
```
The spatial average of raw V values over the ROI yields one sample per frame.

**Why V-channel instead of Y-channel:**

With bright illumination, the Y-channel (luminance) saturates to ~252/255. The blood-volume pulsation (~0.2% AC amplitude) is below the quantization noise floor of an 8-bit signal at full scale. The V-channel (red chrominance) captures the *change in redness* caused by hemoglobin absorption, providing a much higher signal-to-noise ratio for the PPG waveform.

### 2.3 Finger Coverage Detection

The system verifies the finger is properly covering the camera:

| Check | Condition | Threshold |
|-------|-----------|-----------|
| Finger present | Mean Y-luma > threshold AND ROI variance is low | `luma > 30`, `variance < 800` |
| Finger too bright | High clipping (pixels > 250) | `saturation% > 5%` → "Reduce pressure" |
| Finger too dark | High clipping (pixels < 5) | `lowClip% > 5%` → "Press firmly" |

### 2.4 Frame Timing

| Parameter | Value |
|-----------|-------|
| Clock domain | `REALTIME` (Image.timestamp) |
| Timestamp source | Hardware sensor clock |
| Concurrent streams | Both cameras share the same clock |
| FPS tracking | Rolling window, logged every 30 frames |
| Dropped frame detection | `totalFrames - deliveredFrames` |

---

## 3. Exposure Stabilization

### 3.1 Why AE Lock Matters

Auto-Exposure (AE) continuously adjusts camera gain and exposure time to maintain target brightness. These adjustments cause large, slow signal variations that are indistinguishable from PPG pulsation. AE must be locked before signal acquisition begins.

### 3.2 Stability-Based AE Lock Algorithm

Instead of waiting a fixed number of frames, the system monitors a rolling luma window and locks when the signal has stabilized:

**Constants:**

| Parameter | Value | Meaning |
|-----------|-------|---------|
| `AE_STABILITY_WINDOW` | 15 frames | ~500 ms rolling window @ 30fps |
| `AE_MIN_SAMPLES` | 10 frames | ~330 ms minimum before evaluating |
| `AE_LUMA_CHANGE_EPSILON` | 1.5 | Max mean shift between halves (0.6% of 0–255) |
| `AE_LUMA_VARIANCE_DELTA` | 5.0 | Max variance within window |
| `AE_MAX_WAIT_FRAMES` | 90 frames | 3-second hard timeout |

**Algorithm (per camera):**

```
1. Append current frame's luma to rolling window (max 15 samples)
2. If samples < 10: continue (not enough data)
3. Split window into first half and second half
4. Compute mean of each half
5. If |mean₁ - mean₂| < 1.5 AND variance(window) < 5.0:
     → AE has settled naturally → Lock (settled = true)
6. Else if totalFrames > 90:
     → Hard timeout → Lock anyway (settled = false, quality degraded)
7. On lock: set CONTROL_AE_LOCK = true on Camera2 repeating request
```

### 3.3 Post-Lock Drift Monitoring

After AE is locked, the hardware may still drift slightly (especially on some ISPs). The system monitors this:

**EV Drift Detection:**
```
EV = ln(exposureTime × ISO)

For each frame after lock:
  1. Append EV to rolling window (30 samples ≈ 1s)
  2. When window is full:
     a. Compare mean of first and second halves
     b. If |mean₁ - mean₂| / mean > 2% → flag drift
     c. Drifted flag blocks PTT output
```

| Parameter | Value | Meaning |
|-----------|-------|---------|
| `POST_LOCK_EV_WINDOW` | 30 frames | ~1 second post-lock monitoring |
| `POST_LOCK_DRIFT_THRESHOLD` | 0.02 (2%) | Relative EV drift limit |
| `EXPOSURE_STEP_THRESHOLD` | 0.03 (3%) | Frame-to-frame EV step → instant block |
| `CLIPPING_HARD_GATE_PCT` | 15% | If >15% pixels clipped → block PTT |

### 3.4 Output Gating

A frame contributes to PTT analysis only when ALL conditions are met:

```
outputReady(FACE)   = frontExposureLocked AND frontExposureSettled AND NOT frontAeDrifted
outputReady(FINGER) = backExposureLocked  AND backExposureSettled  AND NOT backAeDrifted AND NOT fingerExposureStepDetected
```

---

## 4. Signal Preprocessing Pipeline

### 4.1 Warm-Up Trim

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Trim duration | 2.0 seconds | First frames contain AE settling ramps and ISP startup artifacts |
| Safety skip | If recording < 5s | Don't trim short recordings |
| Per-stream | Independent trim | Face and finger may start at different times |

### 4.2 Motion Rejection (IMU-Based)

Frames during high-acceleration periods are excluded:

| Parameter | Value | Meaning |
|-----------|-------|---------|
| Threshold | 0.1 G RMS | Windowed IMU acceleration exceeding this value |
| Window | ±250 ms | Surrounding temporal context |
| If ALL exceed | Return `ALL_MOTION` gate | PTT unavailable for this session |

### 4.3 Timestamp Synchronization & Resampling

Both camera streams arrive at irregular intervals. The pipeline:

1. **Analyzes synchronization** between streams (rate ratio, jitter, drop rate, offset)
2. **Resamples** both streams to a unified timeline using linear interpolation

**Adaptive sample rate selection:**

| Condition | Effective rate | Foot detection |
|-----------|---------------|----------------|
| minFps ≥ 25 AND clean (jitter ≤ 5ms, drops ≤ 10%) | 100 Hz | Enabled |
| minFps ≥ 15 | 2 × minFps (30–50 Hz) | Disabled |
| minFps < 15 | max(20, 2 × minFps) Hz | Disabled |

"Clean" = both streams have jitter ≤ 5 ms AND drop rate ≤ 10%.

### 4.4 Detrending & Baseline Removal

1. **DC removal:** subtract mean
2. **IIR detrending:** high-pass IIR filter at 0.5 Hz → removes slow baseline drift
3. **Change-point repair:** detect and repair step-like baseline shifts (pressure slips, residual AE steps)
   - Detection: compute low-pass signal at 0.3 Hz, find discontinuities exceeding 2× local MAD
   - Repair: excise ±0.5s around each change-point, linearly interpolate across the gap

### 4.5 Bandpass Filtering

**Filter:** 4th-order Butterworth bandpass, applied forward and backward (`filtfilt`) for zero-phase distortion.

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Low cutoff | 0.7 Hz | Heart rate floor: 42 BPM |
| High cutoff | 4.0 Hz | Heart rate ceiling: 240 BPM + harmonics |
| Order | 4 (8th effective via filtfilt) | Steep rolloff without excessive ringing |
| Padding | 1.5 seconds (150 samples @ 100 Hz) | Prevents edge transients in filtfilt |

### 4.6 Optional Wavelet Denoising

Applied when channel SQI is in range [40, 80] (mid-quality — too noisy for raw processing, but enough signal to benefit from denoising):

- **Algorithm:** Wavelet thresholding (4-level decomposition)
- **Usage:** Produces a parallel denoised signal. PTT is computed on both main and denoised paths; if both produce results, they are compared for consistency.

### 4.7 Z-Score Normalization

Final step: `z = (x - μ) / σ` — ensures both channels have zero mean and unit variance, removing amplitude bias from cross-correlation.

---

## 5. Peak Detection

### Algorithm: MAD-Based Adaptive Thresholding + Prominence Filtering

Traditional peak detection uses `mean + k·std`, which is fragile: a single large artifact raises the threshold and hides real peaks. VivoPulse uses a two-phase robust approach.

**Phase 1: MAD-Based Thresholding**

```
1. Compute median of signal
2. Compute MAD = median(|xᵢ - median(x)|)
3. Threshold = median + 0.5 × 1.4826 × MAD
   (1.4826 converts MAD to std-equivalent for Gaussian data)
   (0.5 factor places threshold at ~69th percentile)
4. Find local maxima above threshold with minimum distance ≥ 350ms between peaks
```

**Phase 2: Prominence-Based Filtering**

```
1. For each candidate peak:
   Prominence = peak height − max(left trough, right trough)
   (troughs searched within ±1 RR-interval)
2. Compute median prominence across all candidates
3. Reject peaks with prominence < 30% of median prominence
```

This removes noise spikes that pass the threshold but lack the morphological signature of real heartbeats.

**Constants:**

| Parameter | Value | Meaning |
|-----------|-------|---------|
| `MIN_RR_MS` | 350 ms | Minimum R-R interval (≤ 171 BPM max physiological) |
| `MAX_RR_MS` | 2000 ms | Maximum R-R interval (≥ 30 BPM min physiological) |
| MAD factor | 0.5 × 1.4826 | Threshold sensitivity |
| Prominence threshold | 30% of median prominence | Noise rejection aggressiveness |
| Minimum peaks for validity | 3 | Fewer = insufficient data |

---

## 6. Heart Rate Estimation

Computed from inter-peak (R-R) intervals:

```
HR (bpm) = 60,000 / mean(RR intervals in ms)
```

**Variability metrics:**

| Metric | Formula | Interpretation |
|--------|---------|----------------|
| RR Mean | `mean(RR)` in ms | Average cardiac cycle duration |
| RR Std | `std(RR)` in ms | Heart rate variability |
| RR CV | `std(RR) / mean(RR)` | Normalized variability (coefficient of variation) |

**Plausibility range:** 40–180 BPM

**Inter-channel agreement:** HR from face and finger must agree within **±5 BPM**.

---

## 7. PTT Estimation Methods

Four independent methods estimate PTT, each with different strengths:

### 7.1 Method A: Normalized Cross-Correlation (NCC)

**Algorithm:** Pearson correlation coefficient computed at each lag τ:

```
R[τ] = Σ(xᵢ - μₓ)(yᵢ₊τ - μᵧ) / √(Σ(xᵢ - μₓ)² × Σ(yᵢ₊τ - μᵧ)²)
```

**Sub-sample refinement:** Quadratic (parabolic) interpolation around the peak:
```
Given 3 points (y₁, y₂, y₃) at indices (-1, 0, +1) relative to peak:
  a = (y₁ + y₃)/2 - y₂
  b = (y₃ - y₁)/2
  Refined offset = -b/(2a)       (vertex of fitted parabola)
```

| Parameter | Value | Meaning |
|-----------|-------|---------|
| Max lag | ±400 ms | Physiological PTT range |
| Window | Last 20 seconds of signal | Most recent data |
| Min samples | 100 | Minimum for valid correlation |
| Confidence decay | Beyond 200 ms, linear decay to 0.5 at 500 ms | Lags > 200 ms are less likely physiological |

**Peak sharpness** = peak correlation − mean(left neighbor, right neighbor). Higher sharpness = more confident timing.

**Measurement variance for Kalman:**
```
σ = 15 / corr       (corr clamped to [0.1, 1.0])
variance = σ²
```

### 7.2 Method B: Intersecting Tangent Method (ITM) Foot-to-Foot

**Purpose:** Detect pulse onset ("foot") in each beat, then compute per-beat PTT as the difference in onset times between channels.

**Algorithm (per beat):**

```
1. Compute Savitzky-Golay 1st derivative of signal
   (window: 150ms, polynomial fit for smooth derivative)

2. HR-adaptive search window:
   Search from (peak − 0.45×RR) to (peak − 0.05×RR)
   This adapts to heart rate — faster HR = shorter search window

3. Find local minimum (t_min) in search window
   = baseline level before upstroke

4. Find max slope point (t_ms) from t_min to near peak
   = steepest point on the upstroke

5. Intersecting Tangent formula:
   t_foot = t_ms + (signal(t_min) − signal(t_ms)) / slope(t_ms)
   
   This is the intersection of:
   - A horizontal line at the baseline level
   - The tangent line at the maximum slope point
```

**Beat matching between channels:**
- Match face foot to nearest finger foot within **±300 ms** tolerance
- PTT = finger foot time − face foot time
- Plausibility filter: **0 < PTT < 500 ms**
- Minimum 2 matched beats required

**Measurement variance for Kalman:**
```
IQR of per-beat PTT values → σ = IQR / 1.349 → variance = σ²
```

### 7.3 Method C: GCC-PHAT (Generalized Cross-Correlation — Phase Transform)

**Algorithm:** Unlike NCC which uses raw signal magnitude, GCC-PHAT whitens the cross-power spectrum to keep only phase information. This produces sharper, more robust timing peaks.

```
1. FFT both signals: X(f) = FFT(x), Y(f) = FFT(y)
2. Cross-power spectrum: G(f) = X*(f) · Y(f)
3. PHAT whitening: W(f) = G(f) / |G(f)|^β     (β = 0.8)
4. Inverse FFT: R_PHAT(τ) = IFFT(W)
5. Find peak in physiological range [30ms, 400ms]
6. Sub-sample refinement via quadratic interpolation
```

The β parameter controls whitening strength:
- β = 0 → standard cross-correlation (amplitude-preserving)
- β = 1 → full PHAT whitening (phase-only, sharpest peaks)
- β = 0.8 → default compromise (sharp peaks, some noise resilience)

**Multi-Window Stability Analysis:**

Runs GCC-PHAT on overlapping sub-windows (5s window, 50% overlap) to assess delay stability:

| Parameter | Value |
|-----------|-------|
| Sub-window | 5.0 seconds |
| Overlap | 50% |
| Lag range | 30–400 ms |

**Stability score:**
```
MAD = median absolute deviation of per-window lags
Stability = (1 − MAD/20ms), clamped to [0, 1]
  MAD < 5ms → stability = 1.0 (excellent)
  MAD > 20ms → stability = 0.0 (unstable, likely noise)
```

**Measurement variance for Kalman:**
```
σ = 1.4826 × MAD     (MAD floored at 3ms)
variance = σ²
```

### 7.4 Method D: Cross-Spectral Phase Delay (CSP)

**Algorithm:** Estimates delay τ from the linear relationship between cross-spectral phase and frequency: `φ_xy(f) ≈ −2πfτ`.

This is the most mathematically principled method but requires sufficient coherence at HR harmonics.

```
1. Welch cross-spectral estimation (256-sample segments, 50% overlap)
   → Produces: Sxy(f), coherence γ²(f), frequencies

2. Extract phase: φ(f) = atan2(Im(Sxy), Re(Sxy))

3. Select HR-harmonic bins:
   For k = 1, 2, ..., 4 (up to 4th harmonic):
     f_target = k × (HR_bpm / 60)
     Search bins within ±0.15 Hz of f_target
     Include bin if γ²(f) ≥ 0.10 (minimum coherence)

4. Phase unwrapping (sequential)

5. Coherence-weighted least squares:
   τ = −Σ(w·ω·φ) / Σ(w·ω²)
   where w = γ²(f), ω = 2πf

6. Standard error from weighted residuals:
   SE = √(weighted_residual_variance / Σ(w·ω²))
```

| Parameter | Value | Meaning |
|-----------|-------|---------|
| Segment length | 256 samples | Welch spectral estimation window |
| Max harmonics | 4 | Up to 4th HR harmonic included |
| γ² minimum | 0.10 | Minimum coherence for bin inclusion |
| Harmonic bandwidth | ±0.15 Hz | Search radius around each harmonic |
| Min bins | 3 | Minimum for meaningful regression |
| SE rejection | SE > 200 ms | Result discarded if uncertainty too high |

**Measurement variance for Kalman:**
```
variance = SE²
```

### 7.5 Adaptive GCC (Coherence-Weighted PHAT)

An advanced variant that replaces the fixed β with per-frequency coherence weighting:

```
β(f) = γ²(f)     (from Welch coherence estimation)

High coherence bins → full PHAT whitening → sharp peak
Low coherence bins  → less whitening → preserves SNR
```

This is theoretically optimal for maximizing peak-to-sidelobe ratio under stationary noise.

---

## 8. Multi-Method Kalman Fusion

### 8.1 Architecture

Instead of heuristic switching ("if methods disagree by > 50ms, use XCorr"), all methods feed into a **scalar Kalman filter** that produces a fused estimate with tracked uncertainty.

### 8.2 Kalman Filter Model

**State-space model:**
```
State: x = PTT (scalar, in ms)
Transition: x_k = x_{k-1} + w    (PTT is slowly varying)
              w ~ N(0, Q)         Q = 25 ms² (process noise ≈ 5ms/step)
Measurement: z = x + v            v ~ N(0, R) where R is method-specific
```

**Initial state:**
```
x₀ = NCC cross-correlation result (prior)
P₀ = 2500 ms² (initial uncertainty: σ = 50ms — wide)
```

### 8.3 Sequential Update with Outlier Gating

For each measurement m (with value z and variance R):

```
1. Innovation: ν = z - x_predict
2. Innovation variance: S = P_predict + R
3. Mahalanobis test: d² = ν²/S
   If d² > 9.0 (3σ gate):  REJECT measurement (outlier)
4. Kalman gain: K = P_predict / S
5. Update: x = x_predict + K·ν
           P = P_predict × (1 - K)
```

| Parameter | Value | Meaning |
|-----------|-------|---------|
| Process noise Q | 25 ms² | (5ms)² — expected PTT variation per step |
| Initial variance P₀ | 2500 ms² | (50ms)² — wide initial uncertainty |
| Outlier gate χ² | 9.0 | 3σ Mahalanobis threshold |

### 8.4 Fusion Output

```
Fused PTT = x (state estimate after all updates)
95% CI = ±1.96 × √P
Stable = P < 100 ms² (SE < 10ms)
```

### 8.5 Method Agreement

Maximum pairwise difference among all non-rejected method estimates:
```
MethodAgree = max(values) - min(values)
```

### 8.6 Beat Coverage

```
Expected beats = (signal duration in seconds) × (HR_bpm / 60)
Beat coverage = (detected matched beats) / (expected beats), clamped to [0, 1]
```

---

## 9. Signal Quality Assessment

### 9.1 Per-Channel SQI (Signal Quality Index)

Scored 0–100 from three components:

**Component 1: SNR (0–70 points)**

```
Band-power SNR:
  Signal power = mean(filtered²)
  Noise power  = mean((raw - filtered)²)
  SNR_dB = 10 × log₁₀(signal / noise)

Score mapping:
  SNR ≥ 15 dB → 70 points
  SNR ∈ [0, 15) → 10 + (SNR/15) × 60 points
  SNR < 0 dB  → 0 points
```

**Component 2: Peak Regularity (0–30 points)**

```
CV = std(RR intervals) / mean(RR intervals)
Quality = 100 × (1 - CV/0.4)
Score = Quality × 0.3

CV mapping:
  CV = 0.05 (5%)  → 95 → score ≈ 28.5 (excellent)
  CV = 0.10 (10%) → 80 → score = 24.0 (good)
  CV = 0.20 (20%) → 60 → score = 18.0 (fair)
  CV > 0.40 (40%) → 0  → score = 0 (poor)
```

**Component 3: Motion (0–15 points)**

```
Score = (motionPenalty / 100) × 15
  motionPenalty = 100  → 15 points (no motion)
  motionPenalty = 0    → 0 points (extreme motion)
```

**Total SQI = SNR + Regularity + Motion, clamped to [0, 100]**

### 9.2 Photometric SQI

Detects exposure artifacts in the raw signal (before bandpass filtering):

**Step detection:**
```
1. Compute frame-to-frame differences: Δ[i] = |raw[i+1] - raw[i]|
2. Compute MAD of differences
3. Step threshold = max(median(Δ) + 6 × MAD, median(Δ) × 3)
4. Count samples exceeding threshold → stepCount
5. Step penalty = min(stepPercent × 8, 40)
```

**Clipping detection:**
```
1. Find signal range: range = max - min
2. Clip margin = 1% of range
3. Count samples within 1% of min or max → clipCount
4. Clip penalty = min(clipPercent × 2, 30)
```

**Score = 100 − stepPenalty − clipPenalty, clamped to [0, 100]**

---

## 10. Combined Confidence Scoring

### 10.1 Logit-Space Weighted Sum

Instead of multiplying quality factors (where any single ~0 kills the result), VivoPulse uses a logistic regression-like model where each factor is a feature:

```
For each factor i:
  1. Map to membership: μᵢ ∈ (0.01, 0.99)
  2. Transform to logit: logit(μᵢ) = ln(μᵢ / (1 - μᵢ))
  3. Weight: wᵢ × logit(μᵢ)

Combined score = Σ(wᵢ × logit(μᵢ)) / Σ(wᵢ)
Confidence = σ(combined score) = 1/(1 + e^(-score))
```

### 10.2 Factors and Weights

| Factor | Weight | Membership mapping | Meaning |
|--------|--------|--------------------|---------|
| **SQI** | 2.0 | soft-min(face/100, finger/100) with λ=5 | Signal quality (most important) |
| **Correlation** | 1.5 | direct value ∈ [0, 1] | Cross-channel agreement |
| **Peak sharpness** | 0.5 | sigmoid((sharpness − 0.05) / 0.05) | Cross-correlation peak quality |
| **Delay stability** | 1.0 | direct value ∈ [0, 1] | Multi-window delay consistency |
| **Method agreement** | 1.0 | sigmoid((30 − agreeMs) / 15) | Inter-method consensus |
| **Coherence at HR** | 1.5 | sigmoid((γ² − 0.15) / 0.10) | Cross-spectral physiological content |

**Soft-min** prevents the worse channel from being ignored (as hard-min would) while allowing partial compensation:
```
softMin(a, b) = −(1/λ) × ln(e^(−λa) + e^(−λb))
As λ → ∞: approaches hard min
λ = 5: smooth approximation
```

### 10.3 Quality Tiers

| Tier | Confidence threshold | Behavior |
|------|---------------------|----------|
| **HIGH** | ≥ 0.75 | Report with high confidence |
| **MEDIUM** | ≥ 0.50 | Report with caveat |
| **LOW** | ≥ 0.30 | Report as experimental |
| **REJECTED** | < 0.30 | Do not report PTT (output = null) |

---

## 11. Pipeline Quality Gates

### 11.1 Soft Gates (Logit-Space)

The pipeline applies its own independent quality assessment before PTT is reported. Each gate contributes a continuous score:

| Gate | Weight | Membership formula | Meaning |
|------|--------|--------------------|---------|
| FPS | 2.0 | σ((minRate − 20) / 3) | Frame rate quality |
| Face SNR | 1.5 | σ((faceSnr − 3dB) / 2) | Face spectral SNR |
| Finger SNR | 1.5 | σ((fingerSnr − 6dB) / 2) | Finger spectral SNR |
| Jitter | 1.0 | σ((5ms − maxJitter) / 2) | Timestamp regularity |
| Drops | 1.0 | σ((10% − maxDropRate) / 3%) | Frame drop rate |
| Clock drift | 1.5 | σ((2000ppm − drift) / 500) | Inter-camera clock agreement |
| Offset stable | 1.0 | σ((threshold − offsetStd) / 2) | Inter-camera offset consistency |
| Motion | 1.0 | 0.15 if ALL_MOTION, else 0.98 | IMU motion gate |
| Offset valid | 1.0 | 0.98 if valid, else 0.20 | Offset measurement validity |

### 11.2 Critical Hard Gates

Only truly catastrophic conditions trigger hard rejection:

| Condition | Threshold | Effect |
|-----------|-----------|--------|
| FPS too low | minFps < 15 Hz | PTT = null, critical rejection |
| Clock drift too high | drift > 5000 ppm | PTT = null, critical rejection |

**Spectral SNR thresholds for reference:**

| Channel | Gate threshold | Rationale |
|---------|---------------|-----------|
| Face | 3 dB | Weaker signal (ambient light rPPG) |
| Finger | 6 dB | Stronger signal expected (torch illumination) |

---

## 12. GoodSync Detection

GoodSync identifies time segments where both channels simultaneously have high-quality, well-correlated signals.

### 12.1 Per-Window Criteria

| Criterion | Threshold | Meaning |
|-----------|-----------|---------|
| Face SQI | ≥ 75 | Acceptable face signal quality |
| Finger SQI | ≥ 75 | Acceptable finger signal quality |
| Correlation | ≥ 0.70 | Strong cross-channel agreement |
| HR agreement | ≤ 5 BPM | Same heartbeat detected in both |
| FWHM | ≤ 120 ms | Sharp correlation peak |
| IMU RMS | ≤ 0.05 G | Low device motion |

### 12.2 Session-Level Segmentation

- Sliding window: 8-second windows, 1-second step
- Windows passing all criteria are merged if gap ≤ 1.5 seconds
- Merged segments form continuous GoodSync regions
- **GoodSync Share** = total GoodSync duration / session duration × 100%

---

## 13. Output and Reporting

### 13.1 PTT Result

| Field | Type | Description |
|-------|------|-------------|
| `pttMs` | Double? | Fused PTT in milliseconds (null if REJECTED) |
| `confidence` | Double | Combined confidence (0–1) |
| `qualityTier` | Enum | HIGH / MEDIUM / LOW / REJECTED |
| `corrScore` | Double | NCC correlation coefficient |
| `hrFaceBpm` | Double | Heart rate from face channel |
| `hrFingerBpm` | Double | Heart rate from finger channel |
| `sqiFace` | Int | Face channel SQI (0–100) |
| `sqiFinger` | Int | Finger channel SQI (0–100) |
| `peakSharpness` | Double | Cross-correlation peak sharpness |
| `nBeats` | Int | Number of valid foot-to-foot beats |
| `kalmanCiMs` | Double | 95% CI half-width (ms) from Kalman fusion |
| `meanCoherenceAtHr` | Double | Mean coherence γ² at HR harmonic bins |
| `beatCoverage` | Double | Valid beats / expected beats (0–1) |

### 13.2 Plausibility Validation

| Check | Range | Action if violated |
|-------|-------|--------------------|
| PTT range | 50–150 ms (typical) | Warning flag on results screen |
| PTT stability | < 25 ms (variability) | Warning: "PTT stability > 25ms" |
| HR plausibility | 40–180 BPM | Reject channel |
| HR inter-channel agreement | ≤ 5 BPM | Lower confidence |

### 13.3 Session Summary Log

Every session produces a structured summary containing all diagnostic metrics:
```
SESSION_SUMMARY | fpsFace | fpsFinger | dropsFace | dropsFinger |
  jitterFaceMs | jitterFingerMs | sharedClockLikely |
  rateRatio (ppm) | offsetMeanMs | offsetStdMs |
  bandSqiFace | bandSqiFinger | faceSnrDb | fingerSnrDb |
  confidence | pipelineQuality | qualityTier |
  coherence | kalmanCIMs | beatCoverage |
  lagMedianMs | nBeats | weakGates | pttMs
```

---

## 14. Known Limitations

### 14.1 Hardware Dependencies

- Requires concurrent camera support (Android 11+, device-specific)
- Pixel 9 torch position is far from the rear camera — external LED may be needed for adequate finger illumination
- V-channel resolution is 2× lower than Y-channel (YUV 4:2:0 chroma subsampling) — acceptable for spatial averaging but limits fine spatial features

### 14.2 Signal Quality

- Face rPPG is sensitive to ambient lighting, skin pigmentation, and head motion
- Finger PPG requires consistent pressure — too much compresses capillaries (low signal), too little loses contact
- AE lock is a single-shot calibration; sustained light changes during recording cannot be compensated

### 14.3 Algorithmic Limitations

- ITM foot detection requires ≥ 100 Hz effective sample rate (disabled at lower rates)
- CSP requires sufficient coherence at HR harmonics; low coherence → method produces no estimate
- Kalman fusion resets per session (no cross-session learning)
- Cross-correlation has ±1/fs timing resolution before sub-sample interpolation (±33.3ms at 30Hz native)
- GoodSync thresholds are empirically tuned, not validated against a gold-standard reference

### 14.4 Clinical Validity

- PTT measured via smartphone camera has NOT been clinically validated against tonometry or cuff-based PTT
- The system measures *relative* trends, not absolute blood pressure
- Individual calibration (height, age, artery characteristics) is not performed
- The plausibility range (50–150 ms) is based on literature values for peripheral PTT, not device-specific validation

---

*This document reflects the implementation as of 2026-04-07. All thresholds and algorithm parameters are extracted directly from the source code.*
