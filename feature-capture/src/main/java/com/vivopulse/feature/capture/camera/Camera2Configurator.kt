package com.vivopulse.feature.capture.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.ImageAnalysis

/**
 * Interface to wrap Camera2Interop calls for testability.
 * Camera2Interop relies on static methods and internal classes that are hard to mock in Robolectric.
 */
interface Camera2Configurator {
    fun setTargetFpsRange(builder: ImageAnalysis.Builder, range: Range<Int>)
    fun setSessionCaptureCallback(builder: ImageAnalysis.Builder, callback: android.hardware.camera2.CameraCaptureSession.CaptureCallback)
    fun disablePostProcessing(builder: ImageAnalysis.Builder)

    class Impl : Camera2Configurator {
        @SuppressLint("RestrictedApi")
        override fun setTargetFpsRange(builder: ImageAnalysis.Builder, range: Range<Int>) {
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        }

        @SuppressLint("RestrictedApi")
        override fun setSessionCaptureCallback(
            builder: ImageAnalysis.Builder,
            callback: android.hardware.camera2.CameraCaptureSession.CaptureCallback
        ) {
            Camera2Interop.Extender(builder)
                .setSessionCaptureCallback(callback)
        }

        @SuppressLint("RestrictedApi")
        override fun disablePostProcessing(builder: ImageAnalysis.Builder) {
            Camera2Interop.Extender(builder).apply {
                // Disable video stabilization (EIS introduces temporal smoothing)
                setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                // Disable noise reduction (vendor NR can smooth out PPG signal)
                setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF
                )
                // Disable edge enhancement (sharpening adds artifacts)
                setCaptureRequestOption(
                    CaptureRequest.EDGE_MODE,
                    android.hardware.camera2.CameraMetadata.EDGE_MODE_OFF
                )
            }
        }
    }
}
