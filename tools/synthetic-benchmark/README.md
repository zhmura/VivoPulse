# Synthetic measurement checks

This tool compiles the repository's **production Kotlin signal-processing code** and executes known-truth signals through `SignalPipeline` and `PttCalculator`. Python orchestrates the compiler; it does not reimplement the algorithms. Android logging, device identity and the ROI rectangle type have small JVM adapters. Camera acquisition, image extraction, Android storage and Compose are not emulated.

Use [the model specification](../../docs/SYNTHETIC_MODEL.md) for equations, seeds, native sampling, pressure assumptions and evaluation contracts. See [the implementation and validation record](../../docs/MEASUREMENT_VALIDITY_IMPLEMENTATION.md) for changes, results and remaining validation.

## Run locally

Requirements: Python 3.10+, JDK 17, Kotlin compiler 1.9.24. The Windows bootstrap downloads checksum-pinned official compiler releases into the ignored `build` directory and makes no global installation changes:

```powershell
./tools/synthetic-benchmark/bootstrap.ps1
python tools/synthetic-benchmark/run.py
python tools/synthetic-benchmark/fetch_test_deps.py
python tools/synthetic-benchmark/run.py --unit-tests --output build/synthetic-benchmark/unit-tests
```

On Linux/macOS, supply an installed JDK 17 and the official Kotlin 1.9.24 compiler archive:

```bash
export JAVA_HOME=/path/to/jdk-17
python tools/synthetic-benchmark/run.py --kotlin-home /path/to/kotlinc
python tools/synthetic-benchmark/fetch_test_deps.py
python tools/synthetic-benchmark/run.py --kotlin-home /path/to/kotlinc --unit-tests --output build/synthetic-benchmark/unit-tests
```

`--java-home` and `--kotlin-home` override environment and local-cache discovery. After provisioning, execution is offline. JUnit downloads use pinned SHA-256 checksums. The checked-in GitHub workflow executes both commands on pushes and pull requests; it has read-only repository permissions.

The workflow also runs `schema/test_export_schemas.py` with `jsonschema==4.23.0`. See [export schema 1.2](../../docs/EXPORT_SCHEMA_1_2.md) for the isolated local command and the distinction between schema fixtures and Android serialization tests.

Every contract failure, exception or compilation failure returns a nonzero exit code. Diagnostic scenarios are explicitly ungraded and never counted as successful accuracy cases. `--probes-only` skips scenarios. `unit-tests.txt` is the explicit source list for JVM tests; it is not the entire Android test suite.

## Generated evidence

By default, `build/synthetic-benchmark/results` contains:

- `results.csv`: truth, acquisition settings, pipeline/adapter results, rejection reasons and independent algorithm diagnostics.
- `probes.csv`: signed-delay, derivative, filter, fusion, matching, SQI and invalid-adapter regression results.
- `summary_stats.csv`: bias, MAE, RMSE, worst error and measurement/rejection counts by scenario group.
- `traces/`: original irregular timestamp/value streams and analytic pulse-component landmarks. Component maxima are not asserted to equal maxima of overlapping composite pulses.
- `manifest.json`: Java runtime, exact compiled source hashes and adapter scope.
- `compile.log`, `run.log`, `summary.txt` and the generated jar/compiler arguments.

Compiled binaries, dependencies and full traces are ignored by Git. A small dated result snapshot is retained under `docs/validation/2026-09-21`. Re-run after modifying algorithms or fixtures; previous results do not validate new source hashes.

## Historical comparison

Preserve historical production sources separately; never replace the working checkout. For the original audited commit:

```powershell
git archive --format=zip --output=build/synthetic-benchmark/baseline-sources.zip 02999ad core-signal/src/main feature-processing/src/main
Expand-Archive -LiteralPath build/synthetic-benchmark/baseline-sources.zip -DestinationPath build/synthetic-benchmark/baseline-sources -Force
python tools/synthetic-benchmark/run.py --source-root build/synthetic-benchmark/baseline-sources --baseline --output build/synthetic-benchmark/baseline-results
```

`--baseline` removes only the new provenance/timebase/capability arguments from a temporary fixture copy, because historical data classes lack them. It does **not** patch numerical production code or the pass/fail criteria. The expected nonzero exit exposes historical failures. Use `--probes-only` for a faster numerical comparison. Historical sources must provide the API expected by the runner; this adapter is specific to the audited starting revision.

## Interpretation

The 10 ms default tolerance is an engineering regression threshold. Sub-sample interpolation can fit an ideal repeated waveform very precisely; it cannot create independent camera timing information. A clean synthetic error below one millisecond is **not** a phone accuracy specification. Acceptance coverage and fault rejection are equally important. Shape changes, common artifacts and falsely trusted clocks remain diagnostic counterexamples even when the automated gates pass.
