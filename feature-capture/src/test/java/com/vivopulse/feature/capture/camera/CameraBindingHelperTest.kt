package com.vivopulse.feature.capture.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.vivopulse.feature.capture.model.Source
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
class CameraBindingHelperTest {

    private val provider = mockk<ProcessCameraProvider>(relaxed = true)
    private val lifecycleOwner = mockk<LifecycleOwner>(relaxed = true)
    private val frontPreview = mockk<PreviewView>(relaxed = true)
    private val backPreview = mockk<PreviewView>(relaxed = true)
    private val executor = mockk<ExecutorService>(relaxed = true)
    private val processFrame: (ImageProxy, Source) -> Unit = { _, _ -> }

    private val helper = CameraBindingHelper("TestTag", executor, processFrame)

    @Test
    fun `bindCamerasWithFallback concurrent mode binds both cameras`() {
        helper.bindCamerasWithFallback(
            provider = provider,
            lifecycleOwner = lifecycleOwner,
            frontPreviewView = frontPreview,
            backPreviewView = backPreview,
            currentMode = CameraMode.CONCURRENT,
            sequentialPrimary = SequentialPrimary.FACE,
            resolutionIndex = 0
        )

        // Verify binding logic calls appropriate provider methods
        verify(atLeast = 2) { 
            provider.bindToLifecycle(
                any<LifecycleOwner>(), 
                any<CameraSelector>(), 
                any<UseCase>(), 
                any<UseCase>()
            ) 
        }
    }

    @Test
    fun `bindCamerasWithFallback sequential face mode binds front camera only`() {
        helper.bindCamerasWithFallback(
            provider = provider,
            lifecycleOwner = lifecycleOwner,
            frontPreviewView = frontPreview,
            backPreviewView = backPreview,
            currentMode = CameraMode.SAFE_MODE_SEQUENTIAL,
            sequentialPrimary = SequentialPrimary.FACE,
            resolutionIndex = 0
        )

        verify(exactly = 1) { 
            provider.bindToLifecycle(
                any<LifecycleOwner>(), 
                any<CameraSelector>(), 
                any<UseCase>(), 
                any<UseCase>()
            ) 
        }
    }

    @Test
    fun `bindCamerasWithFallback sequential finger mode binds back camera only`() {
        helper.bindCamerasWithFallback(
            provider = provider,
            lifecycleOwner = lifecycleOwner,
            frontPreviewView = frontPreview,
            backPreviewView = backPreview,
            currentMode = CameraMode.SAFE_MODE_SEQUENTIAL,
            sequentialPrimary = SequentialPrimary.FINGER,
            resolutionIndex = 0
        )

        verify(exactly = 1) { 
            provider.bindToLifecycle(
                any<LifecycleOwner>(), 
                any<CameraSelector>(), 
                any<UseCase>(), 
                any<UseCase>()
            ) 
        }
    }

    @Test
    fun `bindCamerasWithFallback analysis only mode binds both without preview`() {
        helper.bindCamerasWithFallback(
            provider = provider,
            lifecycleOwner = lifecycleOwner,
            frontPreviewView = frontPreview,
            backPreviewView = backPreview,
            currentMode = CameraMode.SAFE_MODE_ANALYSIS_ONLY,
            sequentialPrimary = SequentialPrimary.FACE,
            resolutionIndex = 0
        )

        // Analysis-only binds only ImageAnalysis
        verify(atLeast = 2) { 
            provider.bindToLifecycle(
                any<LifecycleOwner>(), 
                any<CameraSelector>(), 
                any<UseCase>()
            ) 
        }
    }
}
