# VivoPulse: Camera-Based Pulse Transit Time Measurement

## Interview Pitch — Tailored for Shen.ai

---

## Context: Why This Project Matters for Shen.ai

Shen.ai builds an SDK for **contactless vital signs measurement via rPPG**, including CE-marked blood pressure estimation from standard RGB cameras. VivoPulse is an independent R&D project that solves the same fundamental problem — extracting **Pulse Transit Time (PTT)** from camera-captured photoplethysmography — and encounters the same engineering challenges that are core to Shen.ai's product:

| Shen.ai's Challenge | VivoPulse's Direct Experience |
|---------------------|-------------------------------|
| rPPG signal extraction from face | Green-proxy extraction from YUV with face ROI tracking |
| Blood pressure estimation | PTT → arterial stiffness → BP proxy (same physiological chain) |
| On-device processing | Full pipeline runs on Pixel 9, no cloud, ~400ms latency |
| Diverse lighting/skin tones | Debugged real failures under variable lighting (SNR collapse) |
| Medical-grade accuracy | Multi-method fusion with statistical confidence scoring |
| SDK architecture | Modular Kotlin (core-signal / feature-capture / feature-processing) |

---

## The Problem Being Solved

**Blood pressure** is the most important cardiovascular vital sign, yet measuring it requires an inflatable cuff — uncomfortable, intermittent, and impossible for continuous monitoring.

**Pulse Transit Time (PTT)** — the time a pressure wave takes to travel from the heart to the periphery — correlates inversely with arterial stiffness and systolic blood pressure. PTT is the physiological signal that enables **cuffless blood pressure estimation** — the same principle underlying Shen.ai's CE-marked BP measurement.

**VivoPulse's approach:** Use a smartphone's two cameras simultaneously:
- **Front camera** → rPPG signal from the face (proximal pulse arrival)
- **Rear camera + torch** → transmissive PPG from the fingertip (distal pulse arrival)
- **Time delay** between the two = PTT (typically **50–150 ms**)

The core challenge: resolving sub-frame-interval timing differences from a 30 fps camera (~33 ms between frames) with sufficient precision for physiological meaning.

---

## The Mathematical Pipeline (In Execution Sequence)

### Stage 1 — Signal Acquisition: Optics → Numbers

**Face (rPPG):** Extract green-proxy from YUV frames:

```
G ≈ Y − 0.344·U − 0.714·V
```

Green light (~540 nm) has highest hemoglobin absorption contrast. Spatial average over face ROI (TFLite face detector) yields one sample per frame. The pulsation amplitude is ~0.2% of mean intensity.

**Finger (transmissive PPG):** Extract **V-channel (Cr)** from YUV, not Y-channel.

> **Key insight (discovered via debugging):** Under torch illumination, the Y-channel saturates to ~252/255. The 0.2% pulsation is below 8-bit quantization noise at full scale. The V-channel (red chrominance) captures hemoglobin absorption *changes* and stays within dynamic range.

**Concurrent cameras:** Both use Android ConcurrentCameraManager with hardware REALTIME timestamps — no software drift correction needed.

---

### Stage 2 — Exposure Stabilization: Eliminating Camera Artifacts

Auto-Exposure adjustments produce intensity shifts 1000× larger than the PPG signal. We implement **stability-based AE lock:**

1. Rolling 15-frame luma window → split halves → compare means
2. Lock when `|mean₁ − mean₂| < 1.5` AND `variance < 5.0`
3. Post-lock EV drift monitoring: `EV = ln(exposure × ISO)` → flag 2% drift

This is directly relevant to Shen.ai's SDK — any rPPG system must solve the AE problem, and the approach (stability detection vs. fixed timeout) determines signal quality in the first seconds of capture.

---

### Stage 3 — Signal Preprocessing: Raw → Clean (6-step pipeline)

| Step | Method | Why This Method |
|------|--------|----------------|
| Warm-up trim | Discard first 2s | AE settling ramps, ISP startup |
| Motion rejection | IMU-gated, ±250ms, 0.1G | Corrupts PPG morphology |
| Resampling | Linear interpolation → 100 Hz | Irregular frame arrival → uniform grid |
| Detrending | DC removal + IIR high-pass @ 0.5 Hz | Slow baseline drift |
| Change-point repair | Low-pass @ 0.3 Hz + MAD thresholding → excise ±0.5s | AE steps, pressure slips |
| Bandpass filter | **4th-order Butterworth, 0.7–4.0 Hz, zero-phase** | Cardiac band isolation |

**Why Butterworth?** Maximally flat passband — no ripple to distort pulse morphology. Zero-phase (`filtfilt`) eliminates group delay, which is critical when measuring *inter-channel timing*.

**Why not FIR/FFT filtering?** Gibbs ringing at window edges contaminates pulse foot timing in short (20–40s) recordings. IIR with mirror-padded edges is more robust.

---

### Stage 4 — Peak Detection: MAD-Based Adaptive Thresholding

```
threshold = median + 0.5 × 1.4826 × MAD
```

