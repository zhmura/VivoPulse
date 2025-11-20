# VivoPulse — Quick Test Reference

## 🚀 Quick Start (2 minutes)

```bash
# 1. Install app
./gradlew installDebug

# 2. Launch app
adb shell am start -n com.vivopulse.app/.MainActivity

# 3. Monitor logs
adb logcat -s DualCameraController:* FaceRoiTracker:*
```

**On Device:**
1. Grant camera permission
2. Enable Simulated Mode (⚙️ → toggle)
3. Start Capture → verify PTT ≈ 110ms, Corr > 0.9
4. Export → verify ZIP created

---

## 📋 Essential Commands

### Build & Install
```bash
./gradlew assembleDebug              # Build APK
./gradlew installDebug               # Install to device
adb uninstall com.vivopulse.app      # Clean uninstall
```

### Launch & Control
```bash
# Launch app
adb shell am start -n com.vivopulse.app/.MainActivity

# Force stop
adb shell am force-stop com.vivopulse.app

# Clear data
adb shell pm clear com.vivopulse.app
```

### Logs
```bash
# All VivoPulse logs
adb logcat -s DualCameraController:* FaceRoiTracker:* ThreeALockManager:* FlickerMeter:*

# Performance logs
adb logcat -s FpsTracker:* PerformanceProfiler:*

# Errors only
adb logcat '*:E'

# Save to file
adb logcat > vivopulse_log.txt
```

### Files
```bash
# Pull telemetry
adb pull /sdcard/Android/data/com.vivopulse.app/files/telemetry/ ./

# Pull diagnostics
adb pull /sdcard/Android/data/com.vivopulse.app/files/verification/ ./

# Pull exports
adb pull /sdcard/Android/data/com.vivopulse.app/files/exports/ ./
```

---

## ✅ 5-Minute Smoke Test

1. **Install & Launch**
   ```bash
   ./gradlew installDebug
   adb shell am start -n com.vivopulse.app/.MainActivity
   ```

2. **Simulated Mode**
   - ⚙️ → Enable "Simulated Mode"
   - Start Capture → Processing → Results
   - ✓ PTT ≈ 110ms, Corr > 0.9, SQI > 70

3. **Export**
   - Export → ZIP created
   - ✓ 3 files: session.json, face_signal.csv, finger_signal.csv

4. **Verify Logs**
   ```bash
   adb logcat -d | grep -i error
   # Should be no critical errors
   ```

---

## 🎯 Live Capture Test (10 minutes)

1. **Setup**
   - Disable Simulated Mode
   - Good lighting, face 30-60cm from camera
   - Enable torch, finger covers lens

2. **Capture (30s)**
   - Start Capture → remain still
   - ✓ Green ROI on forehead
   - ✓ Both previews stable

3. **Results**
   - ✓ PTT: 80-150ms
   - ✓ Correlation > 0.7
   - ✓ SQI > 60

---

## 🔍 Performance Checks

### FPS Monitoring
```bash
adb logcat -s FpsTracker:*
```
**Target**: ≥28 fps, <5% drops

### Frame Processing
```bash
adb logcat -s DualCameraController:* | grep "processFrame"
```
**Target**: ≤3ms YUV→RGB+ROI, ≤10ms total

### Memory
```bash
adb shell dumpsys meminfo com.vivopulse.app | grep TOTAL
```
**Target**: <350 MB peak

---

## 🐛 Common Issues

### Black Preview
```bash
adb shell am force-stop com.android.camera
adb shell am start -S -n com.vivopulse.app/.MainActivity
```

### Permission Denied
```bash
adb shell pm grant com.vivopulse.app android.permission.CAMERA
```

### ROI Not Detected
- Check lighting (face well-lit, not backlit)
- Distance: 30-60cm
- Face camera directly

### Poor SQI
- Enable torch for finger
- Improve lighting for face
- Reduce motion
- Avoid fluorescent lights

---

## 📊 Instrumented Tests

```bash
# Performance diagnostics
./gradlew :app:connectedDebugAndroidTest \
  --tests "*.PerformanceDiagnosticsTest"

# Live smoke test
./gradlew :app:connectedDebugAndroidTest \
  --tests "*.LiveSmokeTest"

# All tests
./gradlew connectedDebugAndroidTest
```

---

## 📁 Device File Locations

```
/sdcard/Android/data/com.vivopulse.app/files/
├── telemetry/session_*.json       # Session metrics
├── verification/diagnostics.md    # Performance report
└── exports/session_*.zip          # Exported data
```

---

## 🏷️ Key Log Tags

- `DualCameraController` - Camera lifecycle
- `FaceRoiTracker` - ROI tracking
- `FpsTracker` - Frame rates
- `ThreeALockManager` - 3A lock
- `FlickerMeter` - Anti-flicker
- `SignalPipeline` - Processing
- `PttCalculator` - PTT computation

---

## 🎯 Acceptance Criteria

| Metric | Target | Measurement |
|--------|--------|-------------|
| FPS | ≥28 fps | `adb logcat -s FpsTracker:*` |
| Frame drops | <5% | FpsTracker logs |
| YUV→RGB+ROI | ≤3ms avg | PerformanceProfiler |
| Total processing | ≤10ms | DualCameraController |
| Memory (peak) | <350 MB | `dumpsys meminfo` |
| SQI (simulated) | >70 | Results screen |
| Correlation (sim) | >0.9 | Results screen |
| PTT (simulated) | 110±10ms | Results screen |

---

## 📞 Support

**Full Guide**: `docs/ON_DEVICE_TESTING_GUIDE.md`

**Collect Diagnostics**:
```bash
# Logs
adb logcat -d > vivopulse_full.txt

# Files
adb pull /sdcard/Android/data/com.vivopulse.app/files/ ./device_files/

# Device info
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk
```
