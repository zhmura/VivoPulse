package com.vivopulse.feature.processing.tests

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class MedicalWordingCheck {
    @Test
    fun uiStrings_doNotContainForbiddenClaims() {
        val forbidden = listOf("mmHg", "cfPWV", "AIx", "Augmentation Index", "central BP")
        val projectRoot = findProjectRoot()
        val appSrc = File(projectRoot, "app/src/main/java")
        val offending = mutableListOf<String>()
        if (appSrc.exists()) {
            appSrc.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { f ->
                    if (f.name.contains("EducationTextProvider.kt")) return@forEach // allow disclaimers here
                    val text = f.readText()
                    forbidden.forEach { token ->
                        if (text.contains(token)) offending.add("${f.path} contains '$token'")
                    }
                }
        }
        assertFalse("Forbidden clinical terms found:\n${offending.joinToString("\n")}", offending.isNotEmpty())
    }

    private fun findProjectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        var steps = 0
        while (dir != null && steps < 7) {
            if (File(dir!!, "settings.gradle.kts").exists()) return dir!!
            dir = dir!!.parentFile
            steps++
        }
        return File(System.getProperty("user.dir"))
    }
}


