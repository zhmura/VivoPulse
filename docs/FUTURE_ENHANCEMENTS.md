# Future Enhancements & Roadmap

This document outlines planned enhancements for VivoPulse, organized by priority and implementation complexity.

---

## High Priority (Quality & Robustness)

### 1. IMU Sensor Integration
**Status:** Framework ready, sensors not wired  
**Effort:** 8-12 hours  
**Impact:** Significantly improves motion detection accuracy

**Current State:**
- `ImuMotionGate` class exists with placeholder methods
- Parameters defined but not connected to Android SensorManager
- Motion detection relies only on optical flow (visual)

**Implementation:**
- Wire Android `SensorManager` to capture accelerometer/gyroscope data
- Implement `detectSteps()` for heel-strike detection (walking)
- Fuse IMU + optical flow for robust motion classification
- Add IMU trace to export for validation

**Benefits:**
- Detect motion that optical flow misses (phone held steady but body moving)
- Better rejection of walking/exercise artifacts
- More accurate motion penalties in SQI

**Files to modify:**
- `app/src/main/java/com/vivopulse/app/sensors/ImuCollector.kt` (new)
- `feature-processing/.../motion/ImuMotionGate.kt` (wire real data)
- `DualCameraController.kt` (add IMU stream)

---

### 2. Multi-Window Sync Segmentation
**Status:** Single-window works, session-level stub  
**Effort:** 6-8 hours  
**Impact:** Better handling of long sessions with quality variations

**Current State:**
- `GoodSyncDetector.detectGoodSyncWindows()` works on single 8s window
- `detectSessionSegments()` is a placeholder
- Rolling window parameters defined but unused

**Implementation:**
- Implement sliding 8s window analysis over full 30-60s session
- Track quality evolution over time
- Extract best continuous segments for PTT computation
- Generate quality timeline for export

**Benefits:**
- Use best portions of variable-quality sessions
- Provide temporal quality feedback
- Better PTT estimates from long captures

**Files to modify:**
- `feature-processing/.../sync/GoodSyncDetector.kt`
- `ProcessingViewModel.kt` (use segmented analysis)
- Export schema (add quality timeline)

---

### 3. Multi-Method PTT Consensus
**Status:** Cross-correlation only, framework ready  
**Effort:** 10-15 hours  
**Impact:** More robust PTT estimates

**Current State:**
- `PTTConsensus` class exists
- Only cross-correlation method implemented
- Segment windowing prepared but not used

**Implementation:**
- Add peak-to-peak PTT method (systolic peak alignment)
- Add phase-based PTT (Hilbert transform or zero-crossing)
- Compute consensus via median or weighted average
- Report method agreement as confidence metric

**Benefits:**
- Cross-validate PTT across methods
- Detect method-specific artifacts
- Higher confidence when methods agree

**Files to modify:**
- `feature-processing/.../ptt/PTTConsensus.kt`
- `PttEngine.kt` (integrate consensus)
- Export (add per-method PTT values)

---

## Medium Priority (Performance & UX)

### 4. ROI-Aware Quality Computation
**Status:** Full-buffer analysis, ROI available  
**Effort:** 4-6 hours  
**Impact:** Modest performance improvement, cleaner metrics

**Current State:**
- `FaceMotionIndex` and `FingerSaturationIndex` receive width/height but use full buffer
- ROI coordinates available but not used for cropping

**Implementation:**
- Crop buffers to ROI before analysis
- Reduce computation (smaller arrays)
- More accurate metrics (exclude background)

**Benefits:**
- 20-30% faster per-frame analysis
- Saturation/motion metrics specific to signal region
- Less noise from non-ROI pixels

**Files to modify:**
- `feature-processing/.../motion/FaceMotionIndex.kt`
- `feature-processing/.../motion/FingerSaturationIndex.kt`

---

### 5. Timestamp-Based FPS Tracking
**Status:** Frame-count based, timestamps available  
**Effort:** 2-3 hours  
**Impact:** More accurate FPS under variable frame rates

**Current State:**
- `FpsTracker.onFrameReceived(timestampNs)` ignores timestamp
- Uses simple frame counter

**Implementation:**
- Calculate FPS from timestamp deltas
- Detect frame drops via timestamp gaps
- Handle timestamp rollover (rare but possible)

**Benefits:**
- Accurate FPS even with variable capture rates
- Better drift correlation
- Detect camera throttling

**Files to modify:**
- `feature-capture/.../util/FpsTracker.kt`

---

### 6. Protocol Countdown Timers
**Status:** Elapsed time shown, remaining time not  
**Effort:** 2-3 hours  
**Impact:** Better UX for guided protocols

