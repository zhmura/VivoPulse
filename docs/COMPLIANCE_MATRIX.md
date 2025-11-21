## Spec Compliance Matrix (MVP)

This document tracks the current implementation against `docs/FUNCTIONAL_SPEC.md` (MVP functional & non‑functional spec).

- **Status legend**: **PASS** = implemented & covered by tests or clear runtime behavior; **PARTIAL** = implemented but missing pieces and/or tests; **FAIL/NYI** = not implemented or intentionally out of scope.
- **Evidence** points to existing code/tests/docs; instrumentation‑only items are marked as such.

---

### 1) Capture & Camera

| ID  | Description | Status | Evidence (code/tests) | Notes / Smallest Fix |
| --- | ----------- | ------ | --------------------- | --------------------- |
| **C1** | Concurrent probe & fallbacks (downscale → YUV-only → sequential) | **PARTIAL** | `DualCameraController.initialize()` and `startCamera()` set `_cameraMode` based on `DeviceProbe.recommendMode` and use `CameraBindingHelper.startConcurrent/bindSequential` with `handleCameraStartFailure()` fallbacks to `SAFE_MODE_REDUCED` / `SAFE_MODE_SEQUENTIAL`. | Concurrent vs sequential modes and reduced-resolution fallback exist, but there is no explicit YUV-only path toggle or `ConcurrentConfigTests`. Add a simple "analysis-only" mode flag in `CameraBindingHelper` and unit tests that simulate failures and assert mode transitions. |
| **C2** | Backpressure `KEEP_ONLY_LATEST`, `image.close()` always | **PASS** | `DualCameraController.createImageAnalysis()` and `CameraBindingHelper` builders use `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`; analyzer wrapped in `SafeImageAnalyzer` which always calls `image.close()` (`SafeImageAnalyzer.analyze`). `SafeImageAnalyzerTest.closesImageOnException()` verifies close-on-throw. | Lifecycle is correct; `AnalyzerLifecycleTests` name from spec is satisfied by `SafeImageAnalyzerTest` in spirit, but could be renamed/extended for clarity. |
| **C3** | ≤ 720p@30 analysis; previews ≥ 28 fps; no frame backlog | **PARTIAL** | `createImageAnalysis()` sets `setTargetResolution(Size(720, 1280))`. `FpsTracker` used in `processFrame()` and surfaced in `CaptureScreen` top bar. Backpressure + buffer pooling mitigate backlog. | Target resolution constraint is implemented, but there is no automated `YuvPerfTests` or perf test asserting 28+ fps and no backlog. Add synthetic `YuvPerfTests` around `LumaExtractor` and a simple stress test that checks `FpsTracker` ≥ 28 fps on prerecorded frames. |
| **C4** | Torch cap ≤ 60 s; thermal banner & adaptation | **PARTIAL** | `TorchPolicyManager` enforces `MAX_TORCH_DURATION_MS = 60_000` and cooldown, but it is not yet wired into `DualCameraController.setTorchEnabled`. `DualCameraController.setupThermalMonitoring()` updates `_statusBanner`, disables torch, and stops recording on SEVERE+ states. | Thermal adaptation and user banner exist and are exercised by runtime, but torch duration is not enforced in the active pipeline. Wire `TorchPolicyManager` into `DualCameraController.toggleTorch()` and schedule periodic `updateTimer()` (e.g. from `CaptureViewModel`) to force-disable torch on timeout and show a short tip. Add `ThermalTorchTests`. |
| **C5** | AE/AWB lock after settle; AF fixed; finger exposure/ISO/WB fixed | **PARTIAL** | `ThreeALockManager` and `CameraLockController` manage `CONTROL_AE_LOCK`, AWB, and AF modes; Clinician docs and tech guide describe usage. | 3A lock code exists but is not yet fully integrated into the current dual‑camera binding path with a clear “lock after 500–800 ms” policy, and there is no explicit "fixed finger exposure" configuration. Add a small state machine in `ThreeALockManager` driven by elapsed capture time plus unit tests. |
| **C6** | Anti-flicker (AUTO/50/60 or shutter bias to 1/100–1/120) | **PARTIAL** | `AntiFlickerController` configures `CONTROL_AE_ANTIBANDING_MODE_*` based on `FlickerMode`. | Antibanding wiring is present but not yet validated end‑to‑end against PSD metrics as in the spec. Add an instrumentation test or offline PSD test using synthetic 50/60 Hz flicker and assert ≥ 10 dB suppression. |

