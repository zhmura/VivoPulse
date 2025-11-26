# Functional Specification (MVP)

## 0. Scope & Goal

Build an Android app that:

- Concurrently records front (face) and back (finger with torch) video streams on Camera2/CameraX devices that support it (graceful fallback otherwise).
- Extracts rPPG (face) and PPG (finger) signals on-device, cleans them, computes PTT (pulse transit time) with sub-frame interpolation, and displays synchronized waveforms.
- Operates robustly under motion by detecting GoodSync windows (both channels simultaneously high quality) using SQI, cross-metrics, and optional IMU gating.
- Provides a near-real-time quality indicator (traffic light + tip) during capture for each channel.
- Exports clinician-grade artifacts (CSV/JSON + plots), stays privacy-first (on-device), and avoids clinical claims (no BP/mmHg, no cfPWV/AIx).

## 1. Glossary (Key Signals & Metrics)

- **PTT**: lag (ms) between proximal (face) and distal (finger) optical pulse signals.
- **GoodSync window**: time segment where both channels are high quality and mutually coherent (correlation, HR agreement, FWHM, optional coherence, IMU ok).
- **SQI_face / SQI_finger (0–100)**: per-channel quality derived from SNR with motion/saturation penalties.
- **Confidence (0–1)**: combined trust of PTT (minimum channel SQI × correlation × peak sharpness normalization).
- **Near-real-time indicator**: per-channel light (red/yellow/green) + tip using rolling window metrics.

## 2. User Flows

### Capture

1. Open app → device capability probe → show “Concurrent OK / Safe Mode”.
2. Show live previews (face & finger), torch toggle, near-real-time indicators (2–4 Hz).
3. Start → record 30–60 s; Stop → offline processing.

### Processing

1. Resample to unified 100 Hz, detrend, band-pass (0.7–4.0 Hz), z-norm.
2. Compute SQIs, cross-metrics; detect GoodSync windows.
3. Compute PTT (XCorr + sub-frame + Foot-to-Foot consensus).

### Results

- Graphs (raw/filtered), peak markers, PTT (ms) ± SD, Corr, Confidence, GoodSync%, per-segment chips.
- Biomarker panel (HR, basic HRV if quality allows).
- Educational note (non-diagnostic, no BP/cfPWV/AIx).

### Export

- One-tap encrypted ZIP: `session.json` (rich metadata), `face_signal.csv`, `finger_signal.csv`, `plots/*.png`.

### Errors/Fallbacks

- Camera errors → safe stop + Retry.
- No concurrent support → lower resolution / YUV-only → sequential mode.
- Thermal/torch throttling → degrade gracefully (banner).

## 3. Functional Requirements

### FR-C: Capture & Camera

- **FR-C1**: Support dual concurrent capture when available (`getConcurrentCameraIds()`); otherwise fallback (downscale → YUV-only → sequential).
- **FR-C2**: Use `ImageAnalysis` with `KEEP_ONLY_LATEST`; ensure `image.close()` in `finally`.
- **FR-C3**: Analysis resolution target ≤ 1280×720 @ 30 fps each stream (or device-preferred lower).
- **FR-C4**: Back (finger) torch duration capped ≤ 60 s per session; show thermal banner if MODERATE/SEVERE.
- **FR-C5**: Lock AE & AWB after 500–800 ms settle for face; AF fixed. Finger: fixed exposure/ISO/WB to avoid clipping.
- **FR-C6**: Anti-flicker: set `AE_ANTIBANDING` AUTO/50/60; if ineffective, bias shutter near 1/100s (50 Hz) / 1/120s (60 Hz).

### FR-I: Near-Real-Time Indicators (every 0.5–1.0 s on 6–10 s window)

