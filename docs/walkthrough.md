# Code Hardening Walkthrough

### 1. Pipeline Contract & Signal Inputs
- **Filtered Signals** (`faceSignal`, `fingerSignal`) are used for **Time-Domain Analysis** (Foot detection, PTT estimation). They must be clean of high-frequency noise.
- **Raw Input Signals** (`rawFaceSignal`, `rawFingerSignal`) are used for **SQI/SNR Calculation**. They preserve the original noise characteristics.
- **SQI is intentionally conservative**: SNR uses raw signal, so it may reject sessions where filtered fiducials look usable but raw noise is high. This prevents false confidence when filtering masks poor signal quality.
- **Test Implication**: Synthetic tests must provide clean data for the former and noisy data for the latter to correctly simulate the pipeline.

### 2. Observability Improvements
- Added `nBeats` to `PttOutput` and `SignalPipeline` logs to track foot-to-foot matching performance.
- Added `offsetPairs` to `DriftResult` to monitor synchronization robustness.
- Updated `algorithm_description.md` to verify these contracts.

### 3. Code Changes Made

### 1. [TimestampSync.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/timestamp/TimestampSync.kt)
- `calculateRobustOffset` → returns `Double?` (null on failure instead of silent 0.0)
- `DriftResult` → new `offsetValid: Boolean` field
- `analyzeSynchronization` → sets `isValid = overlapDurationMs > 0 && offsetValid`
- Log message changes to `"Sync: FAILED (Invalid Offset)"` when offset is null

render_diffs(file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/timestamp/TimestampSync.kt)

---

### 2. [PTTConsensus.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/ptt/PTTConsensus.kt)
- Replaced `minByOrNull` (nearest-neighbor) with **monotonic cursor** (causal: Face → Finger)
- Added **XCorr fallback** when foot-to-foot finds 0 beats
- Added **best-of consensus**: uses foot-to-foot when methods agree (≤50ms), XCorr otherwise

render_diffs(file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/ptt/PTTConsensus.kt)

---

### 3. [PttEngine.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/ptt/PttEngine.kt)
- Updated `agreementScore`: no penalty when `nBeats == 0` (XCorr-only fallback)

render_diffs(file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/ptt/PttEngine.kt)

---

### 4. [SignalPipeline.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/SignalPipeline.kt)
- Added `INVALID_OFFSET` as highest-priority gate (before FPS/jitter/drops)
- Updated `SESSION_SUMMARY` log to include `INVALID_OFFSET` gate reason

render_diffs(file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/main/java/com/vivopulse/feature/processing/SignalPipeline.kt)

### 5. [PttLiteratureConsistencyTests.kt](file:///home/ext.siarhei.zhmura/Work/pulse/feature-processing/src/test/java/com/vivopulse/feature/processing/tests/PttLiteratureConsistencyTests.kt)
- **Fix**: Updated test fixtures to provide **clean (filtered) signals** for `faceSignal`/`fingerSignal` while keeping noisy signals for `rawFaceSignal`/`rawFingerSignal`.
- **Reason**: The pipeline expects filtered data for signal processing (like foot detection) but uses raw data for SQI. The tests were injecting raw noisy data (9Hz noise) into the filtered input, causing `detectFeet` to fail on high-frequency noise derivatives.
- **Result**: `detectFeet` now works correctly on the clean signal, and SQI calculates correctly using the noisy raw signal.

## Test Results

| Suite | Tests | Pass | Fail | Notes |
|-------|-------|------|------|-------|
| `:feature-processing:testDebugUnitTest` | 69 | 69 | 0 | All tests pass, including previously failing PTT tests |
| `:core-signal:testDebugUnitTest` | 17 | 17 | 0 | All tests pass |
