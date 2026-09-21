#!/usr/bin/env python3
"""Compile and exercise production Kotlin DSP without an Android installation.

Python only orchestrates kotlinc/java; it does not implement signal processing.
Outputs and downloaded runtimes belong in ignored build/synthetic-benchmark.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path, default=ROOT / "build/synthetic-benchmark/results")
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--kotlin-home", type=Path)
    parser.add_argument("--main", default="com.vivopulse.benchmark.BenchmarkMainKt")
    parser.add_argument("--unit-tests", action="store_true", help="Run the checked-in JVM regression suite instead of scenarios")
    parser.add_argument("--junit-dir", type=Path)
    parser.add_argument("--probes-only", action="store_true")
    parser.add_argument("--baseline", action="store_true", help="Adapt fixture metadata only when compiling historical sources")
    args = parser.parse_args()
    cache = ROOT / "build/synthetic-benchmark/toolchain"
    java_home = args.java_home or (Path(os.environ["JAVA_HOME"]) if "JAVA_HOME" in os.environ else next(cache.glob("jdk-*"), None))
    kotlin_home = args.kotlin_home or (Path(os.environ["KOTLIN_HOME"]) if "KOTLIN_HOME" in os.environ else cache / "kotlinc")
    java = str(java_home / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else shutil.which("java")
    if not java or not Path(java).exists() or not (kotlin_home / "lib/kotlin-compiler.jar").exists():
        parser.error("Java 17 and Kotlin 1.9.24 required. Run bootstrap.ps1 or pass --java-home and --kotlin-home.")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    root = args.source_root.resolve()
    core = root / "core-signal/src/main/kotlin"
    feature = root / "feature-processing/src/main/java/com/vivopulse/feature/processing"
    sources = sorted(p for p in core.rglob("*.kt") if p.name != "AppLogger.kt")
    sources += [feature / name for name in ("SignalPipeline.kt", "PttCalculator.kt", "QualityAssessment.kt", "timestamp/TimestampSync.kt")]
    for package in ("ptt", "sync", "sqi", "wavelet", "motion", "signal", "correlation"):
        sources += sorted((feature / package).rglob("*.kt"))
    sources += sorted((HERE / "adapters").rglob("*.kt"))
    sources += sorted((HERE / "src").glob("*.kt"))
    if args.baseline:
        # Historical models did not have provenance/timebase gates. Strip only
        # these fixture constructor arguments; production sources stay untouched.
        original = HERE / "src/SyntheticCases.kt"
        adapted = output / "SyntheticCases.kt"
        fixture = original.read_text(encoding="utf-8")
        fixture = re.sub(r"^import .*SignalProvenance\n", "", fixture, flags=re.M)
        fixture = re.sub(r",?\s*provenance = SignalProvenance.SYNTHETIC,\s*timingVerified = spec.timingVerified,\s*hardwarePttCapable = true", "", fixture)
        adapted.write_text(fixture, encoding="utf-8")
        sources[sources.index(original)] = adapted
    classpath = []
    test_classes = []
    if args.unit_tests:
        junit_dir = args.junit_dir or cache
        classpath = [junit_dir / "junit-4.13.2.jar", junit_dir / "hamcrest-core-1.3.jar"]
        if not all(p.is_file() for p in classpath):
            parser.error("JUnit jars missing. Run python tools/synthetic-benchmark/fetch_test_deps.py")
        test_paths = (HERE / "unit-tests.txt").read_text(encoding="utf-8").splitlines()
        tests = [root / p for p in test_paths if p.strip() and not p.startswith("#")]
        sources += tests
        sources.append(root / "core-io/src/main/java/com/vivopulse/io/model/ExportSchema.kt")
        for path in tests:
            body = path.read_text(encoding="utf-8")
            package = re.search(r"^package ([\w.]+)", body, flags=re.M).group(1)
            klass = re.search(r"^class (\w+)", body, flags=re.M).group(1)
            test_classes.append(package + "." + klass)
    missing = [str(p) for p in sources if not p.is_file()]
    if missing:
        parser.error("Missing source files: " + ", ".join(missing))
    def source_name(p):
        try:
            return p.relative_to(root).as_posix()
        except ValueError:
            try:
                return p.relative_to(ROOT).as_posix()
            except ValueError:
                return "adapted-fixture/" + p.name
    manifest = {
        "scope": "Production JVM-compatible DSP/processing; Android logging, device identity and Rect adapters only",
        "java": subprocess.run([java, "-version"], capture_output=True, text=True).stderr.strip(),
        "kotlin": subprocess.run([java, "-cp", str(kotlin_home / "lib/*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-version", "-kotlin-home", str(kotlin_home.resolve())], capture_output=True, text=True).stderr.strip(),
        "kotlin_home": str(kotlin_home.resolve()),
        "historical_fixture_metadata_adapter": args.baseline,
        "test_classes": test_classes,
        "sources_sha256": {source_name(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    jar = output / "benchmark.jar"
    # An argument file avoids Windows command-length limits; paths use JVM escaping.
    compiler_args = ["-kotlin-home", str(kotlin_home.resolve()), "-jvm-target", "17", "-include-runtime", "-d", str(jar)]
    compiler_args += [str(p.resolve()) for p in sources]
    if classpath:
        compiler_args += ["-classpath", os.pathsep.join(str(p.resolve()) for p in classpath)]
    argfile = output / "compiler.args"
    argfile.write_text("\n".join('"' + arg.replace("\\", "/").replace('"', '\\"') + '"' for arg in compiler_args), encoding="utf-8")
    command = [java, "-cp", str(kotlin_home / "lib/*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "@" + str(argfile)]
    compiled = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (output / "compile.log").write_text(compiled.stdout, encoding="utf-8")
    if compiled.returncode:
        print(compiled.stdout)
        return compiled.returncode
    print(f"Compiled {len(sources)} Kotlin files. Running {'JUnit regressions' if args.unit_tests else 'production pipeline'}...", flush=True)
    run_cp = os.pathsep.join(str(p.resolve()) for p in [jar] + classpath)
    run_args = ["org.junit.runner.JUnitCore"] + test_classes if args.unit_tests else [args.main, str(output), "--strict"] + (["--probes-only"] if args.probes_only else [])
    result = subprocess.run([java, "-cp", run_cp] + run_args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (output / "run.log").write_text(result.stdout, encoding="utf-8")
    (output / "execution.json").write_text(json.dumps({
        "completed_utc": datetime.now(timezone.utc).isoformat(),
        "exit_code": result.returncode,
        "unit_tests": args.unit_tests,
        "probes_only": args.probes_only,
    }, indent=2) + "\n", encoding="utf-8")
    print(result.stdout if result.returncode else "\n".join(result.stdout.strip().splitlines()[-4:]))
    print(f"Artifacts: {output}")
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