**Current State:**
- `ProtocolWizard` receives `phaseRemainingS` and `totalRemainingS` but doesn't display them
- Only elapsed time shown

**Implementation:**
- Add countdown display per phase
- Add total session countdown
- Visual progress bars
- Audio/haptic cues at phase transitions

**Benefits:**
- Users know how much longer to hold position
- Better compliance with protocol timing
- Reduced early stops

**Files to modify:**
- `app/.../ui/components/ProtocolWizard.kt`

---

## Low Priority (Research & Advanced Features)

### 7. Adaptive Filter Parameters
**Status:** Fixed 0.7-4.0 Hz bandpass  
**Effort:** 6-8 hours  
**Impact:** Better accuracy for extreme heart rates

**Current Implementation:**
- Hardcoded bandpass filter (0.7-4.0 Hz = 42-240 BPM)
- Works for most users but suboptimal for athletes (>180 BPM) or bradycardia (<50 BPM)

**Enhancement:**
- Detect HR from raw signal first
- Adjust bandpass dynamically (e.g., HR±30 BPM)
- Re-filter with optimized range

**Benefits:**
- Better signal quality for extreme HRs
- Reduced filter artifacts
- More accurate peak detection

**Files to modify:**
- `SignalPipeline.kt` (add adaptive filtering)
- `DspFunctions.kt` (parameterized bandpass)

---

### 8. Multi-ROI Redundancy (Face)
**Status:** Single forehead ROI  
**Effort:** 12-16 hours  
**Impact:** Fallback when forehead tracking fails

**Current Implementation:**
- Single forehead ROI from ML Kit landmarks
- If face lost, signal stops

**Enhancement:**
- Add cheek ROIs (left/right)
- Add nose bridge ROI
- Automatic fallback when primary ROI fails
- Multi-ROI fusion for robustness

**Benefits:**
- Continued signal during partial face occlusion
- Better tracking with glasses/hats
- Redundancy for head rotation

**Files to modify:**
- `feature-capture/.../roi/FaceRoiTracker.kt`
- `LumaExtractor.kt` (multi-ROI extraction)
- UI (show active ROI)

---

### 9. Advanced Respiratory Analysis
**Status:** Simple modulation hint  
**Effort:** 8-12 hours  
**Impact:** Research/clinical value

**Current Implementation:**
- `BiomarkerComputer` detects respiratory modulation (boolean)
- No respiratory rate estimation

**Enhancement:**
- Estimate respiratory rate from PPG envelope (0.1-0.3 Hz)
- RSA (Respiratory Sinus Arrhythmia) analysis
- Breathing pattern classification
- Export respiratory waveform

**Benefits:**
- Additional biomarker for research
- Stress/relaxation indicator
- Protocol compliance verification (paced breathing)

**Files to modify:**
- `feature-processing/.../biomarker/RespiratoryAnalyzer.kt` (new)
- `BiomarkerComputer.kt` (integrate)
- Export schema

---

### 10. Cloud Sync & Multi-Session Trends
**Status:** Local-only storage  
**Effort:** 20-30 hours  
**Impact:** Longitudinal tracking

**Current Implementation:**
- `VascularTrendStore` uses local SharedPreferences
- No cross-device sync
- Limited to 10 sessions

**Enhancement:**
- Optional cloud backend (Supabase/Firebase)
- Multi-device sync
- Unlimited session history
- Trend visualization (weeks/months)
- Export to research platforms

**Benefits:**
- Long-term vascular health tracking
- Research data aggregation
- Clinical longitudinal studies

**Files to modify:**
- `app/.../sync/CloudSyncManager.kt` (new)
- `VascularTrendStore.kt` (add sync)
- Backend API integration

---

## Technical Debt & Optimization

### 11. RenderScript Replacement (Already Avoided)
**Status:** ✅ Not using RenderScript  
**Effort:** N/A  
**Impact:** N/A

**Current Implementation:**
- Direct YUV luma extraction (no RGB conversion)
- No RenderScript dependency

**Note:** Already compliant with modern Android (RenderScript deprecated API 31+)

---

### 12. Memory Pool for Frame Buffers
**Status:** Per-frame ByteArray allocation  
**Effort:** 6-8 hours  
**Impact:** Reduced GC pressure

**Current Implementation:**
- Each frame allocates new ByteArray for Y plane
- Works but causes GC churn at 30 fps

**Enhancement:**
- Pre-allocate buffer pool (e.g., 10 buffers)
- Reuse buffers across frames
- Return to pool after processing