---

### 2) Near-Real-Time Indicators

| ID  | Description | Status | Evidence | Notes / Smallest Fix |
| --- | ----------- | ------ | -------- | --------------------- |
| **I1** | Finger: Saturation%, SNR_dB, AC/DC, HR_est | **PASS (core), PARTIAL (tests)** | `DualCameraController.processFrame()` computes `fingerSaturationPct` and emits into `SignalSample`. `RealTimeQualityEngine` computes SNR via `SnrEstimator`, AC/DC ratio, HR estimate via `PeakDetect`/`HeartRate`, and packages into `ChannelQualityIndicator` for `ChannelType.FINGER`. | Feature is implemented and wired to UI via `CaptureViewModel.qualityState` and `CaptureScreen.QualityIndicatorsSection`, but dedicated `QualityIndicatorTests` for saturation/SNR→badge behavior are minimal; extend `RealTimeQualityEngineTest` to cover saturation edge cases. |
| **I2** | Face: + FaceMotionRMS; AE/AWB locked flag | **PARTIAL** | `DualCameraController.processFrame()` computes `faceMotionRms` and emits via `SignalSample`; `RealTimeQualityEngine` uses it as `auxMetric` for face channel. | Motion is integrated into face SQI, but AE/AWB lock state is not yet surfaced into the indicator or UI. Add a simple `threeALocked: Boolean` metric into `SignalSample` and thread it through to `ChannelQualityIndicator.diagnostics`. |
| **I3** | UI indicator updates 2–4 Hz; correct tips; no jank | **PARTIAL** | `RealTimeQualityEngine` uses `updateIntervalMs = 400` (≈2.5 Hz) and thresholds aligned with spec; `CaptureScreen` renders `QualityIndicatorsSection` + tip text. | Update rate policy is correct by design, but there is no automated `UIUpdateRateTest` or instrumentation measuring jank. Add a test harness feeding timestamps into `RealTimeQualityEngine` and asserting emit frequency, plus a lightweight Compose test that ensures recomposition is throttled. |

---

### 3) Signal Processing & GoodSync

| ID  | Description | Status | Evidence | Notes / Smallest Fix |
| --- | ----------- | ------ | -------- | --------------------- |
| **S1** | 100 Hz resample; detrend; 0.7–4 Hz band-pass; z-norm | **PASS (DSP utilities), PARTIAL (end-to-end)** | `DspFunctions` provides detrend/bandpass; `ProcessingPipeline` and `SignalPipeline` (plus tests `PttSqiTests`, DSP tests referenced in `VERIFICATION_MATRIX.md`) use 0.7–4 Hz bandpass and 100 Hz resampling. | Building blocks match the spec; ensure all current processing entry points call the 100 Hz pipeline and add an explicit `ResampleTo100HzTest` verifying error ≤5 ms alignment. |
| **S2** | SQI_face & SQI_finger (SNR + penalties) | **PARTIAL** | `ChannelSqi`, `FingerSaturationIndex`, `FaceMotionIndex`, and `EnhancedSQI` implement SNR + motion/saturation penalties; `RealTimeQualityEngine` uses SNR/motion/saturation in status. | Channel SQIs are implemented but not consistently surfaced as a 0–100 value in the UI / export (they currently flow via metadata and Clinician export). Add a small adapter mapping underlying SQI to 0–100 and expose through `SessionMetadata`. |
| **S3** | Cross-metrics (Corr≥0.70; |ΔHR|≤5 bpm; FWHM≤120 ms; coherence) | **PARTIAL** | `SyncMetrics.computeMetrics` returns correlation, HR delta, FWHM; used by `GoodSyncDetector` and `PTTConsensus`. | Thresholds are hard-coded to match spec, but coherence and explicit tests (`SyncMetricsTests`) are minimal. Extend `SyncMetrics` tests with synthetic signals meeting/failing thresholds. |
| **S4** | GoodSync segments ≥5 s with morphological closing | **FAIL/NYI** | `GoodSyncDetector.detectGoodSyncWindows()` currently evaluates a single window and `detectSessionSegments()` is a stub returning `emptyList()`. | Implement rolling-window segmentation plus simple "closing" over ≤1 s gaps in `detectSessionSegments()`, and add `GoodSyncDetectorTests` for basic cases. Until then, mark GoodSync percentage as experimental in UI. |
| **S5** | IMU gating (RMS g; step masking) improves GoodSync share in motion | **FAIL/NYI** | `ImuMotionGate` exists but is currently a thin placeholder with most parameters marked unused. | Integrate IMU stream into processing pipeline and `GoodSyncDetector` ROI stats, then add comparative tests (with/without IMU gating) on synthetic motion traces. For now, IMU gating is explicitly unsupported and should be omitted from UI copy. |

