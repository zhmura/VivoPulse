# VivoPulse User Guide

**Version 1.0 (MVP)**  
**Last Updated: November 2025**

---

## Table of Contents

1. [Introduction](#introduction)
2. [Getting Started](#getting-started)
3. [Main Features](#main-features)
4. [Recording a Session](#recording-a-session)
5. [Understanding Your Results](#understanding-your-results)
6. [Advanced Features](#advanced-features)
7. [Data Export](#data-export)
8. [Troubleshooting](#troubleshooting)
9. [Safety & Disclaimer](#safety--disclaimer)

---

## Introduction

**VivoPulse** is a research-grade cardiovascular monitoring application that uses your device's cameras to measure pulse wave signals from two body sites simultaneously:
- **Face** (front camera) - Remote photoplethysmography (rPPG)
- **Finger** (back camera) - Contact photoplethysmography (PPG)

By capturing signals from both sites, the app calculates **Pulse Transit Time (PTT)**, a metric related to arterial stiffness and vascular health.

> ⚠️ **Important**: This app is for research and educational purposes only. It is NOT a medical device and should NOT be used for diagnosis or treatment decisions.

---

## Getting Started

### System Requirements

- **Android 8.0 (API 26)** or higher
- **Dual cameras** (front + back)
- **Accelerometer/IMU** (for motion detection)
- **Recommended**: Android 10+ for concurrent camera support
- **Storage**: ~50MB for app + additional space for session exports

### Initial Setup

1. **Install the APK**
   - Transfer `vivopulse-debug.apk` to your device
   - Enable "Install from Unknown Sources" in Settings
   - Tap the APK to install

2. **Grant Permissions**
   - **Camera**: Required for face and finger signal capture
   - **Storage**: Required for exporting session data
   - **Body Sensors**: Optional (for future heart rate sensor integration)

3. **Launch the App**
   - Open **VivoPulse** from your app drawer
   - The app will automatically detect your device's camera capabilities

---

## Main Features

### 1. **Dual-Site Signal Capture**
- Simultaneous face (rPPG) and finger (PPG) monitoring
- Real-time signal quality indicators
- Adaptive fallback modes for device compatibility

### 2. **Real-Time Quality Feedback**
- **Green**: High-quality signal
- **Yellow**: Acceptable signal with minor issues
- **Red**: Poor signal quality (adjust positioning)

### 3. **Smart Coach Tips**
- Real-time guidance to improve signal quality
- Examples:
  - "Hold finger firmly on camera"
  - "Reduce face movement"
  - "Check lighting conditions"

### 4. **Pulse Transit Time (PTT) Calculation**
- Measures time delay between face and finger pulse waves
- Typical range: 80-250ms
- Lower PTT may indicate increased arterial stiffness

### 5. **Signal Visualization**
- Live waveform display for face and finger channels
- Color-coded quality indicators
- FPS and sync drift monitoring

---

## Recording a Session

### Preparation

1. **Find a Well-Lit Area**
   - Good ambient lighting is crucial for face signal quality
   - Avoid harsh shadows or direct sunlight

2. **Position Yourself**
   - Sit comfortably at arm's length from the device
   - Ensure your face is centered in the front camera view
   - Prepare your finger for placement on the back camera

3. **Minimize Movement**
   - Stay as still as possible during recording
   - Avoid talking, chewing, or sudden movements
   - Place device on a stable surface if possible

### Step-by-Step Recording

#### **Step 1: Launch Capture Screen**
- From the main menu, tap **"Start Capture"**
- The app will initialize both cameras

#### **Step 2: Camera Mode Detection**
The app automatically selects the best mode for your device:
- **Concurrent Mode**: Both cameras run simultaneously (recommended)
  - 🔦 Torch auto-enables for optimal finger signal
- **Sequential Mode**: Cameras alternate (fallback for older devices)
- **Safe Mode**: Reduced resolution or analysis-only (compatibility mode)

#### **Step 3: Position Your Finger**
- Place your **index or middle finger** flat against the **back camera lens**
- Cover the lens completely but don't press too hard
- You should see the **torch light** (flashlight) illuminate your finger
- Wait for the **finger signal quality** to turn **GREEN** or **YELLOW**

#### **Step 4: Check Signal Quality**
Before starting, verify:
- ✅ **Face Quality**: Green (steady face, good lighting)
- ✅ **Finger Quality**: Green/Yellow (torch on, finger covers lens)
- ✅ **FPS**: 15-30 fps on both channels
- ✅ **Drift**: < ±2ms/s (indicates good camera sync)

#### **Step 5: Start Recording**
- Tap the **"Start Recording"** button (turns RED)
- The timer will start counting
- **Recommended duration**: 30-60 seconds
  - Minimum: 15 seconds
  - Maximum: 120 seconds (2 minutes)

#### **Step 6: During Recording**
- **Stay still** and breathe normally
- **Keep finger** firmly on the back camera
- **Watch the Smart Coach** tips at the top
  - Green ✓: "Signal quality excellent"
  - Yellow ⚠: "Reduce motion" or "Adjust finger pressure"
  - Red ✗: "Poor finger contact" (reposition immediately)

#### **Step 7: Stop Recording**
- Tap **"Stop Recording"** (button turns GREEN)
- The app will save your session data
- You'll automatically navigate to the **Processing** screen

### Torch (Flashlight) Control

- **Auto-enabled** in Concurrent Mode for optimal finger signal
- **Manual toggle** available (🔦 button)
- **Safety timeout**: Torch auto-disables after 60 seconds to prevent overheating
- **Tip**: If torch disables during recording, manually re-enable it

---

## Understanding Your Results

After recording, the **Processing Screen** displays your cardiovascular metrics:

### Primary Metrics

#### **1. Pulse Transit Time (PTT)**
- **What it is**: Time for pulse wave to travel from face to finger
- **Typical range**: 80-250 ms
- **Lower values**: May indicate increased arterial stiffness (less elastic arteries)
- **Higher values**: May indicate more compliant arteries

> 📘 **Note**: PTT is influenced by blood pressure, arterial stiffness, and heart-vessel distance. It is NOT a direct measurement of blood pressure.

#### **2. Correlation Score**
- **Range**: 0.0 to 1.0
- **> 0.7**: High-quality PTT measurement (reliable)
- **0.4-0.7**: Moderate quality (use with caution)
- **< 0.4**: Low quality (measurement may be unreliable)

#### **3. Signal Quality Index (SQI)**
- **Face SQI**: Quality of rPPG signal from face (0-100)
- **Finger SQI**: Quality of PPG signal from finger (0-100)
- **> 80**: Excellent
- **60-80**: Good
- **40-60**: Fair (borderline, may apply wavelet denoising)
- **< 40**: Poor

### Advanced Metrics (Displayed in Export)

#### **4. Harmonic Features**
- **Fundamental Frequency**: Heart rate in Hz (e.g., 1.2 Hz = 72 BPM)
- **H2/H1 Ratio**: Second harmonic amplitude relative to fundamental
  - Higher values may indicate stronger pulse wave reflections
- **Spectral Entropy**: Signal complexity (lower = more periodic)

#### **5. PTT Diagnostics**
- **PTT Mean (Raw)**: PTT from standard bandpass filter
- **PTT Mean (Denoised)**: PTT from wavelet-denoised signal (if SQI 40-80)
  - Comparison shows denoising effectiveness

---

## Advanced Features

### 1. **Sequential Mode (Manual)**
For devices with limited concurrent camera support:
- Tap **"Switch Primary"** to alternate between Face-first or Finger-first capture
- Torch auto-disables when Face is primary (not needed)

### 2. **Wavelet Denoising**
- **Automatically applied** when SQI is 40-80 (borderline quality)
- Uses Haar (DB1) wavelet transform to reduce noise
- Compare "Raw" vs "Denoised" PTT in export for effectiveness

### 3. **Walking Mode** (Experimental - Dev Flag)
- Adaptive notch filter to suppress step-related artifacts
- Requires IMU/accelerometer data
- Enable via `FeatureFlags.walkingModeEnabled = true` in code

### 4. **Time-Frequency Analysis** (Compile-Time Flag)
- Exports STFT (Short-Time Fourier Transform) spectrograms
- For offline ML/research analysis
- Enable via `FeatureFlags.ENABLE_TF_EXPORT = true` in code

---

## Data Export

### Exporting a Session

1. **Complete Processing**
   - After recording, wait for the Processing screen to complete
   - PTT and quality metrics will be displayed

2. **Tap "Export"**
   - The app will create an encrypted ZIP file
   - Location: `/sdcard/vivopulse/session-{datetime}.zip`

3. **Find Your Export**
   - Use a file manager to navigate to `/sdcard/vivopulse/`
   - File naming: `session-2025-11-29_14-30-45.zip`

### Export Contents

The ZIP file contains:

#### **1. `session.json`** (Metadata & Metrics)
```json
{
  "session_id": "uuid",
  "start_time_ms": 1732888245000,
  "duration_s": 45.2,
  "ptt_ms": 142.5,
  "correlation": 0.82,
  "sqi_face": 85,
  "sqi_finger": 78,
  "harmonics_summary": {
    "face": { "fundamental_hz": 1.15, "h2_h1_ratio": 0.32, ... },
    "finger": { ... }
  },
  "segments": [
    {
      "start_time_s": 0.0,
      "end_time_s": 45.2,
      "ptt_ms": 142.5,
      "ptt_mean_raw": 142.5,
      "ptt_mean_denoised": 141.8,
      "harmonics_face": { ... },
      "harmonics_finger": { ... }
    }
  ],
  "processing_params": {
    "sample_rate_hz": 30,
    "bandpass": "0.7-4.0 Hz, Butterworth order 4",
    ...
  }
}
```

#### **2. `face_signal.csv`** (Face rPPG Signal)
```
timestamp_ns,luma,sqi
1732888245000000,128.5,85
1732888245033333,130.2,86
...
```

#### **3. `finger_signal.csv`** (Finger PPG Signal)
```
timestamp_ns,luma,sqi,saturation_pct
1732888245000000,142.1,78,5.2
...
```

#### **4. `device_info.json`** (Device Capabilities)
- Camera specs, concurrent support, thermal state

#### **5. `fps_report.json`** (Performance Metrics)
- Frame rates, dropped frames, processing latency

#### **6. `tf_spectrogram.csv`** (Optional, if enabled)
- Time-frequency analysis for ML research

### Decryption (For Researchers)

The ZIP is encrypted using **AndroidX Security Crypto** with AES256.
- Password: Set in `ClinicianGradeExporter.kt` (default: device-specific key)
- Use standard ZIP tools (7-Zip, unzip, etc.) to decrypt

---

## Troubleshooting

### Issue: "No Concurrent Camera Support"

**Symptoms**: App shows "Safe Mode: Sequential camera operation"

**Solutions**:
- Your device doesn't support simultaneous dual cameras
- Use **Sequential Mode** instead (tap "Switch Primary" to alternate)
- This is normal for devices pre-2020 or budget models

---

### Issue: "Torch Disabled After 60 Seconds"

**Symptoms**: Torch turns off mid-recording

**Solutions**:
- This is a **safety feature** to prevent overheating
- Manually re-enable torch by tapping 🔦 button
- For long sessions, pause briefly to let torch cool down

---

### Issue: "Poor Finger Signal Quality (Red)"

**Symptoms**: Finger SQI < 40, red indicator

**Solutions**:
- ✅ **Cover lens completely** with your fingertip
- ✅ **Check torch** is enabled (🔦 icon should show "Torch On")
- ✅ **Adjust pressure**: Firm but not crushing
- ✅ **Try different finger**: Index or middle finger usually best
- ✅ **Clean camera lens**: Remove fingerprints/dirt

---

### Issue: "Face Motion Detected"

**Symptoms**: Yellow/Red face quality, "Reduce movement" tip

**Solutions**:
- ✅ **Stay still**: Avoid talking, chewing, or nodding
- ✅ **Rest device** on a table or stable surface
- ✅ **Relax**: Tense facial muscles can affect signal
- ✅ **Check lighting**: Ensure even, diffused lighting (no harsh shadows)

---

### Issue: "High Camera Drift (> ±5ms/s)"

**Symptoms**: Drift indicator shows large positive/negative values

**Solutions**:
- This indicates **camera timestamp desynchronization**
- More common on older devices or in Safe Mode
- **Impact**: May reduce PTT accuracy (especially < 10ms drift)
- **Workaround**: Use longer recording sessions (60s+) to average out drift

---

### Issue: "Export Failed"

**Symptoms**: Export button does nothing or shows error

**Solutions**:
- ✅ **Check storage permissions**: Settings → Apps → VivoPulse → Permissions
- ✅ **Free up space**: Ensure at least 100MB free storage
- ✅ **Check logs**: Use `adb logcat` to see detailed error

---

### Issue: "App Crashes on Start"

**Symptoms**: App closes immediately after launch

**Solutions**:
- ✅ **Clear app cache**: Settings → Apps → VivoPulse → Storage → Clear Cache
- ✅ **Reinstall**: Uninstall and reinstall the APK
- ✅ **Check Android version**: Requires Android 8.0 (API 26) or higher
- ✅ **Report bug**: Check logcat for crash details

---

## Safety & Disclaimer

### Medical Disclaimer

**VivoPulse is NOT a medical device.** It is intended for:
- ✅ Research and educational purposes
- ✅ Wellness monitoring (non-clinical)
- ✅ Algorithm development and validation

**DO NOT use VivoPulse for**:
- ❌ Medical diagnosis
- ❌ Treatment decisions
- ❌ Emergency situations
- ❌ Blood pressure measurement (it does NOT measure BP)

### Safety Precautions

1. **Torch Usage**
   - The app limits torch to 60 seconds to prevent overheating
   - Prolonged torch use can warm the device and lens
   - If device feels hot, stop and let it cool down

2. **Camera Lens Contact**
   - Clean lens before/after use
   - Avoid excessive pressure on lens
   - Don't use if lens is cracked or damaged

3. **Data Privacy**
   - Exported data includes face/finger signal but NO IMAGES
   - Session files are encrypted
   - Data stays on your device unless you share it

4. **Light Sensitivity**
   - If you experience discomfort from torch light, reduce brightness or stop use
   - Not recommended for users with photosensitive conditions

---

## Technical Support & Feedback

For bug reports, feature requests, or technical questions:
- **GitHub Issues**: [Repository URL]
- **Email**: support@vivopulse.example.com
- **Logs**: Use `adb logcat -s VivoPulse` for debug output

---

## Appendix: Feature Flags (Developers)

### Runtime Flags
Located in `app/src/main/java/com/vivopulse/app/util/FeatureFlags.kt`:

```kotlin
object FeatureFlags {
    var walkingModeEnabled: Boolean = false // Enable walking mode with adaptive notch
}
```

### Compile-Time Flags
```kotlin
const val ENABLE_TF_EXPORT = false // Enable Time-Frequency spectrogram export
```

Change these in code and rebuild APK to enable experimental features.

---

## Changelog

### v1.0 (November 2025) - MVP Release
- ✅ Dual-site PPG capture (face + finger)
- ✅ Pulse Transit Time (PTT) calculation
- ✅ Real-time quality feedback and Smart Coach
- ✅ Harmonic feature extraction (H2/H1, spectral entropy)
- ✅ Wavelet denoising (Haar/DB1) for borderline signals
- ✅ Encrypted clinician-grade export (ZIP)
- ✅ Concurrent/Sequential camera modes with adaptive fallback
- ✅ Torch auto-enable in concurrent mode
- ✅ IMU motion detection and drift monitoring

---

**Thank you for using VivoPulse!**  
_Advancing cardiovascular research, one pulse at a time._

