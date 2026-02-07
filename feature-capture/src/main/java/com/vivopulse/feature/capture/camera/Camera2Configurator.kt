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

    class Impl : Camera2Configurator {
        @SuppressLint("RestrictedApi")
        override fun setTargetFpsRange(builder: ImageAnalysis.Builder, range: Range<Int>) {
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        }
    }
}
