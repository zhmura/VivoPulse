# Measurement validity implementation and test record

Branch: `codex/measurement-validity-tests`, based on `02999ad`. Work date: 21 September 2026. Scope: engineering correctness and reproducible validation of experimental face–finger optical delay. This is not a medically validated BP, PWV, arterial-stiffness or ECG-HRV measurement.

The [historical research audit](MEASUREMENT_VALIDITY_RESEARCH.md) describes defects in the starting revision and their scientific context. The [synthetic model](SYNTHETIC_MODEL.md) defines what the generated data can and cannot establish. The [runner guide](../tools/synthetic-benchmark/README.md) gives executable commands.

## Implemented changes

| Area | Change and reason |
| --- | --- |
| Data provenance | Empty recordings and missing channels no longer become synthetic waveforms. Explicit debug simulation carries `SYNTHETIC` provenance, is labelled in results and is excluded from real measurement trends. |
| Authoritative result | `SignalPipeline.pttOutput` is the single reportable delay. The result adapter never reruns the estimator to bypass rejection. Invalid values are unavailable in the UI and JSON, rather than a plausible zero. |
| Acquisition time | Require explicit shared timebase evidence. Actual bound cameras must both report Android `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`, have the concurrent FPS capability and retain the same binding generation through a recording. Fallback capability resets on each binding attempt. |
| Clock interpretation | Overlap, native sampling rate, jitter and missing-frame estimates remain measurable; arbitrary nearest-frame pairs or unequal recording spans no longer claim clock offset/drift. Unknown drift is exported as null. REALTIME establishes comparable sensor clocks, not calibrated optical sampling times. |
| Data loss | Reject nonfinite observations, nonmonotonic/duplicate timestamps, missing channels and interpolation spans over 100 ms. No extrapolation or filling long gaps with fabricated pulses. Original timestamped observations survive rejection and are exportable. |
| Quality gates | Unknown timebase, HR-only hardware, low native FPS, excessive jitter/drop loss, insufficient duration, timestamped motion/saturation, flat signals, photometric steps and low raw SNR prevent delay reporting. A high composite score cannot compensate for these failures. |
| Filtering | Propagate the actual processing rate. Use symmetric processing in both channels: mean removal, forward/reverse bandpass and normalization. Main delay no longer depends on unvalidated repair, wavelet substitution, walking notch or channel-specific causal detrending. POS and morphology features remain research diagnostics. |
| Numerical DSP | Correct cross-spectrum orientation and delay sign; implement cubic Savitzky–Golay derivatives; use correct Butterworth section Q values and steady-state section initialization; allow zero GCC lag, taper and whiten only the pulse band; reject flat/nonfinite correlation inputs. |
| Beat matching and fusion | Unique chronological foot matches; no finite delay without evidence; order-independent robust fusion; no false precision gain from duplicated same-data algorithms. The retained uncertainty field is approximate model spread, not a calibrated clinical confidence interval. |
| SQI and UI scores | Estimate spectral concentration from the observed raw trace, avoiding filtered-minus-itself noise. Fix fraction/percentage comparison. UI labels the result experimental and does not call a quality score medical reliability. |
| Audit export | Schema 1.2 records provenance, validity, rejection reasons, algorithm revision and native rates. Invalid/nonfinite delay values and unknown drift serialize as null; CSV uses locale-independent numbers and blanks for missing metadata. Native source timestamps/values are separate from the resampled grid. The placeholder cross-correlation image is removed. |
| Automation | Portable source-compiling JVM harness, deterministic scenario contracts, numerical probes, selected JUnit regressions, source-hash manifests and a GitHub Actions workflow. |

The forward/reverse cascade comprises a fourth-order high-pass and fourth-order low-pass per pass, then applies both passes. It is not a single fourth-order bandpass transfer function, and forward/reverse filtering squares the magnitude response. Padding and section initialization reduce transients but are not Gustafsson optimal initial conditions.

Expanded integration tests also exposed coherent spectral leakage in nearly sinusoidal signals: absent harmonics were being treated as high-confidence phase evidence. CSP now requires per-channel harmonic power support, and GCC regularizes whitening with a floor relative to the largest retained cross-spectrum magnitude. The existing 5 ms and 10 ms accuracy assertions were retained. These numerical support rules remain engineering choices that need evaluation on reference recordings.

## Engineering acceptance policy

Defaults are deliberately explicit and still need real-data evaluation: native sampling >=25 Hz, median interval-deviation jitter <=5 ms, inferred dropped-frame fraction <=10%, interpolation span <=100 ms, at least eight seconds of overlap after a common two-second warmup, face raw SNR >=3 dB and finger raw SNR >=6 dB. Motion/photometric limits are described in `SignalPipeline`; they are operational gates, not medical thresholds. Whole-session rejection is conservative; a future segment-based policy must preserve exclusion masks and acceptance coverage.

Missing optional motion, saturation and IMU observations remain missing rather than becoming zero. Gates use supplied evidence; absence of these sensors is not proof of stillness or acceptable contact. The scalar benchmark does not test extraction of those quality measurements from images or physical sensors. Window stability uses bounded GCC estimates to avoid whole-pulse-cycle aliases; its SD is variation across overlapping windows, not an accuracy interval.

The estimator reports a **signed optical delay** (positive means finger later). Zero and negative values are useful mathematical diagnostics; neither sign establishes a physiological pathway. Interpolation density is reported separately from native camera rate. A waveform-only pipeline cannot distinguish an unknown clock offset or a nonphysiological artifact that is identical to a pulse.

## Validation evidence

The committed [dated result snapshot](validation/2026-09-21/README.md) records exact outcomes and execution boundaries. Run the commands in the runner guide to regenerate evidence from current source. The manifest, not a historical README claim, identifies the tested code.

## Remaining work before medical interpretation

1. **Optical bench:** feed the two physical cameras a common programmed optical reference, then known fractional delays; test each camera pair, FPS, resolution, exposure, torch and thermal state. Record sensor timestamps, exposure and rolling-shutter skew plus ROI row position. Estimate fixed bias, drift, variance and latency under load. Android timestamp source alone is insufficient.
2. **Contact and biomechanics:** measure contact force/pressure while varying finger placement, force, temperature, hand height and perfusion. Compare wavelength/channel choices and fiducial definitions. Do not map the synthetic pressure labels to mmHg or infer BP from a timing shift.
3. **Reference recordings:** synchronize phone acquisition with reference PPG at matching anatomical sites; use ECG for beat identity and PRV-vs-HRV comparisons. Include movement, lighting, skin-tone range, heart rate changes and repeat sessions. Keep subject/session/device holdouts separate from threshold development.
4. **Prespecified analysis:** compare bias, limits of agreement, absolute/tail error, repeatability, beat false positives/misses and accepted coverage, including rejected sessions. Assess uncertainty coverage empirically. Do not select only successful recordings or use correlated algorithms as independent validation.
5. **Intended medical endpoint:** specify whether the intended output is optical dPTT, PRV or BP. BP claims require separate appropriately designed clinical evaluation and relevant standards; existing scalar delay tests provide no BP accuracy evidence.

Additional software follow-ups: calibrated optical time correction; robust validated segment selection and beat-level uncertainty; empirical pressure/morphology rejection; supported-device evidence replacing whitelist assumptions; reference-data replay and larger seed/population sweeps; Android instrumentation/device tests; encrypted archive decrypt/share usability and full capture-metadata export. These are documented follow-ups, not capabilities silently assumed by this branch. Historical clinician-grade class names are retained for compatibility and do not establish medical-grade accuracy.
