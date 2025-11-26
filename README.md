# VivoPulse

VivoPulse is an Android application for dual-camera capture, on-device signal processing, and PPG (photoplethysmography) signal analysis.

## Overview

VivoPulse is designed to capture video from device cameras, extract PPG signals through advanced signal processing, and visualize cardiovascular metrics in real-time. The app is optimized for specific high-performance Android devices and operates completely offline.

## Features

- **Dual-Camera Capture**: Simultaneous capture from front and rear cameras
- **On-Device Signal Processing**: Real-time PPG signal extraction and analysis
- **Offline Operation**: No internet permission - all processing happens on-device
- **Device Whitelist**: Optimized for specific high-performance devices
- **Simulated Mode**: Debug mode with synthetic PPG signals for testing
- **Data Export**: Export results to CSV or JSON format
- **Encrypted Storage**: Secure data storage using Android Security libraries

## Supported Devices

VivoPulse is currently optimized for the following devices:

### Google Pixel Series
- Pixel 6, 6 Pro, 6a
- Pixel 7, 7 Pro, 7a
- Pixel 8, 8 Pro, 8a

### Samsung Galaxy S Series
- Galaxy S22, S22+, S22 Ultra
- Galaxy S23, S23+, S23 Ultra
- Galaxy S24, S24+, S24 Ultra

> **Note**: The app will display a warning banner on unsupported devices but can still be used in Simulated Mode for testing.

## System Requirements

- **Minimum SDK**: Android 10 (API 29)
- **Target SDK**: Android 14 (API 34)
- **RAM**: 4GB minimum, 6GB+ recommended
- **Storage**: 100MB for app, additional space for data exports

## Project Structure

The project is organized into modular components:

```
VivoPulse/
├── app/                      # Main Android application module
├── core-signal/              # Pure Kotlin DSP library (vendor-agnostic)
├── core-io/                  # CSV/JSON export functionality
├── feature-capture/          # Camera capture with CameraX & OpenCV
├── feature-processing/       # Signal processing pipeline orchestrator
└── feature-results/          # UI graphs and results display
```

### Module Descriptions

- **app**: Main application entry point, navigation, and UI theme
- **core-signal**: Pure Kotlin module for signal processing algorithms (filters, FFT, heart rate calculation)
- **core-io**: Data export utilities for CSV and JSON formats with encryption support
- **feature-capture**: Camera management using CameraX with Camera2 interop and ML Kit face detection
- **feature-processing**: Orchestrates the processing pipeline from camera frames to processed signals
- **feature-results**: Results visualization using MPAndroidChart

## Build & Guides

### Prerequisites

- **Android Studio**: Hedgehog (2023.1.1) or later
- **JDK**: Java 17
- **Gradle**: 8.2 (included via wrapper)
- **Android SDK**: API 29-34

### Quick Build

```bash
# Clone the repository
cd /path/to/VivoPulse

# Build the project (from Android Studio or command line)
./gradlew assembleDebug

# Install debug build on connected device
./gradlew installDebug

# Run tests
./gradlew test
```