**Why MAD instead of mean ± k·σ?** MAD has 50% breakdown point — half the data can be outliers before the threshold is affected. Standard deviation has ~0% breakdown point (one large artifact raises σ, hiding real peaks). Factor 1.4826 converts MAD to Gaussian-equivalent σ.

Followed by **prominence filtering** (≥30% median prominence) to reject noise spikes with correct amplitude but wrong morphology.

---

### Stage 5 — PTT Estimation: 4 Independent Methods

This is the mathematical core. Four algorithms exploit different signal properties:

#### A. Normalized Cross-Correlation (NCC)
```
R[τ] = Σ(xᵢ − μₓ)(yᵢ₊τ − μᵧ) / √(Σ(xᵢ−μₓ)² · Σ(yᵢ₊τ−μᵧ)²)
```
+ Sub-sample parabolic interpolation for sub-frame precision
+ **Strength:** Global, robust to waveform shape differences
+ **Weakness:** Broad peak under noise → poor timing precision

#### B. Intersecting Tangent Method (ITM) — Foot-to-Foot
1. Savitzky-Golay 1st derivative (smooth polynomial fit)
2. Find max-slope point on upstroke
3. Intersect tangent at max slope with baseline → foot time
4. Match face/finger feet within ±300ms → per-beat PTT

+ **Strength:** Per-beat statistics (median, IQR), physiologically interpretable
+ **Weakness:** Requires ≥100 Hz effective sample rate

#### C. GCC-PHAT (Generalized Cross-Correlation — Phase Transform)
1. Cross-power spectrum: `G(f) = X*(f)·Y(f)`
2. PHAT whitening: `W(f) = G(f) / |G(f)|^0.8`
3. `R(τ) = IFFT(W)` → find peak in physiological range

+ Multi-window stability analysis (5s windows, 50% overlap, MAD of per-window lags)
+ **Strength:** Sharper timing peaks than NCC (amplitude-agnostic, phase-only)

#### D. Cross-Spectral Phase Delay (CSP)
```
φ_xy(f) ≈ −2πfτ  →  coherence-weighted least squares for τ
```
+ Only uses frequency bins at HR harmonics where coherence γ² ≥ 0.10
+ **Strength:** Theoretically principled; naturally rejects noise-dominated frequencies
+ **Weakness:** Requires sufficient coherence — often fails in real conditions

---

### Stage 6 — Kalman Fusion: Combining Estimates with Tracked Uncertainty

**Why not simple averaging?** Each method has radically different noise profiles:
- NCC at low correlation: σ ≈ 150 ms
- GCC-PHAT with stable windows: σ ≈ 4.5 ms

A **scalar Kalman filter** automatically weights by inverse variance:

```
State: PTT (random walk, Q = 25 ms²)
For each method's estimate z with variance R:
  Innovation: ν = z − x_predict
  Mahalanobis gate: d² = ν²/(P+R) > 9.0 → REJECT as outlier
  Kalman update: x = x + K·ν, K = P/(P+R)
```

**Output:** Fused PTT ± 95% CI

This is equivalent to running a **1D state estimation** problem — the same mathematical framework used for sensor fusion in navigation, adapted for physiological transit time.

---

### Stage 7 — Confidence Scoring: Logit-Space Quality Assessment

**The key design insight:** In a multiplicative model (`conf = f₁ × f₂ × ... × fₙ`), any single factor near zero collapses everything. Instead, we use **logit-space weighted sum** (equivalent to logistic regression):

```
confidence = σ( Σ wᵢ · logit(μᵢ) / Σ wᵢ )
```

| Factor | Weight | What It Captures |
|--------|--------|-----------------|
| Band-limited SQI | 2.0 | Per-channel processed signal quality |
| NCC correlation | 1.5 | Cross-channel waveform agreement |
| Peak sharpness | 0.5 | Timing peak precision |
| Delay stability | 1.0 | Multi-window consistency |
| Method agreement | 1.0 | Inter-method consensus |
| Coherence at HR | 1.5 | Shared physiological spectral content |

Floor clamp at ε=0.10 (logit = −2.20) prevents correlated bad factors from cascading into total rejection.

**Quality tiers:** HIGH (≥0.75), MEDIUM (≥0.50), LOW (≥0.20), REJECTED (<0.20)

---

## Real Engineering Bugs and Debugging Stories

These are the most interview-relevant parts — they demonstrate depth of understanding:

### Bug 1: Y-Channel Saturation
| | |
|-|-|
| **Symptom** | Finger PPG waveform was flat line at ~252 |
| **Root cause** | Torch illumination saturated 8-bit Y-channel; 0.2% pulsation below quantization noise |
| **Fix** | Switch to V-channel (Cr) — measures *change in redness* from hemoglobin, not total brightness |
| **Takeaway** | In rPPG, the color space channel you choose determines whether you see signal or noise |

### Bug 2: Raw vs. Band-Limited SQI
| | |
|-|-|
| **Symptom** | Confidence always < 0.05 despite visually good waveforms |
| **Root cause** | SQI used `noise = raw − filtered`. Raw has DC offset + drift that filtering correctly removes → noise appeared enormous → SNR = −47 dB |
| **Fix** | Use band-limited SQI (filtered signal vs filtered signal) for confidence |
| **Takeaway** | Quality metrics must measure the *processed* signal, not the raw input — the pipeline exists to clean it |

