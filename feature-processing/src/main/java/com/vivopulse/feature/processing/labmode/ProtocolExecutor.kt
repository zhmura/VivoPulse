package com.vivopulse.feature.processing.labmode

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Protocol executor for lab mode.
 * 
 * Manages protocol timing, phase transitions, and provides real-time state updates.
 */
class ProtocolExecutor(private val protocol: Protocol) {
    
    private val _currentPhaseIndex = MutableStateFlow(0)
    val currentPhaseIndex: StateFlow<Int> = _currentPhaseIndex.asStateFlow()
    
    private val _phaseElapsedS = MutableStateFlow(0)
    val phaseElapsedS: StateFlow<Int> = _phaseElapsedS.asStateFlow()
    
    private val _phaseRemainingS = MutableStateFlow(protocol.phases[0].durationS)
    val phaseRemainingS: StateFlow<Int> = _phaseRemainingS.asStateFlow()
    
    private val _totalElapsedS = MutableStateFlow(0)
    val totalElapsedS: StateFlow<Int> = _totalElapsedS.asStateFlow()
    
    private val _totalRemainingS = MutableStateFlow(protocol.totalDurationS)
    val totalRemainingS: StateFlow<Int> = _totalRemainingS.asStateFlow()
    
    private val _isComplete = MutableStateFlow(false)
    val isComplete: StateFlow<Boolean> = _isComplete.asStateFlow()
    
    private var sessionStartTime: Long = 0
    
    /**
     * Get current phase.
     */
    fun getCurrentPhase(): Phase {
        return protocol.phases[_currentPhaseIndex.value]
    }
    
    /**
     * Start protocol execution.
     */
    fun start() {
        sessionStartTime = System.currentTimeMillis()
        _currentPhaseIndex.value = 0
        _phaseElapsedS.value = 0
        _phaseRemainingS.value = protocol.phases[0].durationS
        _totalElapsedS.value = 0
        _totalRemainingS.value = protocol.totalDurationS
        _isComplete.value = false
    }
    
    /**
     * Update timer (call every second).
     * 
     * @return true if phase changed, false otherwise
     */
    fun updateTimer(): Boolean {
        if (_isComplete.value) return false
        
        val now = System.currentTimeMillis()
        val elapsedS = ((now - sessionStartTime) / 1000).toInt()
        
        _totalElapsedS.value = elapsedS
        _totalRemainingS.value = (protocol.totalDurationS - elapsedS).coerceAtLeast(0)
        
        // Calculate current phase based on elapsed time
        var cumulativeDuration = 0
        var newPhaseIndex = 0
        var phaseStartS = 0
        
        for (i in protocol.phases.indices) {
            val phaseDuration = protocol.phases[i].durationS
            if (elapsedS < cumulativeDuration + phaseDuration) {
                newPhaseIndex = i
                phaseStartS = cumulativeDuration
                break
            }
            cumulativeDuration += phaseDuration
        }
        
        // Check if protocol complete
        if (elapsedS >= protocol.totalDurationS) {
            _isComplete.value = true
            _totalElapsedS.value = protocol.totalDurationS
            _totalRemainingS.value = 0
            return false
        }
        
        // Check for phase change
        val phaseChanged = newPhaseIndex != _currentPhaseIndex.value
        if (phaseChanged) {
            _currentPhaseIndex.value = newPhaseIndex
        }
        
        // Update phase timers
        _phaseElapsedS.value = elapsedS - phaseStartS
        _phaseRemainingS.value = (protocol.phases[newPhaseIndex].durationS - (elapsedS - phaseStartS)).coerceAtLeast(0)
        
        return phaseChanged
    }
    
    /**
     * Get phase tags for a given timestamp.
     * 
     * @param timestampMs Timestamp in milliseconds
     * @return Phase ID at that timestamp, or null if outside protocol
     */
    fun getPhaseAtTime(timestampMs: Long): String? {
        val elapsedMs = timestampMs - sessionStartTime
        if (elapsedMs < 0 || elapsedMs > protocol.totalDurationS * 1000) {
            return null
        }
        
        val elapsedS = (elapsedMs / 1000).toInt()
        var cumulativeDuration = 0
        
        for (phase in protocol.phases) {
            if (elapsedS < cumulativeDuration + phase.durationS) {
                return phase.id
            }
            cumulativeDuration += phase.durationS
        }
        
        return null
    }
    
    /**
     * Get all phase transitions (start/end times).
     */
    fun getPhaseTransitions(): List<PhaseTransition> {
        val transitions = mutableListOf<PhaseTransition>()
        var cumulativeS = 0
        
        for (phase in protocol.phases) {
            transitions.add(
                PhaseTransition(
                    phaseId = phase.id,
                    phaseName = phase.name,
                    startS = cumulativeS,
                    endS = cumulativeS + phase.durationS,
                    durationS = phase.durationS
                )
            )
            cumulativeS += phase.durationS
        }
        
        return transitions
    }
    
    /**
     * Reset executor.
     */
    fun reset() {
        _currentPhaseIndex.value = 0
        _phaseElapsedS.value = 0
        _phaseRemainingS.value = protocol.phases[0].durationS
        _totalElapsedS.value = 0
        _totalRemainingS.value = protocol.totalDurationS
        _isComplete.value = false
        sessionStartTime = 0
    }
}

/**
 * Phase transition.
 */
data class PhaseTransition(
    val phaseId: String,
    val phaseName: String,
    val startS: Int,
    val endS: Int,
    val durationS: Int
)

