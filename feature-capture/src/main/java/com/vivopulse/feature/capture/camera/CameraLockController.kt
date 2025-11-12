package com.vivopulse.feature.capture.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.ImageCapture

/**
 * Camera 3A (AE/AWB/AF) lock controller for stable capture.
 * 
 * Locks exposure and white balance after initial convergence to prevent
 * mid-session variations that corrupt PPG signals.
 */
class CameraLockController {
    
    private val tag = "CameraLockController"
    
    /**
     * Camera role for different lock strategies.
     */
    enum class CameraRole {
        FACE,    // Front camera - lock AE/AWB, AF to infinity
        FINGER   // Back camera - lock AE/AWB, AF to near/macro
    }
    
    /**
     * Apply 3A locks for face camera (front).
     * 
     * - AF: Fixed to infinity (face is ~30-60 cm)
     * - AE: Locked after initial convergence
     * - AWB: Locked after initial convergence
     */
    fun applyFaceCameraLocks(builder: ImageCapture.Builder) {
        Camera2Interop.Extender(builder).apply {
            // AF: Fixed focus at infinity (relaxed for face distance)
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_OFF
            )
            setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                0.0f // Infinity
            )
            
            // AE: Lock after convergence (will be locked via capture request later)
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_LOCK,
                false // Initial state, will lock after warmup
            )
            
            // AWB: Lock after convergence
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_LOCK,
                false // Initial state, will lock after warmup
            )
        }
        
        Log.d(tag, "Applied face camera locks: AF=infinity, AE/AWB=unlocked (will lock after warmup)")
    }
    
    /**
     * Apply 3A locks for finger camera (back).
     * 
     * - AF: Fixed to near/macro distance (finger touching lens)
     * - AE: Locked after initial convergence
     * - AWB: Locked after initial convergence (with torch, typically warm)
     */
    fun applyFingerCameraLocks(builder: ImageCapture.Builder) {
        Camera2Interop.Extender(builder).apply {
            // AF: Fixed focus at minimum distance (finger touching lens)
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_OFF
            )
            setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                10.0f // Near focus (diopters, higher = closer)
            )
            
            // AE: Will lock after torch warmup
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_LOCK,
                false
            )
            
            // AWB: Will lock after torch warmup
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_LOCK,
                false
            )
        }
        
        Log.d(tag, "Applied finger camera locks: AF=near, AE/AWB=unlocked (will lock after warmup)")
    }
    
    /**
     * Lock AE/AWB after warmup period.
     * 
     * Call this after 2-3 seconds of capture to lock exposure and white balance.
     */
    fun lockExposureAndWhiteBalance(builder: ImageCapture.Builder) {
        Camera2Interop.Extender(builder).apply {
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_LOCK,
                true
            )
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_LOCK,
                true
            )
        }
        
        Log.d(tag, "Locked AE/AWB")
    }
}

