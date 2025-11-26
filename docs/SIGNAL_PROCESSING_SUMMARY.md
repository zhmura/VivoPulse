# Signal Processing Implementation Summary

## Overview

This document summarizes the advanced signal processing features implemented in VivoPulse (Phases 2-4 of the roadmap), including research references and validation methods.

## Phase 2: Wavelet Denoising

### Implementation
- **Module**: `feature-processing/src/main/java/com/vivopulse/feature/processing/wavelet/WaveletDenoiser.kt`
- **Wavelet Family**: Haar (Daubechies 1)
- **Decomposition Levels**: 4
- **Thresholding**: Soft thresholding with VisuShrink universal threshold
- **Application**: Conditional (SQI 40-80 only)

### Research Foundation
- **Donoho & Johnstone (1994)**: Universal threshold method for wavelet shrinkage
- **Ram et al. (2012)**: Wavelet-based motion artifact reduction in PPG signals

### Testing
- `WaveletDenoiserTest.kt`: Validates lossless reconstruction and SNR improvement
- Synthetic signals with known noise characteristics

## Phase 3: Time-Frequency Tooling

### Implementation
- **Module**: `core-signal/src/main/kotlin/com/vivopulse/signal/TimeFrequencyTooling.kt`
- **Method**: Short-Time Fourier Transform (STFT)
- **Window**: Hann window, 256 samples
- **Overlap**: 50% (128 samples)
- **Output**: CSV matrix (time × frequency)

### Research Foundation
- **Allen (2007)**: Comprehensive PPG signal analysis and time-frequency methods
- **Allen & Murray (2003)**: Time-frequency characterization of PPG pulse shapes

### Export Integration
- **Location**: `core-io/src/main/java/com/vivopulse/io/ClinicianGradeExporter.kt`
- **Flag**: `FeatureFlags.ENABLE_TF_EXPORT` (default: true for validation phase)
- **Files**: `tf/tf_face_stft.csv`, `tf/tf_finger_stft.csv`
- **Use Case**: Offline analysis and ML experiments

### Testing
- `TimeFrequencyToolingTest.kt`: Validates frequency identification and output dimensions

## Phase 4: Walking Mode

### Implementation Components

#### 1. IMU Motion Analyzer
- **Module**: `feature-processing/src/main/java/com/vivopulse/feature/processing/motion/ImuMotionAnalyzer.kt`
- **Method**: Peak detection on accelerometer magnitude
- **Step Detection**: Autocorrelation-based frequency estimation (1.0-3.0 Hz)
- **Walking Threshold**: RMS > 0.15 G

#### 2. Adaptive Notch Filter
- **Module**: `feature-processing/src/main/java/com/vivopulse/feature/processing/motion/StepNotchFilter.kt`
- **Filter Type**: IIR biquad notch (RBJ Cookbook design)
- **Q-Factor**: 5.0 (narrow bandwidth ~0.3-0.5 Hz)
- **Harmonics**: Notches at fundamental + 2nd harmonic

#### 3. DSP Foundation
- **Module**: `core-signal/src/main/kotlin/com/vivopulse/signal/DspFunctions.kt`
- **Function**: `notchFilter(signal, notchFreqHz, sampleRateHz, qFactor)`
- **Implementation**: Direct Form II biquad

### Research Foundation
- **Poh et al. (2010)**: Motion-tolerant adaptive filtering for wearable PPG
- **Yousefi et al. (2014)**: Adaptive notch filtering for ambulatory PPG monitoring

### Integration
- **Pipeline**: `SignalPipeline.kt` constructor parameter `walkingModeEnabled`
- **Feature Flag**: `FeatureFlags.isWalkingModeEnabled()` / `setWalkingModeEnabled()`
- **Processing Flow**:
  1. Analyze IMU data → detect walking + step frequency
  2. Apply notch filter if walking detected
  3. Log detection for debugging

## Core Signal Processing (Phase 1 - Stabilized)

### Bandpass Filtering
- **Order**: 4th-order Butterworth (24 dB/octave roll-off)
- **Passband**: 0.7-4.0 Hz (cardiac frequencies)
- **Reference**: Elgendi (2012) - PPG signal analysis standards

### PTT Consensus Algorithm
- **Method A**: Cross-correlation with parabolic interpolation
- **Method B**: Foot-to-foot detection (derivative threshold 10-20%)
- **Aggregation**: Median with IQR outlier rejection
- **Agreement**: |XCorr - Foot| ≤ 20 ms
- **References**: 
  - Muehlsteff et al. (2006) - Pulse arrival time measurement
  - Nitzan et al. (2002) - Foot-to-foot detection methods