### Bug 3: Unbounded Cross-Correlation Window
| | |
|-|-|
| **Symptom** | XCorr reported PTT = 4,116 ms (4 seconds!) |
| **Root cause** | `maxLag = n/2` → 22.7s signal → ±11s search window → found noise harmonic at 4.1s |
| **Fix** | Constrain to ±500ms (physiological range) |
| **Takeaway** | Signal processing search windows must be bounded by physical constraints |

### Bug 4: Kalman Prior Poisoning
| | |
|-|-|
| **Symptom** | GCC-PHAT found plausible 79ms PTT but Kalman rejected it as "outlier" |
| **Root cause** | Kalman was initialized with the 4116ms XCorr bug value. Innovation = −4037ms → Mahalanobis d² = 272 >> gate of 9.0 |
| **Fix** | Neutral 100ms prior with wide initial variance — let data converge |
| **Takeaway** | Never use one algorithm's output to bias the fusion prior |

### Bug 5: Logit Cascading Collapse
| | |
|-|-|
| **Symptom** | SQI = 96, Fusion PTT = 63ms (both excellent), but confidence = 0.23 → REJECTED |
| **Root cause** | Stability and method-agreement are correlated (both measure XCorr quality). At ε=0.01, both hit logit = −4.60 → double-counted penalty |
| **Fix** | Raise floor to ε=0.10 (logit = −2.20) — still penalizing, but prevents cascading |
| **Takeaway** | Correlated features in logit-space scoring create multiplicative-like failure modes |

---

## Current Open Problems & Solution Ideas

### 1. Face Signal Under Variable Lighting
- Face spectral SNR drops to −0.0 dB in poor lighting → face HR estimate diverges
- **Ideas:** CHROM algorithm (chrominance-based rPPG, more illumination-robust than green-proxy); adaptive frame exclusion by per-ROI brightness; 1D U-Net denoising using finger PPG as ground truth

### 2. Peak Over-Counting on Face Channel
- Face detects 36 peaks vs finger's 23 in same window (100 vs 76 BPM — same heart!)
- **Ideas:** FFT-based spectral HR estimation instead of time-domain peak counting; cross-channel HR constraint (use finger HR as search prior for face)

### 3. CSP Method Consistently Fails
- Insufficient coherence at HR harmonics in real recordings
- **Ideas:** Multitaper spectral estimation (better resolution than Welch for short signals); lower coherence threshold; accept CSP as HIGH-quality-only method

### 4. No Cross-Session Learning
- Kalman resets every session
- **Idea:** Per-user PTT prior from last N sessions, with wide enough variance to accept genuine changes (caffeine, exercise)

### 5. Clinical Validation Gap
- PTT values (60–80ms) are physiologically plausible but not validated against arterial tonometry
- This is exactly Shen.ai's domain expertise with CE-marking and clinical studies

---

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin (Android) |
| Camera | Camera2 + CameraX (Camera2Interop for manual AE/AF control) |
| DSP | Custom library — Butterworth, Savitzky-Golay, FFT (JTransforms) |
| Face detection | TensorFlow Lite (MediaPipe) |
| UI | Jetpack Compose + real-time quality indicators (live SNR, HR, traffic-light badges) |
| Architecture | MVVM + Hilt DI |
| Modules | `core-signal` · `feature-capture` · `feature-processing` · `feature-results` |

---

## Key Technical Decisions

| Decision | Alternatives | Rationale |
|----------|-------------|-----------|
| V-channel (Cr) for finger | Y-channel, full RGB | Avoids saturation; no full color matrix |
| Zero-phase Butterworth | Chebyshev, FIR, wavelet | Flat passband preserves morphology; zero delay for timing |
| MAD peak detection | mean + k·σ, CWT | 50% breakdown point; simpler than continuous wavelet |
| 4-method Kalman fusion | Single best, majority vote | Different methods exploit different signal properties; Kalman tracks uncertainty |
| Logit-space confidence | Multiplicative, decision tree | No single-factor veto; equivalent to logistic regression |
| On-device only | Cloud processing | Privacy, latency, aligns with Shen.ai's architecture |

---

## How This Maps to Shen.ai's Work

| VivoPulse Experience | Shen.ai Relevance |
|---------------------|--------------------|
| rPPG signal extraction from face camera | Core of Shen.ai's SDK |
| PTT → blood pressure correlation | Shen.ai's CE-marked BP measurement |
| Adaptive exposure management | Essential for SDK robustness across devices |
| Multi-method fusion with Kalman | Medical-grade accuracy requires ensemble methods |
| Real-time quality indicators | SDK must communicate measurement reliability to integrators |
| Variable lighting / skin tone challenges | Shen.ai's 500K+ diverse training dataset addresses this |
| Modular SDK architecture | Shen.ai ships Android/iOS/Web SDKs |
| On-device processing, privacy-first | Shen.ai's core differentiator |

---

*VivoPulse is an active R&D project. All numbers from real testing on Google Pixel 9, Android 16.*