**Benefits:**
- Eliminate per-frame allocations
- Reduce GC pauses
- Lower memory fragmentation

**Files to modify:**
- `feature-capture/.../util/BufferPool.kt` (new)
- `DualCameraController.kt` (use pool)

---

### 13. Hardware Acceleration (GPU/NPU)
**Status:** CPU-only DSP  
**Effort:** 30-40 hours  
**Impact:** 5-10x speedup for filters

**Current Implementation:**
- Pure Kotlin DSP (portable but slow)
- Butterworth filters in software

**Enhancement:**
- Use RenderEffect/Vulkan for filtering (API 31+)
- NPU acceleration for ML Kit (already used)
- GPU-based FFT for spectral analysis

**Benefits:**
- Real-time filtering at higher resolutions
- Lower CPU usage
- Longer battery life

**Files to modify:**
- `core-signal/...` (add GPU path)
- Gradle (add Vulkan/RenderEffect deps)

---

## Research & Clinical Features

### 14. Blood Pressure Calibration (Experimental)
**Status:** Not implemented (intentionally)  
**Effort:** 40-60 hours + validation  
**Impact:** High clinical value if validated

**Current Implementation:**
- PTT computed but NOT converted to BP
- Explicit disclaimers: "not a BP measurement"

**Enhancement:**
- Calibration protocol (reference BP + PTT)
- Personal calibration curve
- Trend-based BP estimation
- **Requires clinical validation**

**Regulatory Note:**
- Would require FDA/CE clearance
- Extensive validation studies
- Not recommended without regulatory pathway

---

### 15. Atrial Fibrillation Detection
**Status:** Not implemented  
**Effort:** 20-30 hours + validation  
**Impact:** High clinical value

**Current Implementation:**
- HRV metrics computed (RMSSD, SDNN)
- No arrhythmia classification

**Enhancement:**
- Irregular RR pattern detection
- P-wave absence indicators (from PPG morphology)
- AFib probability score
- **Requires clinical validation**

**Regulatory Note:**
- Medical device classification likely
- Requires extensive validation
- Consider regulatory pathway first

---

## Implementation Priority Matrix

| Feature | Priority | Effort | Impact | Dependencies |
|---------|----------|--------|--------|--------------|
| IMU Integration | HIGH | 8-12h | High | SensorManager |
| Multi-Window Sync | HIGH | 6-8h | Medium | None |
| PTT Consensus | MEDIUM | 10-15h | Medium | None |
| ROI-Aware Compute | MEDIUM | 4-6h | Low | None |
| Timestamp FPS | MEDIUM | 2-3h | Low | None |
| Protocol Countdown | LOW | 2-3h | Low | None |
| Adaptive Filters | LOW | 6-8h | Low | None |
| Multi-ROI Face | LOW | 12-16h | Medium | None |
| Respiratory Analysis | LOW | 8-12h | Low | None |
| Cloud Sync | LOW | 20-30h | Medium | Backend |
| Memory Pool | TECH DEBT | 6-8h | Low | None |
| GPU Acceleration | OPTIMIZATION | 30-40h | High | Vulkan |
| BP Calibration | RESEARCH | 40-60h | High | Validation |
| AFib Detection | RESEARCH | 20-30h | High | Validation |

---

## Recommended Implementation Order

### Phase 1: Core Quality (2-3 weeks)
1. IMU sensor integration
2. Multi-window sync segmentation
3. ROI-aware quality computation
4. Timestamp-based FPS

**Goal:** Maximize usable session rate to >95%

### Phase 2: Robustness (2-3 weeks)
1. PTT consensus (multi-method)
2. Memory buffer pooling
3. Multi-ROI face tracking
4. Protocol countdown timers

**Goal:** Handle edge cases gracefully

### Phase 3: Advanced Features (4-6 weeks)
1. Adaptive filter parameters
2. Advanced respiratory analysis
3. Cloud sync infrastructure
4. GPU acceleration

**Goal:** Research-grade capabilities

### Phase 4: Clinical (Regulatory Dependent)
1. BP calibration protocol
2. AFib detection
3. Clinical validation studies
4. Regulatory submissions

**Goal:** Medical device clearance

---

## Quick Wins (< 1 day each)

These can be implemented independently:

1. **Protocol Countdown** (2-3h)
   - Display remaining time in ProtocolWizard
   - Add to UI, no algorithm changes

2. **Timestamp FPS** (2-3h)
   - Use timestamps in FpsTracker
   - More accurate, no new dependencies

3. **ROI Cropping** (4-6h)
   - Crop buffers before analysis
   - Performance + accuracy improvement

