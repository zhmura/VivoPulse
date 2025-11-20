package com.vivopulse.feature.capture.camera

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.ImageCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 3A (AE/AWB/AF) lock manager with state tracking and logging.
 * 
 * Manages auto-exposure (AE), auto-white-balance (AWB), and auto-focus (AF) locks
 * to stabilize image capture for rPPG signal extraction.
 */
class ThreeALockManager {
    
    private val tag = "3ALockManager"
    
    companion object {
        const val SETTLE_DURATION_MS = 800L  // Wait for 3A to settle before locking
    }
    
    private val _aeState = MutableStateFlow(LockState.UNLOCKED)
    val aeState: StateFlow<LockState> = _aeState.asStateFlow()
    
    private val _awbState = MutableStateFlow(LockState.UNLOCKED)
    val awbState: StateFlow<LockState> = _awbState.asStateFlow()
    
    private val _afState = MutableStateFlow(LockState.FIXED)
    val afState: StateFlow<LockState> = _afState.asStateFlow()
    
    private val _isFullyLocked = MutableStateFlow(false)
    val isFullyLocked: StateFlow<Boolean> = _isFullyLocked.asStateFlow()
    
    private var lockStartTime = 0L
    
    /**
     * Apply initial 3A configuration (unlocked, will lock after settle).
     * 
     * @param builder ImageCapture builder
     * @param cameraRole Face or finger camera
     */
    fun applyInitial3AConfig(
        builder: ImageCapture.Builder,
        cameraRole: CameraRole
    ) {
        Camera2Interop.Extender(builder).apply {
            // AF: Fixed based on camera role
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_OFF
            )
            
            val focusDistance = when (cameraRole) {
                CameraRole.FACE -> 0.0f      // Infinity (face 30-60 cm)
                CameraRole.FINGER -> 10.0f   // Near (finger touching lens)
            }
            
            setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                focusDistance
            )
            
            // Anti-flicker: Set to AUTO (will adapt to 50/60Hz)
            setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
            )
            
            // AE/AWB: Initially unlocked
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
            setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
        }
        
        _aeState.value = LockState.UNLOCKED
        _awbState.value = LockState.UNLOCKED
        _afState.value = LockState.FIXED
        _isFullyLocked.value = false
        
        Log.d(tag, "Applied initial 3A config for $cameraRole: AF=fixed, AE/AWB=unlocked, anti-flicker=AUTO")
    }
    
    /**
     * Start settle timer.
     * 
     * Call when camera preview starts.
     */
    fun startSettleTimer() {
        lockStartTime = System.currentTimeMillis()
        Log.d(tag, "3A settle timer started (${SETTLE_DURATION_MS}ms)")
    }
    
    /**
     * Check if settle period completed and lock if ready.
     * 
     * @return true if lock was applied, false if still settling
     */
    fun checkAndLock(builder: ImageCapture.Builder): Boolean {
        if (_isFullyLocked.value) return true
        
        val now = System.currentTimeMillis()
        val elapsed = now - lockStartTime
        
        if (elapsed < SETTLE_DURATION_MS) {
            return false
        }
        
        // Apply locks
        Camera2Interop.Extender(builder).apply {
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        
        _aeState.value = LockState.LOCKED
        _awbState.value = LockState.LOCKED
        _isFullyLocked.value = true
        
        Log.d(tag, "3A locked: AE=LOCKED, AWB=LOCKED, AF=FIXED")
        return true
    }
    
    /**
     * Unlock 3A (for testing or re-initialization).
     */
    fun unlock(builder: ImageCapture.Builder) {
        Camera2Interop.Extender(builder).apply {
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
            setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
        }
        
        _aeState.value = LockState.UNLOCKED
        _awbState.value = LockState.UNLOCKED
        _isFullyLocked.value = false
        
        Log.d(tag, "3A unlocked")
    }
    
    /**
     * Reset manager.
     */
    fun reset() {
        _aeState.value = LockState.UNLOCKED
        _awbState.value = LockState.UNLOCKED
        _afState.value = LockState.FIXED
        _isFullyLocked.value = false
        lockStartTime = 0L
    }
}

/**
 * 3A lock state.
 */
enum class LockState {
    UNLOCKED,  // Auto-adjusting
    LOCKED,    // Locked at current value
    FIXED      // Fixed at preset value (AF only)
}

/**
 * Camera role for 3A configuration.
 */
enum class CameraRole {
    FACE,
    FINGER
}