- **FR-I1**: Finger metrics: Saturation% (≥ 250/255), `SNR_dB` (0.7–4 Hz vs off-band), `AC/DC`, `HR_est`.
- **FR-I2**: Face metrics: includes FR-I1 + `FaceMotionRMS` (optical flow RMS px/frame), AE/AWB locked state.
- **FR-I3**: UI badges: green/yellow/red; tips such as “Increase light”, “Reduce finger pressure”, “Hold head steady”, “Enable torch”.
- **Threshold seeds**: Saturation% ≤ 5%, SNR_face ≥ 6 dB, SNR_finger ≥ 10 dB, |ΔHR| ≤ 5 bpm, FaceMotionRMS ≤ 0.5 px/frame (green).

### FR-S: Signal Processing & GoodSync

- **FR-S1**: Resample to 100 Hz unified timeline; detrend; band-pass 0.7–4.0 Hz; z-normalize.
  - *Reference*: Elgendi, M. (2012). "On the analysis of fingertip photoplethysmogram signals." Current Cardiology Reviews, 8(1), 14-25.
- **FR-S2**: Per-channel SQI (0–100): SNR + penalties (face motion, finger saturation, IMU).
  - *Reference*: Li, Q., & Clifford, G. D. (2012). "Signal quality assessment of pulsatile signals." Physiological Measurement, 33(9), 1491.
- **FR-S3**: Cross-metrics per window: Corr (≥ 0.70), |ΔHR| ≤ 5 bpm, FWHM ≤ 120 ms (± coherence ≥ 0.6).
- **FR-S4**: GoodSync detection with morphological closing (≤ 1 s gaps); segments ≥ 5 s.
  - *Reference*: Clifford, G. D., et al. (2006). "Signal quality in cardiorespiratory monitoring." Physiological Measurement, 27(9), 155.
- **FR-S5**: Optional IMU gating (RMS g ≤ 0.05; mask ±150–250 ms around steps).
- **FR-S6**: Wavelet denoising (DWT with Haar, soft thresholding) applied conditionally for borderline SQI (40-80).
  - *Reference*: Donoho, D. L., & Johnstone, I. M. (1994). "Ideal spatial adaptation by wavelet shrinkage." Biometrika, 81(3), 425-455.
  - *Reference*: Ram, M. R., et al. (2012). "Motion artifact reduction in PPG signals based on AS-LMS adaptive filter." IEEE Trans. Instrumentation and Measurement, 61(5), 1445-1457.
- **FR-S7**: Walking mode with adaptive notch filtering at detected step frequency + 2nd harmonic (Q=5.0).
  - *Reference*: Poh, M. Z., et al. (2010). "Motion tolerant magnetic earring sensor." IEEE Trans. Information Technology in Biomedicine, 14(3), 786-794.
  - *Reference*: Yousefi, R., et al. (2014). "Motion-tolerant adaptive algorithm for wearable PPG biosensors." IEEE J. Biomedical and Health Informatics, 18(2), 670-681.

### FR-P: PTT

- **FR-P1**: XCorr lag with quadratic interpolation (sub-frame) inside GoodSync windows only.
  - *Reference*: Muehlsteff, J., et al. (2006). "Continuous cuff-less measurement of arterial blood pressure using pulse arrival time." IEEE EMBS Conference, 2006.
- **FR-P2**: Foot-to-Foot (d/dt threshold at 10–20% amplitude) per-beat; robust aggregate via median with outlier guard (RANSAC/Huber).
  - *Reference*: Nitzan, M., et al. (2002). "The measurement of oxygen saturation in arterial and venous blood." IEEE Instrumentation & Measurement Magazine, 5(4), 9-15.
- **FR-P3**: Consensus: agreement |XCorr − Foot| ≤ 20 ms; else exclude/down-weight segment.
- **FR-P4**: Session output: `PTT_mean` (ms), `PTT_SD`, `Corr_mean`, `Confidence`, `GoodSync%`, `nBeats`.

### FR-R: Results & Education

- **FR-R1**: UI shows synchronized graphs (raw/filtered), peaks, per-segment chips, final values.
- **FR-R2**: Biomarker panel: HR; HRV (RMSSD/SDNN) only with sufficient quality.
- **FR-R3**: Non-diagnostic wording; persistent “limitations & safe use” note (no BP/mmHg, no AIx/cfPWV claims).

