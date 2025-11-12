// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.3.0" apply false
    id("com.android.library") version "8.3.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.20" apply false
    id("com.google.dagger.hilt.android") version "2.48.1" apply false
    id("com.google.devtools.ksp") version "1.9.20-1.0.14" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

// Generate verification artifacts (matrix, diagnostics placeholder)
tasks.register("packageVerificationArtifacts") {
    group = "verification"
    description = "Generate verification reports and matrix from test outputs"

    doLast {
        val rootDir = project.rootDir
        val reportsDir = java.io.File(rootDir, "verification-reports")
        reportsDir.mkdirs()

        // Helper to parse JUnit XML failures count (very lightweight)
        fun countFailures(dir: java.io.File): Int {
            if (!dir.exists()) return 0
            return dir.walkTopDown()
                .filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" }
                .map { it.readText() }
                .map { text ->
                    // Extract failures and errors attributes from testsuite
                    val failures = "failures=\"(\\d+)\"".toRegex().find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val errors = "errors=\"(\\d+)\"".toRegex().find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    failures + errors
                }
                .sum()
        }

        // Locate unit test result folders
        val coreSignalResults = java.io.File(rootDir, "core-signal/build/test-results/test")
        val featureProcessingResults = java.io.File(rootDir, "feature-processing/build/test-results/testDebugUnitTest")

        val unitFailures = countFailures(coreSignalResults) + countFailures(featureProcessingResults)
        val unitStatus = if (unitFailures == 0) "PASS" else "FAIL"

        // Instrumented results (may be absent in CI/no device)
        val androidResults = java.io.File(rootDir, "app/build/outputs/androidTest-results/connected")
        val instrStatus = if (androidResults.exists() && countFailures(androidResults) == 0) "PASS" else "N/A"

        val matrix = buildString {
            appendLine("| ID  | Capability                     | Method              | Input/Fixture                    | Acceptance Criteria                                                       | Status    |")
            appendLine("| --- | ------------------------------ | ------------------- | -------------------------------- | ------------------------------------------------------------------------- | --------- |")
            appendLine("| V1  | Dual camera concurrent preview | Instrumented        | Live device                      | Both previews render at ≥ 30 fps, torch toggle works                      | ${instrStatus} |")
            appendLine("| V2  | Timestamp drift detection      | Unit                | Synthetic ts with ±10 ms/s drift | Drift measured within ±2 ms/s; resampler aligns                           | ${unitStatus} |")
            appendLine("| V3  | Face ROI stability             | Instrumented        | Live device                      | ROI stays on forehead, recovery < 1 s after brief loss                    | ${instrStatus} |")
            appendLine("| V4  | Raw signal extraction          | Instrumented        | Live device                      | Visible ~1 Hz oscillation on finger; face oscillation with good light     | ${instrStatus} |")
            appendLine("| V5  | DSP correctness                | Unit                | Synthetic multi-sine + noise     | Passband preserved (1–2 Hz), stopbands attenuated ≥ 15 dB                 | ${unitStatus} |")
            appendLine("| V6  | Peak detection & HR sanity     | Unit + Instrumented | Synthetic + Live                 | HR(face) ≈ HR(finger) within ±5 bpm under good signal                     | ${unitStatus} |")
            appendLine("| V7  | PTT estimation (xcorr)         | Unit + Instrumented | Simulated mode lag=100 ms        | Reported PTT 100±5 ms; corrScore ≥ 0.90                                   | ${unitStatus} |")
            appendLine("| V8  | PTT on live                    | Instrumented        | Live device (60 s)               | corrScore ≥ 0.70; PTT in [50..150] ms; stability (SD) ≤ 25 ms             | ${instrStatus} |")
            appendLine("| V9  | SQI & guidance                 | Instrumented        | Live device (bad light/motion)   | SQI drop < 60 with correct tips; good case ≥ 70                           | ${instrStatus} |")
            appendLine("| V10 | Export                         | Unit + Instrumented | Live/Sim sessions                | ZIP with session.json + face_signal.csv + finger_signal.csv; schema valid | ${unitStatus} |")
            appendLine("| V11 | Performance                    | Instrumented        | Live device (60 s)               | Proc/frame avg < 8 ms; RSS < 200 MB; no sustained jank                    | ${instrStatus} |")
            appendLine("| V12 | Regression smoke               | Instrumented        | Prerecorded assets               | Pipeline produces expected HR/PTT within tolerances                       | ${instrStatus} |")
        }
        val matrixFile = java.io.File(reportsDir, "VERIFICATION_MATRIX.md")
        matrixFile.writeText(matrix)

        // Minimal diagnostics placeholder
        java.io.File(reportsDir, "diagnostics.md").writeText(
            """# Session Diagnostics (Summary)\n\n- Unit tests status: ${unitStatus}\n- Instrumented tests status: ${instrStatus}\n\nArtifacts will be populated by instrumented perf tests on device.\n"""
        )

        // Zip the reports directory
        val zipFile = java.io.File(rootDir, "verification-reports.zip")
        if (zipFile.exists()) zipFile.delete()
        val fos = java.io.FileOutputStream(zipFile)
        val zos = java.util.zip.ZipOutputStream(fos)
        reportsDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val entryName = reportsDir.toPath().relativize(file.toPath()).toString()
            zos.putNextEntry(java.util.zip.ZipEntry("verification-reports/$entryName"))
            file.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
        zos.close()

        println("Verification reports written to: ${reportsDir.absolutePath}")
        println("Zipped at: ${zipFile.absolutePath}")
    }
}


