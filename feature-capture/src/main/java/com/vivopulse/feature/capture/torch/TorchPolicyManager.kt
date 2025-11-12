package com.vivopulse.feature.capture.torch

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Torch usage policy manager.
 * 
 * Enforces torch usage limits to prevent device overheating and battery drain.
 */
class TorchPolicyManager {
    
    private val tag = "TorchPolicyManager"
    
    companion object {
        const val MAX_TORCH_DURATION_MS = 60_000L // 60 seconds
        const val COOLDOWN_DURATION_MS = 30_000L  // 30 seconds between sessions
    }
    
    private var torchStartTime: Long = 0
    private var lastTorchOffTime: Long = 0
    
    private val _torchState = MutableStateFlow(TorchState.OFF)
    val torchState: StateFlow<TorchState> = _torchState.asStateFlow()
    
    private val _elapsedTimeMs = MutableStateFlow(0L)
    val elapsedTimeMs: StateFlow<Long> = _elapsedTimeMs.asStateFlow()
    
    private val _remainingTimeMs = MutableStateFlow(MAX_TORCH_DURATION_MS)
    val remainingTimeMs: StateFlow<Long> = _remainingTimeMs.asStateFlow()
    
    /**
     * Check if torch can be enabled.
     */
    fun canEnableTorch(): TorchEnableResult {
        val now = System.currentTimeMillis()
        
        // Check cooldown
        if (lastTorchOffTime > 0) {
            val timeSinceLast = now - lastTorchOffTime
            if (timeSinceLast < COOLDOWN_DURATION_MS) {
                val cooldownRemaining = COOLDOWN_DURATION_MS - timeSinceLast
                return TorchEnableResult.Cooldown(cooldownRemaining)
            }
        }
        
        return TorchEnableResult.Allowed
    }
    
    /**
     * Start torch session.
     */
    fun startTorch(): Boolean {
        val result = canEnableTorch()
        if (result !is TorchEnableResult.Allowed) {
            Log.w(tag, "Cannot enable torch: $result")
            return false
        }
        
        torchStartTime = System.currentTimeMillis()
        _torchState.value = TorchState.ON
        _elapsedTimeMs.value = 0
        _remainingTimeMs.value = MAX_TORCH_DURATION_MS
        
        Log.d(tag, "Torch started")
        return true
    }
    
    /**
     * Stop torch session.
     */
    fun stopTorch() {
        if (_torchState.value == TorchState.OFF) return
        
        lastTorchOffTime = System.currentTimeMillis()
        _torchState.value = TorchState.OFF
        _elapsedTimeMs.value = 0
        _remainingTimeMs.value = MAX_TORCH_DURATION_MS
        
        Log.d(tag, "Torch stopped")
    }
    
    /**
     * Update torch timer.
     * 
     * Call periodically (e.g., every 500ms) to update elapsed/remaining time.
     * Returns true if torch should be force-disabled due to timeout.
     */
    fun updateTimer(): Boolean {
        if (_torchState.value != TorchState.ON) return false
        
        val now = System.currentTimeMillis()
        val elapsed = now - torchStartTime
        _elapsedTimeMs.value = elapsed
        
        val remaining = MAX_TORCH_DURATION_MS - elapsed
        _remainingTimeMs.value = remaining.coerceAtLeast(0)
        
        // Check if max duration exceeded
        if (elapsed >= MAX_TORCH_DURATION_MS) {
            Log.w(tag, "Torch max duration exceeded, force-disabling")
            _torchState.value = TorchState.TIMEOUT
            return true
        }
        
        // Warn at 10 seconds remaining
        if (remaining in 9000..11000 && remaining % 1000 < 500) {
            Log.d(tag, "Torch warning: ${remaining / 1000}s remaining")
        }
        
        return false
    }
    
    /**
     * Reset policy (for testing).
     */
    fun reset() {
        torchStartTime = 0
        lastTorchOffTime = 0
        _torchState.value = TorchState.OFF
        _elapsedTimeMs.value = 0
        _remainingTimeMs.value = MAX_TORCH_DURATION_MS
    }
}

/**
 * Torch state.
 */
enum class TorchState {
    OFF,
    ON,
    TIMEOUT
}

/**
 * Torch enable result.
 */
sealed class TorchEnableResult {
    object Allowed : TorchEnableResult()
    data class Cooldown(val remainingMs: Long) : TorchEnableResult()
}

