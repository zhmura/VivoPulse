package com.vivopulse.feature.capture.analysis

import androidx.camera.core.ImageProxy
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import java.lang.RuntimeException

class SafeImageAnalyzerTest {

    @Test
    fun `analyze calls close on image after processing`() {
        val mockImage = mockk<ImageProxy>(relaxed = true)
        var processed = false
        
        val analyzer = SafeImageAnalyzer {
            processed = true
        }
        
        analyzer.analyze(mockImage)
        
        assert(processed)
        verify(exactly = 1) { mockImage.close() }
    }

    @Test
    fun `analyze calls close on image even if processing throws`() {
        val mockImage = mockk<ImageProxy>(relaxed = true)
        
        val analyzer = SafeImageAnalyzer {
            throw RuntimeException("Processing failed")
        }
        
        try {
            analyzer.analyze(mockImage)
        } catch (e: RuntimeException) {
            // Expected
        }
        
        verify(exactly = 1) { mockImage.close() }
    }
}
