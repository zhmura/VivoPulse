# VivoPulse — On-Device Testing Guide

## Overview

This guide provides comprehensive instructions for testing the VivoPulse app on physical Android devices. It covers device setup, build installation, testing procedures, performance profiling, and diagnostics collection.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Device Setup](#device-setup)
3. [Building and Installing](#building-and-installing)
4. [Testing Procedures](#testing-procedures)
5. [Performance Profiling](#performance-profiling)
6. [Diagnostics and Logs](#diagnostics-and-logs)
7. [Troubleshooting](#troubleshooting)
8. [Advanced Testing](#advanced-testing)

---

## Prerequisites

### Development Environment

- **Android Studio**: Hedgehog (2023.1.1) or later
- **JDK**: Java 17
- **Android SDK**: API 29-34 installed
- **ADB**: Android Debug Bridge (included with Android Studio)

### Supported Test Devices

#### Tier 1 (Recommended)
- **Google Pixel**: 6, 6 Pro, 7, 7 Pro, 8, 8 Pro
- **Samsung Galaxy S**: S22, S23, S24 (all variants)

#### Tier 2 (Compatible)
- Any Android device with:
  - Android 10+ (API 29+)
  - Dual cameras (front + back)
  - 4GB+ RAM
  - Camera2 API support

> **Note**: The app will display a warning banner on non-whitelisted devices but can still be tested in Simulated Mode.

---

## Device Setup

### 1. Enable Developer Options

1. Open **Settings** on your Android device
2. Navigate to **About phone**
3. Tap **Build number** 7 times
4. Enter your PIN/password if prompted
5. Developer options are now enabled

### 2. Enable USB Debugging

1. Go to **Settings → System → Developer options**
2. Enable **USB debugging**
3. Enable **Install via USB** (if available)
4. Enable **USB debugging (Security settings)** (if available on Samsung devices)

### 3. Connect Device to Computer

1. Connect device via USB cable
2. On the device, tap **Allow** when prompted to allow USB debugging
3. Check the box **Always allow from this computer** (recommended)

### 4. Verify ADB Connection

```bash
# List connected devices
adb devices

# Expected output:
# List of devices attached
# ABC123XYZ    device
```

If your device shows as "unauthorized", disconnect and reconnect the USB cable, then accept the prompt on the device.

---

## Building and Installing

### Option 1: Android Studio (Recommended)

#### Quick Install

1. Open the VivoPulse project in Android Studio
2. Select **Run → Select Device** and choose your connected device
3. Click the **Run** button (▶) or press `Shift+F10`
4. Wait for Gradle build and installation to complete
5. The app will launch automatically

#### Build Variants

- **Debug**: Includes Simulated Mode and debug menu
  - `BuildConfig.ENABLE_SIMULATED_MODE = true`
  - Debuggable, no obfuscation
- **Release**: Production build
  - ProGuard optimization enabled
  - No simulated mode

To switch variants:
1. **Build → Select Build Variant**
2. Choose **debug** or **release** for the `app` module

### Option 2: Command Line

#### Build Debug APK

```bash
# Navigate to project root
cd /path/to/VivoPulse

# Build debug APK
./gradlew assembleDebug

# APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

#### Install Debug APK

```bash
# Install to connected device
./gradlew installDebug

# Or use adb directly
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### Launch App

```bash
# Start the app
adb shell am start -n com.vivopulse.app/.MainActivity

# Or with intent flags
adb shell am start -S -n com.vivopulse.app/.MainActivity
```

#### Uninstall (Clean Install)

```bash
# Uninstall existing app
adb uninstall com.vivopulse.app

# Then reinstall
./gradlew installDebug
```

---

## Testing Procedures

### Quick Smoke Test (5 minutes)

This test verifies basic functionality:

```bash
# 1. Install and launch
./gradlew installDebug
adb shell am start -n com.vivopulse.app/.MainActivity
```

**On Device:**

1. **Launch & Permissions**
   - [ ] App launches without crash
   - [ ] Grant camera permission when prompted
   - [ ] Both camera previews appear (front + back)

2. **ROI Tracking (Face Camera)**
   - [ ] Green ROI box appears on forehead
   - [ ] ROI tracks face movement smoothly
   - [ ] ROI state shows "Stable" (not "Re-acquiring" or "Lost")

3. **Simulated Mode Test**
   - [ ] Tap settings icon (⚙️) in top-right
   - [ ] Enable "Simulated Mode" toggle
   - [ ] Banner shows "Simulated Mode Active"
   - [ ] Tap "Start Capture" → Processing screen appears
   - [ ] Processing completes → Results screen appears
   - [ ] Verify PTT ≈ 110-120ms
   - [ ] Verify Correlation > 0.9
   - [ ] Verify Combined SQI > 70

4. **Export**
   - [ ] Tap "Export" button
   - [ ] ZIP file created successfully
   - [ ] Verify 3 files in ZIP: `session.json`, `face_signal.csv`, `finger_signal.csv`

5. **Stability**
   - [ ] No crashes or ANRs
   - [ ] No error dialogs
   - [ ] Check logcat for errors (see [Diagnostics](#diagnostics-and-logs))

### Live Capture Test (10 minutes)

Test with real camera capture (requires good lighting):

**On Device:**

1. **Disable Simulated Mode**
   - Settings → Disable "Simulated Mode"

2. **Face Camera Setup**
   - Position face 30-60cm from front camera
   - Ensure good lighting (avoid backlighting)
   - Keep head still
   - Verify green ROI box on forehead

3. **Finger Camera Setup**
   - Enable torch (flashlight icon)
   - Place finger over back camera lens
   - Cover lens completely
   - Apply gentle, steady pressure

4. **Capture Session (30 seconds)**
   - Tap "Start Capture"
   - Remain still for 30 seconds
   - Breathe normally
   - Avoid talking or moving

5. **Verify Results**
   - [ ] Processing completes without errors
   - [ ] PTT value displayed (typically 80-150ms)
   - [ ] Correlation > 0.7 (good quality)
   - [ ] Combined SQI > 60 (acceptable)
   - [ ] Face and finger waveforms visible

6. **Quality Indicators**
   - **Good**: SQI > 70, Correlation > 0.8
   - **Acceptable**: SQI 60-70, Correlation 0.7-0.8
   - **Poor**: SQI < 60, Correlation < 0.7

### Concurrent Camera Test

Verify dual-camera operation:

```bash
# Check device capabilities
adb shell am start -n com.vivopulse.app/.MainActivity

# Monitor logcat for concurrent camera support
adb logcat -s DeviceProbe:* DualCameraController:*
```

**Expected Log Output:**

```
DeviceProbe: Device probe complete: Samsung SM-G998B (API 33)
DeviceProbe: Concurrent support: true, Recommended mode: CONCURRENT
DualCameraController: Cameras started successfully in mode CONCURRENT
```

**Test Scenarios:**

1. **Concurrent Mode** (if supported)
   - [ ] Both previews update simultaneously
   - [ ] Frame rates stable (≥25 fps each)
   - [ ] No frame drops or jank

2. **Sequential Mode** (fallback)
   - [ ] Banner shows "Safe Mode: Sequential camera operation"
   - [ ] Cameras alternate capture
   - [ ] Processing still completes successfully

### Orientation Test

Verify ROI stability during rotation:

> **Note**: App is locked to portrait mode, but device rotation sensors may still trigger events.

1. Start capture session
2. Slowly rotate device left/right (±15°)
3. Verify:
   - [ ] ROI remains on forehead
   - [ ] ROI bounds stay within frame
   - [ ] No "ROI: Lost" state
   - [ ] Tracking recovers quickly

### Thermal Test

Test thermal throttling behavior:

1. Run multiple 30-second capture sessions back-to-back
2. Enable torch for all sessions
3. Monitor for thermal warnings:
   - [ ] "Device is warm" banner appears (moderate thermal state)
   - [ ] Torch auto-disables on critical thermal state
   - [ ] Capture stops on emergency thermal state

```bash
# Monitor thermal state
adb logcat -s ThermalGuard:* DualCameraController:*
```

---

## Performance Profiling

### Frame Rate Monitoring

Monitor real-time FPS for both cameras:

```bash
# Filter for FPS logs
adb logcat -s FpsTracker:*
```

**Expected Output:**

```
FpsTracker: [FACE] Current FPS: 29.8, Avg: 29.5, Dropped: 2/450
FpsTracker: [FINGER] Current FPS: 30.1, Avg: 29.8, Dropped: 1/451
```

**Acceptance Criteria:**
- Average FPS ≥ 28 fps (target: 30 fps)
- Frame drops < 5% of total frames
- No sustained jank (consecutive drops)

### Processing Latency

Measure frame processing time:

```bash
# Monitor frame processing
adb logcat -s DualCameraController:* | grep "processFrame"
```

**Target Metrics:**
- YUV→RGB+ROI processing: ≤ 3ms average
- Total frame processing: ≤ 10ms
- Jank threshold: < 5% frames exceed 15ms

### Memory Profiling

Monitor memory usage during capture:

```bash
# Check memory stats
adb shell dumpsys meminfo com.vivopulse.app

# Monitor in real-time
watch -n 1 'adb shell dumpsys meminfo com.vivopulse.app | grep TOTAL'
```

**Expected Memory Usage:**
- Idle: 80-120 MB
- During capture: 150-250 MB
- Peak (processing): 250-350 MB

### Battery Impact

Monitor battery drain during capture:

```bash
# Check battery stats
adb shell dumpsys batterystats com.vivopulse.app

# Reset battery stats
adb shell dumpsys batterystats --reset
```

**Expected Drain:**
- Capture (30s): ~1-2% battery
- With torch: ~2-3% battery

---

## Diagnostics and Logs

### Collecting Logcat

#### Full Logcat

```bash
# Capture all logs to file
adb logcat -v time > vivopulse_logcat.txt

# Stop with Ctrl+C after reproducing issue
```

#### Filtered Logcat (Recommended)

```bash
# VivoPulse-specific logs only
adb logcat -s \
  DualCameraController:* \
  FaceRoiTracker:* \
  ThreeALockManager:* \
  FlickerMeter:* \
  DeviceProbe:* \
  SignalPipeline:* \
  PttCalculator:* \
  TelemetryLogger:* \
  > vivopulse_filtered.txt
```

#### Real-time Monitoring

```bash
# Monitor specific components
adb logcat -s DualCameraController:D FaceRoiTracker:D
```

### Crash Logs

If the app crashes:

```bash
# View crash stack trace
adb logcat -s AndroidRuntime:E

# Or use logcat with buffer
adb logcat -b crash
```

### Performance Diagnostics

Run instrumented performance test:

```bash
# Run performance diagnostics test
./gradlew :app:connectedDebugAndroidTest \
  --tests "com.vivopulse.app.PerformanceDiagnosticsTest"

# Results location:
# app/build/outputs/androidTest-results/connected/
```

**Generated Artifacts:**
- `diagnostics.md` - Performance summary
- `face_filtered.png` - Face signal plot
- `finger_filtered.png` - Finger signal plot

**View on Device:**

```bash
# Pull diagnostics from device
adb pull /sdcard/Android/data/com.vivopulse.app/files/verification/ ./verification/

# Open diagnostics.md
cat verification/diagnostics.md
```

### Telemetry Logs

Export session telemetry:

```bash
# Pull telemetry logs
adb pull /sdcard/Android/data/com.vivopulse.app/files/telemetry/ ./telemetry/

# View latest session
ls -lt telemetry/ | head -n 5
cat telemetry/session_YYYYMMDD_HHMMSS.json
```

**Telemetry Contents:**
- Device info (manufacturer, model, Android version)
- Session metrics (duration, FPS, drift)
- Quality metrics (SQI, correlation)
- PTT metrics (lag, stability)
- Issues and warnings

---

## Troubleshooting

### App Won't Install

**Error: `INSTALL_FAILED_UPDATE_INCOMPATIBLE`**

```bash
# Uninstall existing app first
adb uninstall com.vivopulse.app
./gradlew installDebug
```

**Error: `INSTALL_FAILED_INSUFFICIENT_STORAGE`**

```bash
# Check available storage
adb shell df /data

# Clear app cache
adb shell pm clear com.vivopulse.app
```

### Camera Permission Denied

```bash
# Grant camera permission manually
adb shell pm grant com.vivopulse.app android.permission.CAMERA

# Verify permissions
adb shell dumpsys package com.vivopulse.app | grep permission
```

### Black Camera Preview

**Possible Causes:**
1. Camera in use by another app
2. Camera2 API not supported
3. Concurrent camera not supported

**Debug Steps:**

```bash
# Check camera availability
adb shell dumpsys media.camera

# Kill other camera apps
adb shell am force-stop com.android.camera

# Restart VivoPulse
adb shell am start -S -n com.vivopulse.app/.MainActivity
```

### ROI Not Detected

**Face ROI Issues:**

1. **Lighting**: Ensure face is well-lit (not backlit)
2. **Distance**: Position face 30-60cm from camera
3. **Angle**: Face camera directly (not at angle)
4. **ML Kit**: Check logcat for face detection errors

```bash
adb logcat -s FaceRoiTracker:*
```

**Finger ROI Issues:**

1. **Coverage**: Ensure finger completely covers lens
2. **Pressure**: Apply gentle, steady pressure
3. **Torch**: Enable torch for better signal

### Poor Signal Quality (Low SQI)

**Troubleshooting Steps:**

1. **Improve Lighting**
   - Face: Use natural or bright indoor lighting
   - Finger: Enable torch

2. **Reduce Motion**
   - Keep head still
   - Rest elbow on table for finger
   - Breathe normally (don't hold breath)

3. **Check Placement**
   - Face: ROI on forehead (not cheeks)
   - Finger: Full lens coverage

4. **Environmental Factors**
   - Avoid fluorescent lighting (50/60 Hz flicker)
   - Avoid direct sunlight (too bright)
   - Minimize ambient motion

### High Frame Drops

```bash
# Monitor frame drops
adb logcat -s FpsTracker:* DualCameraController:*
```

**Common Causes:**

1. **Thermal Throttling**: Device overheating
   - Let device cool down
   - Disable torch if not needed

2. **Background Apps**: High CPU usage
   ```bash
   # Close background apps
   adb shell am kill-all
   ```

3. **Low Memory**: Insufficient RAM
   ```bash
   # Clear app cache
   adb shell pm clear com.vivopulse.app
   ```

4. **Resolution Too High**: Fallback to lower resolution
   - App will auto-fallback if needed
   - Check for "Safe Mode: Reduced resolution" banner

### Logcat Too Verbose

```bash
# Reduce log verbosity
adb logcat -s '*:W'  # Warnings and errors only

# Or filter by priority
adb logcat '*:E'  # Errors only
adb logcat '*:I'  # Info and above
```

---

## Advanced Testing

### Instrumented Test Suite

Run full instrumented test suite:

```bash
# Run all instrumented tests
./gradlew connectedDebugAndroidTest

# Run specific test class
./gradlew connectedDebugAndroidTest \
  --tests "com.vivopulse.app.LiveSmokeTest"

# Run specific test method
./gradlew connectedDebugAndroidTest \
  --tests "com.vivopulse.app.LiveSmokeTest.testSimulatedCapture"
```

**Available Test Suites:**

1. **LiveSmokeTest**: Basic app flow validation
2. **PerformanceDiagnosticsTest**: Performance profiling
3. **SimE2ETest**: End-to-end simulated mode test
4. **AssetsE2ETest**: Asset validation test

### Profiling with Android Profiler

1. Open Android Studio
2. **View → Tool Windows → Profiler**
3. Select your device and app
4. Record a capture session
5. Analyze:
   - **CPU**: Frame processing time
   - **Memory**: Heap allocations
   - **Network**: Should be zero (offline app)

### Systrace Profiling

Capture system-level trace:

```bash
# Start systrace
python $ANDROID_HOME/platform-tools/systrace/systrace.py \
  -o vivopulse_trace.html \
  -t 30 \
  -a com.vivopulse.app \
  camera view

# Open vivopulse_trace.html in Chrome
```

### ADB Screenrecord

Record test session:

```bash
# Record screen (max 3 minutes)
adb shell screenrecord /sdcard/vivopulse_test.mp4

# Stop with Ctrl+C

# Pull video
adb pull /sdcard/vivopulse_test.mp4
```

### Monkey Testing

Stress test with random inputs:

```bash
# Run monkey test (1000 events)
adb shell monkey -p com.vivopulse.app -v 1000

# With seed for reproducibility
adb shell monkey -p com.vivopulse.app -s 42 -v 1000
```

### Network Verification

Verify app is truly offline:

```bash
# Monitor network activity (should be none)
adb shell tcpdump -i any -s 0 -w /sdcard/capture.pcap

# Or use Android Profiler Network tab
```

---

## Test Checklist

### Pre-Release Validation

- [ ] **Build**
  - [ ] Debug build installs successfully
  - [ ] Release build installs successfully
  - [ ] No ProGuard warnings in release build

- [ ] **Permissions**
  - [ ] Camera permission requested on first launch
  - [ ] Permission denial handled gracefully
  - [ ] No storage permission requested (scoped storage)

- [ ] **Device Compatibility**
  - [ ] Whitelisted device: No warning banner
  - [ ] Non-whitelisted device: Warning banner shown
  - [ ] Simulated mode works on all devices

- [ ] **Camera Capture**
  - [ ] Concurrent mode on supported devices
  - [ ] Sequential fallback on unsupported devices
  - [ ] Both previews render correctly
  - [ ] Frame rates stable (≥28 fps)

- [ ] **ROI Tracking**
  - [ ] Face ROI detected and stable
  - [ ] Finger ROI calculated correctly
  - [ ] ROI survives orientation changes
  - [ ] ROI overlay renders correctly

- [ ] **Signal Processing**
  - [ ] Simulated mode: PTT ≈ 110ms, Corr > 0.9
  - [ ] Live capture: PTT 80-150ms, Corr > 0.7
  - [ ] SQI calculated correctly
  - [ ] Processing completes in < 5 seconds

- [ ] **Export**
  - [ ] ZIP file created successfully
  - [ ] session.json valid JSON
  - [ ] CSV files contain data
  - [ ] Files encrypted (if enabled)

- [ ] **Performance**
  - [ ] No ANRs during capture
  - [ ] No memory leaks
  - [ ] Battery drain acceptable (< 3% per 30s)
  - [ ] Thermal throttling handled gracefully

- [ ] **Stability**
  - [ ] No crashes in 10 consecutive sessions
  - [ ] Monkey test passes (1000 events)
  - [ ] App survives background/foreground transitions

---

## Quick Reference

### Essential ADB Commands

```bash
# Install app
./gradlew installDebug

# Launch app
adb shell am start -n com.vivopulse.app/.MainActivity

# View logs
adb logcat -s DualCameraController:*

# Pull files
adb pull /sdcard/Android/data/com.vivopulse.app/files/ ./device_files/

# Clear app data
adb shell pm clear com.vivopulse.app

# Uninstall
adb uninstall com.vivopulse.app
```

### Log Tags to Monitor

- `DualCameraController` - Camera lifecycle, frame processing
- `FaceRoiTracker` - Face detection, ROI tracking
- `ThreeALockManager` - 3A lock state, variance tracking
- `FlickerMeter` - Flicker detection, anti-flicker
- `SignalPipeline` - Signal processing, filtering
- `PttCalculator` - PTT computation, cross-correlation
- `TelemetryLogger` - Session metrics, telemetry

### File Locations on Device

```
/sdcard/Android/data/com.vivopulse.app/files/
├── telemetry/              # Session telemetry logs
│   └── session_*.json
├── verification/           # Performance diagnostics
│   ├── diagnostics.md
│   └── plots/
│       ├── face_filtered.png
│       └── finger_filtered.png
└── exports/                # Exported session data
    └── session_*.zip
```

---

## Support

For issues or questions:

1. Check [Troubleshooting](#troubleshooting) section
2. Review logcat output
3. Collect diagnostics (see [Diagnostics and Logs](#diagnostics-and-logs))
4. Contact development team with:
   - Device model and Android version
   - Build variant (debug/release)
   - Logcat output
   - Steps to reproduce

---

**Last Updated**: 2025-11-20  
**App Version**: 1.0.0  
**Min Android**: 10 (API 29)  
**Target Android**: 14 (API 34)
