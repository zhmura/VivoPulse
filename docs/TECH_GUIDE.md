# VivoPulse — Tech Guide (Build & Debug)

## Build
- CLI:
  - Debug APK: `./gradlew assembleDebug`
  - Run unit tests (processing only): `./gradlew :feature-processing:test`
  - Run all unit tests: `./gradlew test`
- Android Studio:
  - Open project root.
  - Select “app” module, “debug” variant.
  - Build/Run on a whitelisted device (see README).

## Modules (high level)
- app: UI (Jetpack Compose), navigation, ViewModels, export
- feature-processing: DSP, PTT, SQI, wave features, biomarkers, reactivity analyzer
- feature-capture: dual-camera capture (face/finger), ROI overlays
- core-signal: low-level DSP utilities, SQI components
- core-io: export (encrypted ZIP), session.json writer

## Key runtime switches
- Simulated Mode toggle (Debug menu on Capture screen): generates synthetic data for quick testing.
- Torch control on Capture screen for finger camera.

## Where computations happen
- PTT: `feature-processing/PttCalculator` (cross-correlation lag; dual-site optical PTT surrogate)
- SQI & confidence: `core-signal/SignalQuality` (+ enhanced variants)
- Wave features: `feature-processing/wave/WaveFeatures`
- Biomarkers: `feature-processing/biomarker/Biomarkers`
- Trend index: `app/trend/VascularTrendStore`
- Reactivity protocol: `feature-processing/reactivity/ReactivityProtocolAnalyzer`

## Result screen state
- ViewModel: `app/viewmodel/ProcessingViewModel`
  - Exposes: PTT result, QualityReport, VascularWaveProfile, BiomarkerPanel, VascularTrendSummary, SessionSummary.

## Export
- Writer: `core-io/DataExporter`
- session.json includes:
  - Required: device/app/session/ptt/quality/camera
  - Optional enrichment (if available): `vascularWaveProfile`, `vascularTrendSummary`, `biomarkerPanel`, `reactivityProtocol`
- Files: `session.json`, `face_signal.csv`, `finger_signal.csv` (encrypted ZIP)

## Tests
- Processing module: `feature-processing/src/test/...`
  - PTT literature consistency (lag accuracy, monotonicity)
  - Wave feature directionality on synthetic shapes
  - HRV formula sanity (RMSSD/SDNN)
  - SQI monotonic behavior
  - Wording check to avoid forbidden clinical claims (except explicit disclaimers)

## Debug workflow tips
1. Start with Simulated Mode on Capture screen.
2. Process once; open Results and verify PTT/SQI.
3. Toggle torch and adjust lighting to improve SNR.
4. Use Reactivity Protocol to exercise deltas and recovery computation.
5. Export ZIP and inspect `session.json` and CSVs.

## Common pitfalls
- Low light or motion → poor SQI; PTT and advanced metrics suppressed.
- Finger not fully covering lens → unstable signal.
- Avoid interpreting PTT/shape features as clinical stiffness or BP.