### FR-E: Export & Schemas

- **FR-E1**: Encrypted ZIP:
  - `session.json`: device, API, app version, start/end ISO, duration, fps face/finger, mode, `PTT_mean/SD`, Corr, Confidence, SQIs, drift metrics, GoodSync%, segments `[ { tStart, tEnd, corr, pttMs } ]`, consensus/beats, filters/fs/ROI, 3A states, thermal timeline.
  - `face_signal.csv` & `finger_signal.csv`: `t_ms`, `raw`, `filtered`, `peak_flag`.
  - `plots/*.png`: raw vs filtered 10 s, peaks overlay, xcorr curve ±300 ms, PSD pre/post anti-flicker.
  - `tf/*.csv` (optional, dev/research): STFT time-frequency representations for offline analysis.
    - *Reference*: Allen, J. (2007). "Photoplethysmography and its application in clinical physiological measurement." Physiological Measurement, 28(3), R1.
- **FR-E2**: JSON/CSV schemas present and validated; backward compatibility retained.

### FR-D: Diagnostics

- **FR-D1**: `diagnostics.md`: fps/jitter/drops, inter-camera drift (ms/s), resampler error, perf timings, memory RSS, thermal events.
- **FR-D2**: `device_capabilities.json`, `fps_report.json`, `thermal_timeline.json`.

## 4. Non-Functional Requirements (NFR)

### Performance & Memory

- **NFR-P1**: YUV→ROI mean (including optional YUV→RGB) ≤ 3 ms avg (95p ≤ 6 ms) @ 720p on target devices.
- **NFR-P2**: Total processing per frame avg ≤ 8 ms (95p ≤ 16 ms).
- **NFR-P3**: RSS < 200 MB during 60 s session; no OOM; no monotonic growth.
- **NFR-P4**: Near-real-time UI updates ≤ 4 Hz (no jank); preview ≥ 28 fps.

### Reliability & Fallbacks

- **NFR-R1**: Unsupported concurrency → Safe Mode without crash (downscale → YUV-only → sequential).
- **NFR-R2**: Capture session errors → safe teardown + visible Retry.
- **NFR-R3**: Thermal/torch policies degrade gracefully (banner + adaptation).

### Compatibility

- **NFR-C1**: Min SDK 29; modern Camera2/CameraX devices; front/back sensors typical.
- **NFR-C2**: EIS off on analysis stream; correct orientation mapping for ROI.

### Security & Privacy

- **NFR-S1**: No INTERNET permission; all processing on-device; export encrypted.
- **NFR-S2**: Do not save raw frames by default; only numeric signals + plots.

### Accessibility & UX

- **NFR-A1**: Clear tips/messages; failure modes explained.
- **NFR-A2**: Indicators use color + text label (for color-blind users).

### Medical Wording Safety

- **NFR-M1**: No "blood pressure/mmHg", "cfPWV (m/s)", "AIx%", diagnoses, or treatment advice.
- **NFR-M2**: Use "experimental digital biomarkers" and "relative trends vs your baseline".

## 5. Research References & Validation

VivoPulse's signal processing pipeline is based on peer-reviewed research in photoplethysmography, signal quality assessment, and motion artifact reduction.

### Core Signal Processing

1. **Elgendi, M. (2012).** "On the analysis of fingertip photoplethysmogram signals." *Current Cardiology Reviews*, 8(1), 14-25.
   - Foundation for PPG preprocessing, bandpass filtering (0.7-4.0 Hz), and peak detection algorithms.

2. **Allen, J. (2007).** "Photoplethysmography and its application in clinical physiological measurement." *Physiological Measurement*, 28(3), R1.
   - Comprehensive review of PPG principles, signal characteristics, and clinical applications.

### Wavelet Denoising (Phase 2)