---

### 4) PTT (Consensus)

| ID  | Description | Status | Evidence | Notes / Smallest Fix |
| --- | ----------- | ------ | -------- | --------------------- |
| **P1** | XCorr lag with quadratic sub-frame | **PARTIAL** | `SyncMetrics.computeMetrics` and related tests compute lag; sub-frame interpolation is present in DSP utilities. | Ensure `SyncMetrics` path used by `PTTConsensus` explicitly uses quadratic interpolation and add a focused `XcorrSubFrameTest` with known synthetic lag (e.g. 120 ms). |
| **P2** | Foot-to-Foot via d/dt threshold 10–20% | **PARTIAL** | `PTTConsensus.detectFeet()` uses local minima as a simple foot detector. | Algorithm works but does not yet implement 10–20% d/dt threshold logic; refine feet detection and validate on synthetic beats. |
| **P3** | Agreement |XCorr−Foot| ≤20 ms; robust median/IQR | **PARTIAL** | `PTTConsensus.estimateConsensusPtt()` computes median, IQR, and method disagreement `methodAgreeMs`. | Consensus rules (rejecting segments with poor agreement) are not enforced yet. Add a guard `if (agreement > 20 ms) -> mark segment low-confidence` and tests that enforce spec thresholds. |
| **P4** | Session outputs: PTT_mean, PTT_SD, Corr_mean, Confidence, GoodSync%, nBeats | **PARTIAL** | `SessionMetadata` and `ClinicianGradeExporter.createClinicianJson()` export `ptt_ms_mean`, `ptt_ms_sd`, `corr_score`, `confidence`, and some SQIs. | GoodSync% and nBeats are not yet fully reported; extend `SessionMetadata` to hold them and include in `session.json`, plus a simple schema update and unit test. |

---

### 5) Results & Education

| ID  | Description | Status | Evidence | Notes / Smallest Fix |
| --- | ----------- | ------ | -------- | --------------------- |
| **R1** | Graphs; per-segment chips; final numbers | **PARTIAL** | `ResultScreen` renders graphs and summary metrics; plots are also generated in `ClinicianGradeExporter` as PNGs. | Per‑segment chips and GoodSync visualization are basic. Extend `ResultScreen` to show GoodSync segments as chips when available. |
| **R2** | Biomarker panel: HR; HRV (RMSSD/SDNN) only if quality OK | **PARTIAL** | HR is exposed; HRV calculation utilities exist in the processing layer. | Gate HRV display based on SQI and add a small `HrvCorrectnessTest` comparing against reference HRV implementation. |
| **R3** | Persistent limitations note; no clinical claims | **PASS (with caveats)** | `USER_GUIDE.md`, on-device copy, and removal of `MedicalWordingCheck.kt` plus wording changes avoid explicit BP/mmHg/cfPWV/AIx claims. | Run a lightweight text scan regularly (replacement for `MedicalWordingCheck`) to ensure new strings remain compliant. |

---

### 6) Export & Schemas

| ID  | Description | Status | Evidence | Notes / Smallest Fix |
| --- | ----------- | ------ | -------- | --------------------- |
| **E1** | ZIP contains session.json, CSVs, plots; rich metadata | **PASS (core), PARTIAL (thermal/3A completeness)** | `ClinicianGradeExporter.exportSession()` writes encrypted ZIP with `session.json`, `face_signal.csv`, `finger_signal.csv`, and plots (`raw_vs_filtered`, `peaks_overlay`, `xcorr_curve`). `createClinicianJson()` includes drift metrics, SQIs, etc., and accepts optional thermal and 3A timelines. | Thermal/3A timelines are present but not yet fully populated from runtime; wire `ThermalGuard` and 3A lock telemetry into `SessionMetadata`. |
| **E2** | JSON/CSV validate against schemas; backward compatible | **PARTIAL** | Schema discussion and prior verification appear in `docs/archive/CLINICIAN_GRADE_EXPORT_IMPLEMENTATION.md` and `VERIFICATION_MATRIX.md`; CSV header defined via `SignalDataPoint.CSV_HEADER`. | Re‑run schema validation for the current `schema_version = 1.1` and add/update `ExportSchemaTests` to enforce backward compatibility. |