4. **Export Enhancements** (2-4h)
   - Add quality timeline to JSON
   - Include per-method PTT values
   - Richer metadata

---

## Technical Considerations

### Memory Budget
- Current: ~200 MB RSS during 60s capture
- With buffer pool: ~150 MB (25% reduction)
- With GPU: ~180 MB (offload to VRAM)

### CPU Budget
- Current: 5-8% (ROI tracking) + 2-3% (analysis)
- With IMU: +1-2% (sensor fusion)
- With GPU: -50% (filter offload)
- Net: Similar or lower

### Battery Impact
- Current: Minimal (offline, no network)
- With cloud sync: +5-10% (periodic uploads)
- With GPU: -5% (more efficient than CPU)

### Storage
- Current: ~500 KB per session export
- With quality timeline: +50 KB
- With respiratory: +100 KB
- 100 sessions: ~65 MB

---

## Regulatory & Safety Notes

### Features Requiring Validation
- **BP Calibration:** Class II medical device (FDA)
- **AFib Detection:** Class II medical device (FDA)
- **Clinical Claims:** Requires IRB studies

### Safe to Implement Without Clearance
- IMU motion detection (quality improvement)
- Multi-window sync (algorithmic)
- PTT consensus (research tool)
- Respiratory rate (wellness, not diagnostic)
- Cloud sync (data management)

### Disclaimer Requirements
Any new biomarker must include:
- "Experimental, not validated"
- "Not for diagnosis or treatment"
- "Consult healthcare provider"

See `app/.../ui/education/EducationTextProvider.kt` for templates.

---

## Testing Requirements Per Feature

### IMU Integration
- Unit: Synthetic accelerometer traces
- Instrumented: Real device motion (walk, sit, stand)
- Validation: Compare IMU vs optical flow accuracy

### Multi-Window Sync
- Unit: Synthetic signals with quality variations
- Instrumented: Long sessions with motion
- Validation: Segment quality vs ground truth

### PTT Consensus
- Unit: Known-lag signals, method agreement
- Instrumented: Live sessions, method comparison
- Validation: Consensus vs single-method accuracy

### BP Calibration (if pursued)
- Clinical: 50+ subjects, reference sphygmomanometer
- Validation: Bland-Altman analysis, FDA submission
- Regulatory: 510(k) or De Novo pathway

---

## Code Locations for Future Work

### Placeholder Classes (Ready to Implement)
```
feature-processing/src/main/java/com/vivopulse/feature/processing/
├── motion/
│   ├── ImuMotionGate.kt          ← Wire Android sensors
├── sync/
│   └── GoodSyncDetector.kt       ← Implement detectSessionSegments()
├── ptt/
│   └── PTTConsensus.kt           ← Add peak-to-peak, phase methods
└── biomarker/
    └── RespiratoryAnalyzer.kt    ← New file for advanced respiratory
```

### Integration Points
```
app/src/main/java/com/vivopulse/app/
├── sensors/
│   └── ImuCollector.kt           ← New: Android SensorManager wrapper
├── sync/
│   └── CloudSyncManager.kt       ← New: Optional cloud backend
└── viewmodel/
    └── CaptureViewModel.kt       ← Wire new sensors/features
```

---

## Backward Compatibility

All enhancements must maintain:
- Export schema compatibility (use `additionalProperties: true`)
- API stability (no breaking signature changes)
- Graceful degradation (features optional)

### Versioning Strategy
- Schema version bump for new fields
- Feature flags for experimental features
- Device capability checks before enabling

---

## Community Contributions

### Good First Issues
- Protocol countdown timers
- Timestamp-based FPS
- Export enhancements
- UI polish

### Advanced Issues
- IMU integration
- Multi-window sync
- PTT consensus
- GPU acceleration

### Research Collaborations
- BP calibration validation
- AFib detection studies
- Large-scale data collection
- Algorithm benchmarking

---

## Summary

**Current Status:** Core functionality complete and production-ready

**Next Steps:**
1. IMU integration (highest impact)
2. Multi-window sync (robustness)
3. PTT consensus (validation)

**Long-Term Vision:**
- Research-grade PPG platform
- Optional clinical features (with validation)
- Open ecosystem for algorithm development

**Estimated Timeline:**
- Phase 1 (Quality): 2-3 weeks
- Phase 2 (Robustness): 2-3 weeks
- Phase 3 (Advanced): 4-6 weeks
- Phase 4 (Clinical): 6-12 months (regulatory dependent)

---

**Last Updated:** November 21, 2025  
**Maintainer:** VivoPulse Development Team  
**Status:** Living document, updated as features are implemented


