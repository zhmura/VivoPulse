# Research export schema 1.2

Schema 1.2 records measurement provenance and preserves unavailable results. It is a structural data contract, not medical-device validation. The Kotlin class `ClinicianGradeExporter` and existing archive filename prefix remain for API compatibility; its JSON now identifies the payload as `research_with_plots`. `DataExporter` identifies its variant as `research_signals`.

`schema/session.schema.json` accepts both variants and requires their shared audit fields. It intentionally does not accept 1.1 payloads. `schema/signal.schema.json` validates the exact processed CSV header via a JSON `{ "columns": [...] }` descriptor. `schema/source_signal.schema.json` does the same for source CSVs. Neither header schema parses CSV rows.

## Audit and missing values

Every session includes `measurement_provenance` (`REAL`, `SYNTHETIC`, or `UNKNOWN`), `timing_verified`, `ptt_valid`, `rejection_reasons`, and `algorithm_revision`. PTT output is an experimental inter-site optical delay. Synthetic results remain visibly synthetic even when an engineering test accepts their delay.

A reportable delay requires the explicit valid flag, verified timing, no rejection reasons, and a finite delay. Otherwise the exported delay, correlation, stability, and confidence are JSON `null`, with quality `UNAVAILABLE`. Old nonnullable Kotlin numeric fields remain source-compatible, but a stale numeric value cannot override invalidity. Individual nonfinite diagnostic numbers, including unknown camera drift, are also JSON `null`; they are not replaced by zero. JSON nulls are present as keys rather than silently omitted.

Nonpositive epoch session timestamps mean unavailable and export as JSON null, rather than implying a recording occurred in 1970. The export-creation timestamp remains the actual time the archive is created.

`pttConfidence` remains a percentage in Kotlin. JSON uses `confidence_percent` for 0..100 and, in the plot variant, `confidence_0_to_1` for the divided value. These are engineering scores rather than calibrated probabilities. The old ambiguous `confidence` field was removed.

The camera object separates `native_face_rate_hz` and `native_finger_rate_hz` from the processing grid rate. A 100 Hz grid does not mean either camera acquired at 100 fps. ROI geometry and exact filter parameters are currently unavailable to the exporter; the plot variant explicitly marks them null instead of supplying hard-coded descriptions.

## Source observations versus processing grid

Both archives include `source_face.csv` and `source_finger.csv`, containing the actual samples supplied by capture: `timestamp_ns,raw_value,interpolated`. Source rows retain their original monotonic integer nanoseconds, order and gaps, with `interpolated=false`. No source is reconstructed from an index. Header-only files mean unavailable, as recorded by `source_samples.*_available`.

`face_signal.csv` and `finger_signal.csv` contain the processing-grid rows. Their 13 columns are:

```text
time_ms,raw_value,filtered_value,is_peak,r,g,b,motion_rms,saturation_fraction,imu_rms_g,phase_tag,timestamp_ns,interpolated
```

The `raw_value` in those files is the pre-filter value on the processing grid and may already have been interpolated. Use the source files for native acquisition analysis. Grid rows supplied by the view model have `interpolated=true`; missing timestamp/interpolation metadata stays blank. `timestamp_ns` is an integer and should be parsed as a 64-bit integer or string, not a JavaScript floating-point number. These monotonic timestamps are distinct from the epoch-millisecond session start/end fields.

All numeric CSV formatting uses a decimal point independent of device locale. Missing/nonfinite observations and absent quality metadata are blank cells. An absent IMU observation is not zero motion. Text fields use CSV quoting. `saturation_pct` was renamed to `saturation_fraction` to reflect capture's 0..1 units. Columns were appended for timestamp provenance, so consumers must read headers rather than assume 11 columns.

The basic export's combined convenience CSV keeps both channel timestamps and relative times; a row pairs entries by index only and does not assert simultaneous acquisition. It retains the longer channel instead of truncating to the shorter one. Source CSVs remain authoritative for acquisition timing.

## Removed claims and compatibility changes

- The placeholder `plots/xcorr_curve.png` was removed. No measured correlation curve reaches the exporter, so no curve is fabricated.
- Derived vascular, trend and biomarker extras are omitted for invalid, synthetic or unknown-provenance sessions. Eligible real-session extras appear under `experimental_analysis` with `clinically_validated=false`; they are no longer top-level clinical-looking fields.
- `device.android_api` became `device.android_version`: the supplied value is an Android version string, not an API-level integer.
- Consumers of 1.1 must handle null metrics, the renamed confidence/saturation fields, new archive entries, the two explicit export types, and the additional CSV columns.

## Verification boundary

The schema unit tests exercise locale independence, exact nanosecond serialization, missing/nonfinite values, quoted text, source gaps, and rejection overriding stale metrics. `schema/test_export_schemas.py` checks both structural payload fixtures, rejection invariants and the actual Kotlin CSV header, using `jsonschema==4.23.0`. Those Python fixtures do not execute the Android exporter or `JSONObject` serialization. JSON schemas describe the two payload layouts and enforce null PTT metrics when invalid. Schema conformance does not prove that timestamps came from synchronized sensors or that a report is physiologically correct.

Run the structural tests from the repository root in a Python environment with the pinned validator:

```sh
python -m pip install jsonschema==4.23.0
python schema/test_export_schemas.py
```

For an isolated dependency directory on PowerShell, use `python -m pip install --target build/synthetic-benchmark/schema-validator jsonschema==4.23.0`, then `$env:PYTHONPATH='build/synthetic-benchmark/schema-validator'; python schema/test_export_schemas.py`. The directory is ignored build output. The Kotlin `ExportSchemaTest` suite is also included in the standalone benchmark's `--unit-tests` mode.

Android encryption, scoped-storage behavior, bitmap rendering and on-device archive round trips still require Android integration tests. ZIPs are still constructed in memory; this change does not redesign storage or memory usage. The basic export retains its existing separate convenience CSV behavior outside the encrypted ZIP. Full raw camera images, per-frame camera metadata, exposure and rolling-shutter characterization are not stored by these APIs.
