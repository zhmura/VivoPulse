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
import com.vivopulse.feature.capture.model.Source

/**
 * Camera binding helper with progressive fallback logic.
 * 
 * Implements the fallback strategy:
 * 1. Try concurrent mode at target resolution
 * 2. Retry with smaller resolution
 * 3. Retry with YUV-only (no preview)
 * 4. Fall back to sequential mode
 */
@SuppressLint("RestrictedApi")
internal class CameraBindingHelper(
    private val tag: String,
    private val executor: java.util.concurrent.ExecutorService,
    private val processFrame: (androidx.camera.core.ImageProxy, Source) -> Unit
) {
    
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
        resolutionIndex: Int
    ): Pair<androidx.camera.core.Camera?, androidx.camera.core.Camera?>? {
        
        val resolution = getResolutionForIndex(resolutionIndex)
        Log.d(tag, "Attempting camera binding with mode=$currentMode, resolution=$resolution")
        
        return when (currentMode) {
            CameraMode.CONCURRENT, CameraMode.SAFE_MODE_REDUCED -> {
                bindConcurrent(provider, lifecycleOwner, frontPreviewView, backPreviewView, resolution)
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
        resolution: Size
    ): Pair<androidx.camera.core.Camera?, androidx.camera.core.Camera?>? {
        return try {
            // Front camera
            val frontPreview = Preview.Builder()
                .setTargetResolution(resolution)
                .build()
                .also { it.setSurfaceProvider(frontPreviewView.surfaceProvider) }
            
            val frontAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(executor) { img -> processFrame(img, Source.FACE) } }
            
            val frontCamera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                frontPreview,
                frontAnalysis
            )
            
            // Back camera
            val backPreview = Preview.Builder()
                .setTargetResolution(resolution)
                .build()
                .also { it.setSurfaceProvider(backPreviewView.surfaceProvider) }
            
            val backAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(executor) { img -> processFrame(img, Source.FINGER) } }
            
            val backCamera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                backPreview,
                backAnalysis
            )
            
            Log.d(tag, "Concurrent binding successful at $resolution")
            Pair(frontCamera, backCamera)
            
        } catch (e: Exception) {
            Log.w(tag, "Concurrent binding failed at $resolution", e)
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
    ): Pair<androidx.camera.core.Camera?, androidx.camera.core.Camera?>? {
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
                .also { it.setAnalyzer(executor) { img -> processFrame(img, source) } }
            
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                analysis
            )
            
            Log.d(tag, "Sequential binding successful (${sequentialPrimary.name.lowercase()}) at $resolution")
            return if (useFace) {
                Pair(camera, null)
            } else {
                Pair(null, camera)
            }
            
        } catch (e: Exception) {
            Log.w(tag, "Sequential binding failed at $resolution", e)
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
}
