# VivoPulse — Tech Guide (Build & Debug)

## 1. Build System

The project uses standard Gradle build system. Key modules:
- `:app` — Main application entry point and UI
- `:core-signal` — Pure Kotlin signal processing logic
- `:core-io` — Data export and formatting
- `:feature-capture` — CameraX implementation and frame processing
- `:feature-processing` — PTT algorithms and pipeline orchestration
- `:feature-results` — Results screen and visualization

### Build Commands
- ` ./gradlew assembleDebug` — Build debug APK
- ` ./gradlew installDebug` — Install to connected device
- ` ./gradlew test` — Run unit tests
- ` ./gradlew connectedAndroidTest` — Run instrumented tests

## 2. Architecture Overview

### Camera Capture
- Uses `CameraX` with `ImageAnalysis` use case.
- **Dual Camera**: Supports concurrent front/back capture on compatible devices (Pixel 6+, Samsung S22+).
- **Fallback**: Automatically degrades to sequential mode (one camera at a time) if concurrent is not supported or fails.
- **Luma Extraction**: Frame processing extracts average luma from ROI (Face) or center (Finger) directly from YUV buffer.
- **Safety**: `SafeImageAnalyzer` ensures `image.close()` is called to prevent pipeline stalls.

### Signal Processing
- **Pipeline**: Resampling (100Hz) -> Detrend -> Bandpass (0.7-4Hz) -> Normalization.
- **PTT**: Calculates Pulse Transit Time using Cross-Correlation + Foot-to-Foot consensus.
- **Sync**: `GoodSyncDetector` checks signal quality and correlation to identify valid windows.

### Visualization
- **Waveform**: Real-time preview shows raw luma signal.
  - *Note*: Auto-scaling is clamped to a minimum range (10.0) to prevent small noise from appearing as large jumps. Real PPG signals (amplitude ~30+) will fill the view.
- **Quality Indicators**: Real-time traffic light (Green/Yellow/Red) based on SNR and motion.

## 3. Debugging

### Logs
Filter Logcat by `tag:VivoPulse`:
- `DualCameraController`: Camera state, frame drops, luma values.
- `ProcessingViewModel`: Pipeline steps, synthetic fallback events.
- `PttEngine`: PTT calculation details.

### Common Issues
- **FPS 0**: Check if `SafeImageAnalyzer` is closing images. Check thermal state.
- **No Waveform**: Verify luma values in logs (`Face luma: ...`). If 0, check ROI detection.
- **Sequential Mode**: Only one camera runs. The other channel uses synthetic data for pipeline stability.

## 4. Testing

- **Unit Tests**: `./gradlew test` covers signal processing logic (`SignalQuality`, `PttConsensus`).
- **Robolectric**: Used for camera binding logic tests (`CameraBindingHelperTest`).
- **Manual Validation**: See `docs/ON_DEVICE_TESTING_GUIDE.md`.
