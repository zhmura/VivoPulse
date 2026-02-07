package com.vivopulse.feature.capture.camera

import android.annotation.SuppressLint
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.UseCaseGroup
import com.vivopulse.feature.capture.model.Source
import com.vivopulse.signal.AppLogger

import kotlin.Pair
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2Interop

/**
 * Helper to manage CameraX lifecycle binding for dual streams.
 * 
 * **MVP Requirements Validation:**
 * - **FR-C1 (Binding Fallback):** Implements the strict fallback hierarchy (Concurrent -> Reduced -> Analysis -> Sequential).
 * - **NFR-R1 (Safe Mode):** Ensures application does not crash on device capability mismatch; downgrades gracefully.
 * - **FR-C3 (Resolution):** Validates and applies target resolution (720p) or supported alternatives.
 * 
 * **Functional Goal:**
 * - Abstract away the complexity of concurrent vs sequential binding.
 * - Provide a unified error handling capability for the initial bind (startup) phase.
 */
@Suppress("DEPRECATION")
@SuppressLint("RestrictedApi")
internal class CameraBindingHelper(
    private val tag: String,
    private val executor: java.util.concurrent.ExecutorService,
    private val processFrame: (androidx.camera.core.ImageProxy, Source) -> Unit,
    private val configurator: Camera2Configurator = Camera2Configurator.Impl()
) {
    
    // Store the last binding error for reporting
    private var _lastBindingError: Exception? = null
    val lastBindingError: Exception? get() = _lastBindingError
    
    /**
     * Attempt to bind both cameras with progressive fallback.
     * 
     * @return Pair of (frontCamera, backCamera) or null if binding fails
     */
    fun bindCamerasWithFallback(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        frontPreviewView: PreviewView,
        backPreviewView: PreviewView,
        currentMode: CameraMode,
        sequentialPrimary: SequentialPrimary,
        resolutionIndex: Int,
        targetFrontId: String? = null,
        targetBackId: String? = null
    ): Pair<Camera?, Camera?>? {
        
        val resolution = getResolutionForIndex(resolutionIndex)
        AppLogger.log(tag, "Attempting camera binding with mode=$currentMode, resolution=$resolution, frontId=$targetFrontId, backId=$targetBackId")
        
        return when (currentMode) {
            CameraMode.CONCURRENT, CameraMode.SAFE_MODE_REDUCED -> {
                bindConcurrent(provider, lifecycleOwner, frontPreviewView, backPreviewView, resolution, targetFrontId, targetBackId)
            }
            CameraMode.SAFE_MODE_ANALYSIS_ONLY -> {
                bindAnalysisOnly(provider, lifecycleOwner, resolution)
            }
            CameraMode.SAFE_MODE_SEQUENTIAL -> {
                bindSequential(
                    provider = provider,
                    lifecycleOwner = lifecycleOwner,
                    frontPreviewView = frontPreviewView,
                    backPreviewView = backPreviewView,
                    sequentialPrimary = sequentialPrimary,
                    resolution = resolution
                )
            }
        }
    }
    
    /**
     * Bind both cameras concurrently.
     */
    private fun bindConcurrent(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        frontPreviewView: PreviewView,
        backPreviewView: PreviewView,
        resolution: Size,
        frontId: String? = null,
        backId: String? = null
    ): Pair<Camera?, Camera?>? {
        val frontSelector = if (frontId != null) {
            createSelectorForId(frontId, CameraSelector.LENS_FACING_FRONT)
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        
        val backSelector = if (backId != null) {
            createSelectorForId(backId, CameraSelector.LENS_FACING_BACK)
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        return try {
            // Check concurrent support
            if (provider.availableConcurrentCameraInfos.isEmpty()) {
                AppLogger.log(tag, "bindConcurrent: No concurrent camera infos found")
                // Fallback or error? Let's treat as error for now in this mode
            }

            AppLogger.log(tag, "bindConcurrent: Configuring cameras at $resolution")

            // Dynamic FPS Range Selection
            val resolutionSize = resolution
            // We need to find the best range for EACH camera, but ideally they match.
            // Since we can't easily query per-camera capabilities here without a CameraInfo, 
            // we will stick to a safer default or try to reuse the logic if we could access CameraCharacteristics.
            // HOWEVER, we are inside bindConcurrent where we don't have easy access to the exact CameraInfo yet 
            // BEFORE binding. 
            //
            // Best approach: Use a safe fixed range like [30, 30] which is universally supported,
            // OR [60, 60] only if we are sure.
            // 
            // Given the issues seen (39fps vs 30fps) and massive frame drops/gaps (2s) when forcing [30, 30],
            // we must relax the lower bound to allow the AE algorithm to increase exposure time without breaking stream.
            // [15, 30] ensures we get AT LEAST 15fps (continuous), whereas [30, 30] caused 0fps (gaps).
            // PPG is valid at >8Hz (Nyquist for 240bpm). 15fps is safe.
            val targetRange = android.util.Range(15, 30)
            AppLogger.log(tag, "Forcing FPS Range: $targetRange")

            // FRONT Config
            val frontPreview = Preview.Builder()
                .setTargetResolution(resolution)
                .build()
                .also { it.setSurfaceProvider(frontPreviewView.surfaceProvider) }
            
            val frontBuilder = ImageAnalysis.Builder()
                .setTargetResolution(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)


            configurator.setTargetFpsRange(frontBuilder, targetRange)

            val frontAnalysis = frontBuilder.build().also { analysis ->
                analysis.setAnalyzer(executor, com.vivopulse.feature.capture.analysis.SafeImageAnalyzer { img -> 
                    // com.vivopulse.signal.AppLogger.log(tag, "Front Frame: ${img.imageInfo.timestamp}")
                    processFrame(img, Source.FACE) 
                })
            }
                
            val frontUseCaseGroup = UseCaseGroup.Builder()
                .addUseCase(frontPreview)
                .addUseCase(frontAnalysis)
                .build()
                
            val frontConfig = ConcurrentCamera.SingleCameraConfig(
                frontSelector,
                frontUseCaseGroup,
                lifecycleOwner
            )

            // BACK Config
            val backPreview = Preview.Builder()
                .setTargetResolution(resolution)
                .build()
                .also { it.setSurfaceProvider(backPreviewView.surfaceProvider) }
            
            val backBuilder = ImageAnalysis.Builder()
                .setTargetResolution(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

            configurator.setTargetFpsRange(backBuilder, targetRange)

            val backAnalysis = backBuilder.build().also { analysis ->
                analysis.setAnalyzer(executor, com.vivopulse.feature.capture.analysis.SafeImageAnalyzer { img -> 
                    processFrame(img, Source.FINGER) 
                })
            }
                
            val backUseCaseGroup = UseCaseGroup.Builder()
                .addUseCase(backPreview)
                .addUseCase(backAnalysis)
                .build()
                
            val backConfig = ConcurrentCamera.SingleCameraConfig(
                backSelector,
                backUseCaseGroup,
                lifecycleOwner
            )
            
            AppLogger.log(tag, "bindConcurrent: Binding BOTH cameras (Unified API)...")
            
            val concurrentCamera = provider.bindToLifecycle(listOf(frontConfig, backConfig))
            
            val cameras = concurrentCamera.cameras
            val frontCamera = cameras.find { it.cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT }
            val backCamera = cameras.find { it.cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK }
            
            if (frontCamera != null) {
                AppLogger.log(tag, "bindConcurrent: Front camera bound successfully")
            }
            if (backCamera != null) {
                AppLogger.log(tag, "bindConcurrent: Back camera bound successfully")
            }
            
            AppLogger.log(tag, "Concurrent binding successful at $resolution")
            Pair(frontCamera, backCamera)
            
        } catch (e: Exception) {
            _lastBindingError = e
            AppLogger.error(tag, "CONCURRENT BINDING FAILED at $resolution: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }
    
    /**
     * Bind both cameras in analysis-only mode (no preview).
     */
    private fun bindAnalysisOnly(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        resolution: Size
    ): Pair<Camera?, Camera?>? {
        return try {
            AppLogger.log(tag, "bindAnalysisOnly: Creating front analysis at $resolution")
            val frontAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { 
                    it.setAnalyzer(executor, com.vivopulse.feature.capture.analysis.SafeImageAnalyzer { img -> 
                        processFrame(img, Source.FACE) 
                    })
                }
            
            AppLogger.log(tag, "bindAnalysisOnly: Binding FRONT camera...")
            val frontCamera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                frontAnalysis
            )
            AppLogger.log(tag, "bindAnalysisOnly: FRONT camera bound successfully")
            
            val backAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { 
                    it.setAnalyzer(executor, com.vivopulse.feature.capture.analysis.SafeImageAnalyzer { img -> 
                        processFrame(img, Source.FINGER) 
                    })
                }
            
            AppLogger.log(tag, "bindAnalysisOnly: Binding BACK camera...")
            val backCamera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                backAnalysis
            )
            AppLogger.log(tag, "bindAnalysisOnly: BACK camera bound successfully")
            
            AppLogger.log(tag, "Analysis-only binding successful at $resolution")
            Pair(frontCamera, backCamera)
            
        } catch (e: Exception) {
            _lastBindingError = e
            AppLogger.error(tag, "ANALYSIS-ONLY BINDING FAILED at $resolution: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * Bind cameras sequentially (back camera only for now).
     */
    private fun bindSequential(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        frontPreviewView: PreviewView,
        backPreviewView: PreviewView,
        sequentialPrimary: SequentialPrimary,
        resolution: Size
    ): Pair<Camera?, Camera?>? {
        return try {
            val useFace = sequentialPrimary == SequentialPrimary.FACE
            val previewView = if (useFace) frontPreviewView else backPreviewView
            val cameraSelector = if (useFace) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            val source = if (useFace) Source.FACE else Source.FINGER

            val preview = Preview.Builder()
                .setTargetResolution(resolution)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { 
                    it.setAnalyzer(executor, com.vivopulse.feature.capture.analysis.SafeImageAnalyzer { img -> 
                        processFrame(img, source) 
                    })
                }
            
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                analysis
            )
            
            AppLogger.log(tag, "Sequential binding successful (${sequentialPrimary.name.lowercase()}) at $resolution")
            return if (useFace) {
                Pair(camera, null)
            } else {
                Pair(null, camera)
            }
            
        } catch (e: Exception) {
            _lastBindingError = e
            AppLogger.error(tag, "SEQUENTIAL BINDING FAILED at $resolution: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }
    
    /**
     * Get resolution for fallback index.
     */
    private fun getResolutionForIndex(index: Int): Size {
        val resolutions = listOf(
            Size(720, 1280),
            Size(640, 480),
            Size(480, 640)
        )
        return resolutions.getOrElse(index) { resolutions.last() }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun createSelectorForId(id: String, facing: Int): CameraSelector {
        return CameraSelector.Builder()
            .requireLensFacing(facing)
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { 
                    try {
                        androidx.camera.camera2.interop.Camera2CameraInfo.from(it).cameraId == id
                    } catch (e: Exception) {
                        false
                    }
                }
            }
            .build()
    }
}
