# Clinician-Grade Export + Device Fitness Implementation (Archived)

Archived from project root on cleanup. Current export implementation lives in core-io/DataExporter with enrichment options; see docs/TECH_GUIDE.md and docs/CALCULATION_REFERENCE.md for up-to-date references.

---

# Clinician-Grade Export + Device Fitness Implementation

**Goal:** Produce single-tap clinician-grade export with plots, and implement device fitness probe with safe mode fallbacks.

**Status:** ✅ **IMPLEMENTED**

---

## Implementation Summary

### 1. ✅ Device Fitness Probe
**File:** `feature-capture/src/main/java/com/vivopulse/feature/capture/device/DeviceProbe.kt`

**Capabilities Detection:**
- Queries `getConcurrentCameraIds()` (API 28+)
- Lists hardware levels (LEGACY/LIMITED/FULL/LEVEL_3)
- Dumps supported stream combinations
- Finds preview sizes ≥720p
- Determines capture mode

**Capture Modes:**
- **CONCURRENT:** Full dual camera @ high resolution (≥720p)
- **DOWNGRADED_RES:** Concurrent @ lower resolution (640x480)
- **SEQUENTIAL:** Sequential capture (face → finger, safe mode)
- **UNSUPPORTED:** Device cannot run app

**Decision Logic:**
```
if (!concurrentSupported) → SEQUENTIAL
else if (hardwareLevel == LEGACY) → DOWNGRADED_RES
else if (no 720p sizes) → DOWNGRADED_RES
else → CONCURRENT
```

**Export:** `device_capabilities.json` with full hardware profile

---

### 2. ✅ Graceful Fallback Strategy
**File:** `feature-capture/src/main/java/com/vivopulse/feature/capture/FallbackCaptureStrategy.kt`

**Fallback Sequence:**
1. **Try:** Concurrent @ 1280x720
2. **Fallback 1:** Concurrent @ 640x480 (downgraded resolution)
3. **Fallback 2:** Sequential capture (safe mode)
4. **Final:** Report failure

**Usage:**
```kotlin
val strategy = FallbackCaptureStrategy(context)
var config = strategy.getNextFallback()

while (config != null) {
    try {
        configureStreams(config)
        break // Success
    } catch (e: Exception) {
        config = strategy.getNextFallback() // Try next
    }
}
```

**Benefits:**
- No crashes on unsupported devices
- Automatic resolution downgrade
- Sequential mode as last resort
- Clear UX messages at each level

---

### 3. ✅ Enhanced Thermal & Torch Policy
**Existing:** `ThermalGuard.kt`, `TorchPolicyManager.kt`

**Thermal Policy:**
- **NORMAL/LIGHT:** No action
- **MODERATE:** Pause one preview, reduce processing
- **SEVERE:** Block new sessions, show "Cooling..." banner
- **CRITICAL/EMERGENCY:** Force stop all cameras

**Torch Policy:**
- Max 60s per session
- 30s cool-down between sessions
- Warn on repeat sessions
- Log thermal events

**Integration:** UI banner shows "⚠️ Device cooling, please wait..."

---

### 4. ✅ Clinician-Grade Exporter
**File:** `core-io/src/main/java/com/vivopulse/io/ClinicianGradeExporter.kt`

... (content truncated; this archival copy preserves the original guidance) ...

