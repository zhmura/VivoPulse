# Validation evidence — 21 September 2026

This record compares the audited baseline `02999ad` with the implementation on `codex/measurement-validity-tests`. Final source hashes are retained in the manifests. Java: Temurin 17.0.20.1+1; Kotlin: 1.9.24; Python: 3.12.10; Windows x64. These tests assess engineering behavior and do not establish clinical accuracy.

## Executed checks

| Check | Result |
| --- | --- |
| Final synthetic suite | **39/39 graded cases pass**; nine additional diagnostic cases are ungraded. 48 cases executed. |
| Final numerical probes | **16/16 pass**. |
| JVM regression suite | **131 tests pass**, including pipeline integration, timestamp integrity, DSP, narrowband delay accuracy, provenance/equality and export serialization models. |
| Export structural schemas | **9 tests pass** with `jsonschema==4.23.0`; tests use explicit payload fixtures and the actual Kotlin CSV header. |
| Android app compilation | `:app:compileDebugKotlin` **passed**, including dependent capture, processing and export modules. Initial build: 85 tasks executed in 3m43s. Final incremental compile after the spectral correction: 11 executed, 74 up-to-date in 1m12s. |
| Whitespace | `git diff --check` passed. |
| Historical comparison | Same 48 cases and 16 probes: **12 graded scenario failures and 13 numerical probe failures**; exit code 1 as expected. |

The final graded set comprises 12 clean cases that must produce accurate measurements, 13 disturbed but interpretable cases that must be accurate or reject, and 14 severe-fault cases that must reject. All 12 clean cases were measured. Twelve of the 13 interpretable disturbances were measured; the high-jitter case was rejected. All 14 severe faults were rejected without a reported numerical delay. Diagnostic cases are excluded from acceptance success counts.

For the 12 ideal clean fixtures, final MAE was **0.440 ms**, RMSE **0.477 ms**, and worst absolute error **0.728 ms**, against a prespecified 10 ms engineering tolerance. Baseline MAE was 1.080 ms and worst error 1.873 ms; both versions passed the clean tolerances. The larger improvement is fault handling and mathematical correctness. Ideal waveform fitting below a camera sample interval is not evidence of sub-millisecond camera accuracy.

Two expanded integration assertions initially exposed a genuine narrowband estimator bias: an imposed 80 ms delay returned 63.988 ms, and a noisy 70 ms delay returned 54.858 ms. Harmonic power support and regularized whitening corrected them to 79.434 ms and 69.277 ms in the targeted diagnostic run. The original 5 ms and 10 ms assertions were preserved, and the complete final regression suite passes.

## Evidence files

- [Final scenario results](fixed/results.csv), [numerical probes](fixed/probes.csv), [group error/coverage statistics](fixed/summary_stats.csv), [source manifest](fixed/manifest.json), [execution receipt](fixed/execution.json).
- [Baseline scenarios](baseline/results.csv), [baseline probes](baseline/probes.csv), [baseline statistics](baseline/summary_stats.csv), [baseline manifest](baseline/manifest.json), [expected failure receipt](baseline/execution.json).
- [JUnit output](unit-tests/run.log), [test classes/source hashes](unit-tests/manifest.json), [JUnit execution receipt](unit-tests/execution.json).

Only the compiler installation path is normalized in snapshot manifests; source hashes remain unchanged. The historical fixture adapter removes new metadata constructor arguments unsupported by the original data classes and never edits the historical numerical code. The baseline does not have the new timebase/provenance gates; those missing protections are part of the comparison. Full traces and analytic landmarks are regenerated in ignored `build/synthetic-benchmark/results/traces`.

## Interpretation limits

The pressure-shape, clipping, clock and common-artifact diagnostics intentionally show that a clean-looking result can still be biased or physiologically uninterpretable. Their scalar truth is either absent or defined only on the supplied timestamp axes. A falsely trusted +40 ms camera-label offset is inseparable from an extra optical delay without independent synchronization evidence. A variable clock drift, changed upstroke/reflection or a common optical artifact is not validated by good correlation.

The harness compiles actual production Kotlin with logging, device-identity and rectangle adapters. It does not run CameraX, image/ROI extraction, physical IMU sensing, Compose, Android storage encryption or archive round trips. Android compilation verifies API integration but does not test runtime behavior. The selected JVM test list is explicit; this record does not claim the entire Gradle/instrumentation test suite ran. GitHub Actions is configured but was not remotely executed during this local task.

All toolchains remain under ignored project build directories. Android compilation used command-line tools 22.0, platform API 36 and build tools 35.0.0. The official Windows command-line archive was `commandlinetools-win-15859902_latest.zip`, SHA-256 `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a`.

Use the [runner guide](../../../tools/synthetic-benchmark/README.md) to reproduce software checks and the [implementation record](../../MEASUREMENT_VALIDITY_IMPLEMENTATION.md) for optical bench, pressure and clinical validation work that remains.