3. **Donoho, D. L., & Johnstone, I. M. (1994).** "Ideal spatial adaptation by wavelet shrinkage." *Biometrika*, 81(3), 425-455.
   - VisuShrink universal threshold method for wavelet coefficient thresholding.

4. **Ram, M. R., Madhav, K. V., Krishna, E. H., Komalla, N. R., & Reddy, K. A. (2012).** "A novel approach for motion artifact reduction in PPG signals based on AS-LMS adaptive filter." *IEEE Transactions on Instrumentation and Measurement*, 61(5), 1445-1457.
   - Application of wavelet denoising to PPG signals for motion artifact reduction.

### Motion Artifact Reduction & Walking Mode (Phase 4)

5. **Poh, M. Z., Swenson, N. C., & Picard, R. W. (2010).** "Motion tolerant magnetic earring sensor and wireless earpiece for wearable photoplethysmography." *IEEE Transactions on Information Technology in Biomedicine*, 14(3), 786-794.
   - Adaptive filtering techniques for motion artifact reduction in wearable PPG sensors.

6. **Yousefi, R., Nourani, M., Ostadabbas, S., & Panahi, I. (2014).** "A motion-tolerant adaptive algorithm for wearable photoplethysmographic biosensors." *IEEE Journal of Biomedical and Health Informatics*, 18(2), 670-681.
   - Adaptive notch filtering and motion compensation for ambulatory PPG monitoring.

### PTT Measurement & Consensus

7. **Muehlsteff, J., Aubert, X. L., & Schuett, M. (2006).** "Cuffless estimation of systolic blood pressure for short effort bicycle tests: the prominent role of the pre-ejection period." *IEEE EMBS Conference*, 2006.
   - Cross-correlation methods for pulse arrival time measurement with sub-frame interpolation.

8. **Nitzan, M., Romem, A., & Koppel, R. (2002).** "Pulse oximetry: fundamentals and technology update." *Medical Devices: Evidence and Research*, 2014(7), 231-239.
   - Foot-to-foot detection methods for pulse wave analysis and PTT calculation.

### Signal Quality Assessment

9. **Li, Q., & Clifford, G. D. (2012).** "Dynamic time warping and machine learning for signal quality assessment of pulsatile signals." *Physiological Measurement*, 33(9), 1491.
   - SNR-based SQI metrics with motion and saturation penalties for PPG quality assessment.

10. **Clifford, G. D., & Moody, G. B. (2006).** "Signal quality in cardiorespiratory monitoring." *Physiological Measurement*, 27(9), 155.
    - Multi-metric quality gating and morphological closing for segment detection (GoodSync).

### Time-Frequency Analysis (Phase 3)

11. **Allen, J., & Murray, A. (2003).** "Age-related changes in the characteristics of the photoplethysmographic pulse shape at various body sites." *Physiological Measurement*, 24(2), 297.
    - STFT and time-frequency analysis for PPG signal characterization.

### Implementation Notes

- **Butterworth Filter**: 4th-order IIR implementation for 24dB/octave roll-off (sharper attenuation than 2nd-order).
- **Wavelet**: Haar (DB1) wavelet chosen for computational efficiency on mobile devices; 4-level decomposition.
- **Notch Filter**: Q-factor of 5.0 provides narrow notch bandwidth (~0.3-0.5 Hz) suitable for step frequency rejection.
- **PTT Consensus**: Dual-method approach (cross-correlation + foot-to-foot) with median aggregation improves robustness vs. single-method estimation.

### Validation & Testing

All signal processing components include unit tests with synthetic signals:
- `BandPassFilterTests.kt`: Validates frequency response and DC offset removal
- `WaveletDenoiserTest.kt`: Verifies lossless reconstruction and SNR improvement
- `TimeFrequencyToolingTest.kt`: Confirms correct frequency identification
- `PTTConsensusTest.kt`: Tests consensus algorithm with known ground truth

For detailed test coverage, see `QA_CHECKLIST.md` and module-specific test suites.