### Guides
- User Guide: `docs/USER_GUIDE.md`
- Tech Guide (build & debug): `docs/TECH_GUIDE.md`
- Calculation References: `docs/CALCULATION_REFERENCE.md`
```

### Build Variants

- **Debug**: Includes Simulated Mode toggle and debug menu (BuildConfig.ENABLE_SIMULATED_MODE = true)
- **Release**: Production build with ProGuard optimization, no simulated mode

## Running the App

### From Android Studio

1. Open the project in Android Studio
2. Connect a supported device via USB or use an emulator (API 29+)
3. Enable USB debugging on the device
4. Click **Run** (Shift+F10) or select **Run > Run 'app'**
5. Grant camera permissions when prompted

### From Command Line

```bash
# Install and launch debug build
./gradlew installDebug
adb shell am start -n com.vivopulse.app/.MainActivity
```

## Using the App

### Main Flow

1. **Capture Screen**: 
   - Initial screen with camera preview placeholder
   - Access debug menu via settings icon (top-right)
   - Device support banner shows if device not whitelisted
   - Start capture to proceed to processing

2. **Processing Screen**:
   - Signal processing pipeline runs here
   - Progress indicator shows processing status
   - Navigate to results when complete

3. **Results Screen**:
   - View PPG signal graphs
   - Display heart rate and other metrics
   - Export data to CSV/JSON
   - Return to capture for new measurement

### Debug Menu (Debug Build Only)

Access the debug menu from the Capture Screen:

- **Simulated Mode Toggle**: Enable/disable synthetic PPG signal generation for testing
- **Device Info**: View current device and support status

When Simulated Mode is active, a banner appears on the Capture Screen.

## Development

### Adding Dependencies

Dependencies are managed in each module's `build.gradle.kts`:

```kotlin
// Example: Adding a new dependency to app module
dependencies {
    implementation("your.dependency:name:version")
}
```

### Feature Flags

Feature flags are managed in `app/src/main/java/com/vivopulse/app/util/FeatureFlags.kt`:

- `isSimulatedModeEnabled()`: Check if simulated mode is active
- `setSimulatedModeEnabled(Boolean)`: Toggle simulated mode (debug only)
- `enforceDeviceWhitelist()`: Check if device whitelist should be enforced

### Device Whitelist

Modify the device whitelist in `app/src/main/java/com/vivopulse/app/util/DeviceWhitelist.kt`:

```kotlin
private val SUPPORTED_DEVICES = setOf(
    "Device Model" to "Manufacturer",
    // Add more devices...
)
```

## Permissions

The app requires the following permissions (declared in `AndroidManifest.xml`):

- `CAMERA`: Required for video capture
- `WRITE_EXTERNAL_STORAGE`: For data export (API ≤ 32, scoped storage)
- `READ_MEDIA_VIDEO`: For media access (API 33+)
- `READ_MEDIA_IMAGES`: For media access (API 33+)

> **Note**: No internet permission is requested - the app is fully offline.

## Technology Stack

### Core Libraries

- **Kotlin**: 1.9.20
- **Jetpack Compose**: BOM 2023.10.01
- **Material 3**: Latest
- **CameraX**: 1.3.1
- **Kotlin Coroutines**: 1.7.3

### Camera & ML

- **CameraX Camera2 Interop**: Dual-camera support
- **ML Kit Face Detection**: 16.1.5
- **OpenCV**: 4.8.0 (for image processing)

### Data & Storage

- **Room Database**: 2.6.1 (with KTX)
- **Security Crypto**: 1.1.0-alpha06 (encrypted storage)
- **Kotlinx Serialization JSON**: 1.6.0

### UI & Visualization

- **Jetpack Compose Navigation**: 2.7.5
- **MPAndroidChart**: v3.1.0 (for signal graphs)

### Dependency Injection

- **Hilt**: 2.48.1 (Dagger-based DI for Android)

## Signal Processing Pipeline

VivoPulse implements a research-backed signal processing pipeline for PPG analysis:

### 1. Preprocessing & Filtering
- **Detrending**: High-pass IIR filter (0.5 Hz cutoff) removes baseline drift
- **Bandpass Filtering**: 4th-order Butterworth (0.7-4.0 Hz) isolates cardiac frequencies
  - Reference: *Elgendi, M. (2012). "On the analysis of fingertip photoplethysmogram signals." Current Cardiology Reviews, 8(1), 14-25.*

### 2. Wavelet Denoising (Phase 2)
- **Method**: Discrete Wavelet Transform (DWT) with Haar wavelet
- **Application**: Conditional denoising for borderline SQI (40-80) signals
- **Thresholding**: Soft thresholding with VisuShrink universal threshold
  - Reference: *Donoho, D. L., & Johnstone, I. M. (1994). "Ideal spatial adaptation by wavelet shrinkage." Biometrika, 81(3), 425-455.*
  - Reference: *Ram, M. R., et al. (2012). "A novel approach for motion artifact reduction in PPG signals based on AS-LMS adaptive filter." IEEE Transactions on Instrumentation and Measurement, 61(5), 1445-1457.*

### 3. Signal Quality Assessment (SQI)
- **Metrics**: SNR (signal-to-noise ratio), motion penalties, saturation checks
- **IMU Integration**: Accelerometer-based motion gating
  - Reference: *Li, Q., & Clifford, G. D. (2012). "Dynamic time warping and machine learning for signal quality assessment of pulsatile signals." Physiological Measurement, 33(9), 1491.*

### 4. Time-Frequency Analysis (Phase 3)
- **Method**: Short-Time Fourier Transform (STFT) with Hann windowing
- **Application**: Offline analysis and research export
- **Parameters**: 256-sample window, 50% overlap (128 samples)
  - Reference: *Allen, J. (2007). "Photoplethysmography and its application in clinical physiological measurement." Physiological Measurement, 28(3), R1.*

### 5. Walking Mode - Adaptive Notch Filtering (Phase 4)
- **Step Detection**: Peak detection on accelerometer magnitude with autocorrelation
- **Notch Filter**: IIR biquad notch at detected step frequency + 2nd harmonic
- **Q-Factor**: 5.0 for narrow notch bandwidth
  - Reference: *Poh, M. Z., et al. (2010). "Motion tolerant magnetic earring sensor and wireless earpiece for wearable photoplethysmography." IEEE Transactions on Information Technology in Biomedicine, 14(3), 786-794.*
  - Reference: *Yousefi, R., et al. (2014). "A motion-tolerant adaptive algorithm for wearable photoplethysmographic biosensors." IEEE Journal of Biomedical and Health Informatics, 18(2), 670-681.*

### 6. PTT Consensus Algorithm
- **Method A**: Cross-correlation with parabolic interpolation (sub-frame precision)
- **Method B**: Foot-to-foot detection using derivative threshold (10-20% amplitude)
- **Consensus**: Median aggregation with IQR-based outlier rejection; agreement threshold ≤20ms
  - Reference: *Nitzan, M., et al. (2002). "The measurement of oxygen saturation in arterial and venous blood." IEEE Instrumentation & Measurement Magazine, 5(4), 9-15.*
  - Reference: *Muehlsteff, J., et al. (2006). "Continuous cuff-less measurement of arterial blood pressure using the pulse arrival time." IEEE EMBS Conference, 2006.*

### 7. GoodSync Detection
- **Criteria**: Multi-metric quality gating (SQI ≥70, correlation ≥0.70, HR delta ≤5 bpm, FWHM ≤120ms)
- **Morphological Closing**: Bridges gaps ≤1s to create continuous high-quality segments
- **Minimum Duration**: 5s segments for reliable PTT estimation
  - Reference: *Clifford, G. D., et al. (2006). "Signal quality in cardiorespiratory monitoring." Physiological Measurement, 27(9), 155.*

### Research References

For detailed calculation methods and validation studies, see:
- **Calculation Reference**: `docs/CALCULATION_REFERENCE.md`
- **Technical Guide**: `docs/TECH_GUIDE.md`
- **Functional Specification**: `docs/FUNCTIONAL_SPEC.md`

### Key Publications

1. **PPG Signal Processing**:
   - Elgendi, M. (2012). "On the analysis of fingertip photoplethysmogram signals." *Current Cardiology Reviews*, 8(1), 14-25.
   - Allen, J. (2007). "Photoplethysmography and its application in clinical physiological measurement." *Physiological Measurement*, 28(3), R1.

2. **Wavelet Denoising**:
   - Donoho, D. L., & Johnstone, I. M. (1994). "Ideal spatial adaptation by wavelet shrinkage." *Biometrika*, 81(3), 425-455.
   - Ram, M. R., et al. (2012). "A novel approach for motion artifact reduction in PPG signals." *IEEE Trans. Instrumentation and Measurement*, 61(5), 1445-1457.

3. **Motion Artifact Reduction**:
   - Poh, M. Z., et al. (2010). "Motion tolerant magnetic earring sensor." *IEEE Trans. Information Technology in Biomedicine*, 14(3), 786-794.
   - Yousefi, R., et al. (2014). "A motion-tolerant adaptive algorithm for wearable PPG biosensors." *IEEE J. Biomedical and Health Informatics*, 18(2), 670-681.

4. **PTT Measurement**:
   - Muehlsteff, J., et al. (2006). "Continuous cuff-less measurement of arterial blood pressure using pulse arrival time." *IEEE EMBS Conference*, 2006.
   - Nitzan, M., et al. (2002). "The measurement of oxygen saturation in arterial and venous blood." *IEEE Instrumentation & Measurement Magazine*, 5(4), 9-15.

5. **Signal Quality Assessment**:
   - Li, Q., & Clifford, G. D. (2012). "Dynamic time warping and machine learning for signal quality assessment." *Physiological Measurement*, 33(9), 1491.
   - Clifford, G. D., et al. (2006). "Signal quality in cardiorespiratory monitoring." *Physiological Measurement*, 27(9), 155.

## Testing

### Unit Tests

```bash
# Run all unit tests
./gradlew test

