package com.vivopulse.feature.capture.camera

import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.vivopulse.feature.capture.model.Source
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.junit.After
import org.junit.Before
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
    private val configurator = mockk<Camera2Configurator>(relaxed = true)

    private val helper = CameraBindingHelper("TestTag", executor, processFrame, configurator)

    @Before
    fun setUp() {
        // Mock CameraX builders so they don't throw under Robolectric
        mockkConstructor(Preview.Builder::class)
        mockkConstructor(ImageAnalysis.Builder::class)
        mockkConstructor(UseCaseGroup.Builder::class)

        val mockPreview = mockk<Preview>(relaxed = true)
        val mockAnalysis = mockk<ImageAnalysis>(relaxed = true)
        val mockUseCaseGroup = mockk<UseCaseGroup>(relaxed = true)

        every { anyConstructed<Preview.Builder>().setTargetResolution(any<Size>()) } returns mockk(relaxed = true) {
            every { build() } returns mockPreview
        }
        every { mockPreview.setSurfaceProvider(any()) } returns Unit

        every { anyConstructed<ImageAnalysis.Builder>().setTargetResolution(any<Size>()) } returns mockk(relaxed = true) {
            every { setBackpressureStrategy(any()) } returns mockk(relaxed = true) {
                every { setOutputImageFormat(any()) } returns mockk<ImageAnalysis.Builder>(relaxed = true) {
                    every { build() } returns mockAnalysis
                }
            }
        }
        every { mockAnalysis.setAnalyzer(any(), any()) } returns Unit

        every { anyConstructed<UseCaseGroup.Builder>().addUseCase(any()) } returns mockk(relaxed = true) {
            every { addUseCase(any()) } returns mockk(relaxed = true) {
                every { build() } returns mockUseCaseGroup
            }
        }

        // Make ConcurrentCamera binding succeed
        val mockConcurrentCamera = mockk<ConcurrentCamera>(relaxed = true)
        val mockCamera = mockk<Camera>(relaxed = true)
        every { mockConcurrentCamera.cameras } returns listOf(mockCamera, mockCamera)
        every {
            provider.bindToLifecycle(any<List<ConcurrentCamera.SingleCameraConfig>>())
        } returns mockConcurrentCamera
    }

    @After
    fun tearDown() {
        unmockkConstructor(Preview.Builder::class)
        unmockkConstructor(ImageAnalysis.Builder::class)
        unmockkConstructor(UseCaseGroup.Builder::class)
    }

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

        // Concurrent uses the list overload
        verify(atLeast = 1) { 
            provider.bindToLifecycle(any<List<ConcurrentCamera.SingleCameraConfig>>())
        }
        
        // FPS applied to both front and back builders
        verify(exactly = 2) { 
            configurator.setTargetFpsRange(any(), any())
        }

        // Post-processing disabled on both builders  
        verify(exactly = 2) {
            configurator.disablePostProcessing(any())
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

        // Post-processing disabled on the single builder
        verify(exactly = 1) {
            configurator.disablePostProcessing(any())
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

        // Post-processing disabled on the single builder
        verify(exactly = 1) {
            configurator.disablePostProcessing(any())
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

        // Analysis-only binds only ImageAnalysis (2 cameras)
        verify(atLeast = 2) { 
            provider.bindToLifecycle(
                any<LifecycleOwner>(), 
                any<CameraSelector>(), 
                any<UseCase>()
            ) 
        }

        // Post-processing disabled on both builders
        verify(exactly = 2) {
            configurator.disablePostProcessing(any())
        }
    }
}