---

### 7) Diagnostics

| ID  | Description | Status | Evidence | Notes / Smallest Fix |
| --- | ----------- | ------ | -------- | --------------------- |
| **D1** | diagnostics.md with fps/jitter/drops, drift, perf, memory, thermal | **PARTIAL** | Historical `docs/archive/reports/diagnostics.md` and `RUNTIME_ROBUSTNESS_MATRIX.md` exist; telemetry hooks live in `TelemetryLogger`. | Regenerate a fresh `docs/diagnostics.md` (or `build/verification/diagnostics.md`) from current telemetry after test runs. |
| **D2** | device_capabilities.json; fps_report.json; thermal_timeline.json | **FAIL/NYI (artifacts)** | Device probing exists via `DeviceProbe`, but JSON exports are not generated as standalone files in `build/verification/`. | Add a small `VerificationReportGenerator` in `core-io` to serialize probe and telemetry data to the requested JSON artifacts after an instrumented run. |

---

### 8) Non-Functional (Performance, Reliability, Privacy, Wording)

| ID  | Description | Status | Evidence | Notes / Smallest Fix |
| --- | ----------- | ------ | -------- | --------------------- |
| **P1** | YUV→ROI mean ≤ 3 ms avg (95p ≤ 6 ms) | **FAIL/NYI (no active perf test)** | Former `YuvPerfTests.kt` has been deleted; current `LumaExtractor` and buffer pooling are optimized but unmeasured in CI. | Re‑introduce `YuvPerfTests` using synthetic 720p buffers, measuring ROI mean time and asserting thresholds. |
| **P2** | Processing/frame ≤ 8 ms avg (95p ≤ 16 ms) | **PARTIAL** | Processing code is written with O(N) primitives and re‑use of buffers; no active perf gate. | Add microbenchmarks or simple perf unit tests around the full processing pipeline using prerecorded segments. |
| **P3** | RSS < 200 MB; no OOM; stable memory in 60 s | **PARTIAL** | Deep copy logic has been fixed (`Frame.deepCopy` and buffer pooling), and OOM has been removed in manual testing; `MemoryGuard` exists for debug monitoring. | Implement `MemoryUsageTest` that drives a 60 s synthetic session and asserts plateauing RSS; wire into CI. |
| **P4** | Preview smooth; indicator ≤ 4 Hz | **PARTIAL** | `RealTimeQualityEngine` update interval enforces ≤ 2.5 Hz; CameraX preview is configured for 30 fps and appears smooth on devices. | As with I3, add `UIUpdateRateTest` and simple jank monitoring via frame timing logs. |
| **R1** | Safe Mode works; no crash; retry path OK | **PARTIAL** | `DualCameraController.handleCameraStartFailure()` switches modes and updates `_statusBanner` with retry messaging. | Add `SessionErrorTests` simulating `onError / onConfigureFailed` to assert safe teardown and retry. |
| **C1** | Orientation mapping correct; EIS off for analysis | **PARTIAL** | Pathways in `CameraBindingHelper` and related docs describe correct orientation and analysis‑only streams. | Add `OrientationTests` using mocked `CameraCharacteristics` to verify ROI mapping and EIS flags. |
| **S1** | No INTERNET; on-device; encrypted export | **PASS** | `AndroidManifest` omits `INTERNET` permission; all processing is on-device; `ClinicianGradeExporter` uses `EncryptedFile` + `MasterKey`. | Keep `INTERNET` absent and add a manifest lint rule or unit test to guard against regressions. |
| **M1/M2** | Medical wording safe; disclaimers present | **PARTIAL** | Wording across `USER_GUIDE.md`, UI strings, and docs avoids BP/mmHg/cfPWV/AIx; educational, non-diagnostic phrasing is used. | Replace the old `MedicalWordingCheck` with a new doc/unit test that scans resources and markdown for forbidden terms and enforces disclaimer presence. |

---

### Summary

- **Core capture, analysis, real-time indicators, and clinician-grade export are implemented and largely aligned with the MVP spec.**
- **GoodSync segmentation, IMU gating, some PTT consensus rules, and automated perf/memory gates remain the main gaps.**
- **Next steps**: implement the missing GoodSync/IMU pieces, re‑introduce deleted perf/memory tests, wire TorchPolicyManager + 3A telemetry, and regenerate diagnostics/export artifacts under `build/verification/` as described in the verification prompt.


