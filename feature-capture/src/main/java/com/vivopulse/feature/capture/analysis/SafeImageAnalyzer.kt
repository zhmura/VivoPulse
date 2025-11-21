package com.vivopulse.feature.capture.analysis

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * Ensures [ImageProxy] instances are always closed after analyzer work, even when
 * downstream processing throws, preventing backpressure buildup.
 */
class SafeImageAnalyzer(
    private val delegate: (ImageProxy) -> Unit
) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            delegate(image)
        } finally {
            image.close()
        }
    }
}