# Run specific module tests
./gradlew :core-signal:test
```

### Instrumented Tests

```bash
# Run instrumented tests (requires connected device)
./gradlew connectedAndroidTest
```

### Manual Testing

See **[QA_CHECKLIST.md](QA_CHECKLIST.md)** for comprehensive testing guide including:
- Camera capture scenarios
- ROI tracking validation
- Signal quality conditions
- Performance verification
- Export validation
- Edge case handling

**Quick Smoke Test (5 minutes):**
- [ ] App launches successfully
- [ ] Grant camera permission → dual previews appear
- [ ] Green ROI box on forehead (stable tracking)
- [ ] Enable Simulated Mode → Processing → ⚙️ Sim → PTT=120ms
- [ ] Results → Verify PTT ~120ms, Correlation >0.9
- [ ] Export → ZIP with 3 files created
- [ ] No crashes or errors

## Known Limitations

- Camera capture logic not yet implemented (placeholder)
- Signal processing algorithms are stubs
- Graph visualization is placeholder
- Export functionality not yet implemented
- Face detection integration pending

## Future Roadmap

1. Implement CameraX dual-camera capture
2. Integrate OpenCV for ROI extraction
3. Implement DSP algorithms in core-signal
4. Add ML Kit face detection for automatic ROI
5. Implement MPAndroidChart integration
6. Add data export functionality
7. Implement encrypted storage for results
8. Performance optimization and profiling
9. Expand device whitelist based on testing

## Troubleshooting

### Build Fails

- Ensure JDK 17 is installed and selected in Android Studio
- Clean and rebuild: `./gradlew clean build`
- Invalidate caches in Android Studio: **File > Invalidate Caches / Restart**

### App Crashes on Launch

- Check Logcat for error messages
- Verify minimum API 29 (Android 10) on test device
- Ensure camera permissions are granted

### Gradle Sync Issues

- Check internet connection (for initial dependency download)
- Update Gradle wrapper: `./gradlew wrapper --gradle-version 8.2`
- Delete `.gradle` folder and rebuild

## License

This project is proprietary and confidential.

## Contact

For questions or support, please contact the development team.

---

**Version**: 1.0.0  
**Last Updated**: November 4, 2025  
**Minimum Android Version**: 10 (API 29)  
**Target Android Version**: 14 (API 34)