### Signal Quality Index (SQI)
- **Metrics**: SNR + motion penalties + saturation checks
- **Range**: 0-100
- **Reference**: Li & Clifford (2012) - Signal quality assessment

### GoodSync Detection
- **Criteria**: SQI ≥70, Corr ≥0.70, |ΔHR| ≤5 bpm, FWHM ≤120 ms
- **Morphological Closing**: Bridges gaps ≤1s
- **Minimum Duration**: 5s segments
- **Reference**: Clifford et al. (2006) - Quality gating in cardiorespiratory monitoring

## Performance Characteristics

### Computational Complexity
- **Bandpass Filter**: O(N) per channel
- **Wavelet Denoising**: O(N log N) when applied
- **Notch Filter**: O(N) per notch
- **STFT**: O(N × M × log M) where M = window size (offline only)

### Memory Footprint
- **Ring Buffers**: Bounded (no monotonic growth)
- **Session RSS**: < 200 MB target
- **Wavelet**: In-place processing where possible

### Processing Budget
- **Per-frame average**: ≤ 8 ms
- **95th percentile**: ≤ 16 ms
- **YUV→ROI**: ≤ 3 ms average
- **Walking mode overhead**: ~2-4 ms additional

## Testing & Validation

### Unit Test Coverage
- ✅ `BandPassFilterTests.kt`: Frequency response, DC removal, attenuation
- ✅ `WaveletDenoiserTest.kt`: Lossless reconstruction, SNR improvement
- ✅ `TimeFrequencyToolingTest.kt`: Frequency identification, dimensions
- ✅ `PTTConsensusTest.kt`: Known ground truth validation
- ✅ `SignalPipelineTest.kt`: End-to-end integration (with Robolectric)

### Synthetic Signal Testing
All algorithms validated with:
- Known-frequency sine waves
- Controlled noise (white, pink)
- Known PTT delays (60, 100, 140 ms)
- Motion artifacts (step frequencies 1.5-2.5 Hz)

### Real-World Validation
- Device testing on Pixel/Samsung Galaxy series
- Performance profiling on target hardware
- Thermal throttling behavior under 60s capture

## Documentation Updates

### README.md
- Added "Signal Processing Pipeline" section
- Listed all 7 processing stages with references
- Added "Research References" section with key publications
- Organized by topic (PPG, Wavelet, Motion, PTT, SQI)

### FUNCTIONAL_SPEC.md
- Updated FR-S with FR-S6 (Wavelet) and FR-S7 (Walking Mode)
- Updated FR-P with PTT method references
- Updated FR-E with TF export specification
- Added Section 5: "Research References & Validation"
- Included implementation notes and test coverage

## Future Enhancements

### Walking Mode UI
- [ ] Add toggle in CaptureScreen or debug menu
- [ ] Display walking detection status in real-time
- [ ] Show step frequency in debug overlay

### Advanced Motion Compensation
- [ ] Implement simple ANC (Adaptive Noise Cancellation) with LMS
- [ ] Multi-axis IMU fusion (gyroscope + accelerometer)
- [ ] Adaptive Q-factor based on motion intensity

### Time-Frequency Analysis
- [ ] Morlet CWT for better time-frequency resolution
- [ ] Pre-rendered PNG heatmaps in export
- [ ] Interactive TF viewer in results screen (research mode)

### Wavelet Optimization
- [ ] Evaluate Daubechies 4 (db4) vs Haar for PPG
- [ ] Adaptive level selection based on signal length
- [ ] Hard thresholding option for comparison

## References

1. Elgendi, M. (2012). Current Cardiology Reviews, 8(1), 14-25.
2. Allen, J. (2007). Physiological Measurement, 28(3), R1.
3. Donoho & Johnstone (1994). Biometrika, 81(3), 425-455.
4. Ram et al. (2012). IEEE Trans. Instrumentation and Measurement, 61(5), 1445-1457.
5. Poh et al. (2010). IEEE Trans. Information Technology in Biomedicine, 14(3), 786-794.
6. Yousefi et al. (2014). IEEE J. Biomedical and Health Informatics, 18(2), 670-681.
7. Muehlsteff et al. (2006). IEEE EMBS Conference, 2006.
8. Nitzan et al. (2002). IEEE Instrumentation & Measurement Magazine, 5(4), 9-15.
9. Li & Clifford (2012). Physiological Measurement, 33(9), 1491.
10. Clifford et al. (2006). Physiological Measurement, 27(9), 155.
11. Allen & Murray (2003). Physiological Measurement, 24(2), 297.

---

**Document Version**: 1.0  
**Last Updated**: November 26, 2025  
**Status**: Phases 1-4 Complete, Validated

