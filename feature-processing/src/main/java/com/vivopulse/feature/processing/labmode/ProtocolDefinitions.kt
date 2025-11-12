package com.vivopulse.feature.processing.labmode

/**
 * Lab mode protocol definitions.
 * 
 * Guided scenarios for vascular reactivity assessment without external calibration.
 */
object ProtocolDefinitions {
    
    /**
     * Protocol A: Paced Breathing
     * 
     * Baseline (30s) → Paced Breathing 6/min (30s) → Recovery (30s)
     * 
     * Expected: PTT↑ during slow breathing (parasympathetic activation)
     */
    val PROTOCOL_A_PACED_BREATHING = Protocol(
        id = "protocol_a",
        name = "Paced Breathing",
        description = "Breathe slowly to assess autonomic reactivity",
        totalDurationS = 90,
        phases = listOf(
            Phase(
                id = "baseline",
                name = "Baseline",
                description = "Breathe normally, relax",
                durationS = 30,
                instructions = "Sit comfortably and breathe normally",
                metronome = null
            ),
            Phase(
                id = "paced_breathing",
                name = "Slow Breathing",
                description = "Follow breathing guide: 6 breaths per minute",
                durationS = 30,
                instructions = "Inhale for 5s, exhale for 5s (follow visual guide)",
                metronome = MetronomeConfig(
                    bpm = 6, // 6 breaths per minute
                    inhaleDurationS = 5.0,
                    exhaleDurationS = 5.0
                )
            ),
            Phase(
                id = "recovery",
                name = "Recovery",
                description = "Return to normal breathing",
                durationS = 30,
                instructions = "Breathe normally again, relax",
                metronome = null
            )
        ),
        expectedChanges = ExpectedChanges(
            pttDirection = mapOf(
                "baseline" to "paced_breathing" to Direction.INCREASE,
                "paced_breathing" to "recovery" to Direction.DECREASE
            ),
            hrDirection = mapOf(
                "baseline" to "paced_breathing" to Direction.DECREASE,
                "paced_breathing" to "recovery" to Direction.INCREASE
            )
        )
    )
    
    /**
     * Protocol B: Mini-Orthostatic
     * 
     * Sit (30s) → Stand (30s) → Stand (30s)
     * 
     * Expected: PTT↓ on standing (sympathetic activation, vasoconstriction)
     */
    val PROTOCOL_B_ORTHOSTATIC = Protocol(
        id = "protocol_b",
        name = "Mini-Orthostatic",
        description = "Sit-to-stand challenge to assess vascular reactivity",
        totalDurationS = 90,
        phases = listOf(
            Phase(
                id = "sit",
                name = "Seated",
                description = "Remain seated, relax",
                durationS = 30,
                instructions = "Sit comfortably, keep device steady",
                metronome = null
            ),
            Phase(
                id = "stand_early",
                name = "Stand (Early)",
                description = "Transition to standing position",
                durationS = 30,
                instructions = "Stand up slowly and remain standing",
                metronome = null
            ),
            Phase(
                id = "stand_late",
                name = "Stand (Late)",
                description = "Continue standing",
                durationS = 30,
                instructions = "Keep standing, hold device steady",
                metronome = null
            )
        ),
        expectedChanges = ExpectedChanges(
            pttDirection = mapOf(
                "sit" to "stand_early" to Direction.DECREASE,
                "stand_early" to "stand_late" to Direction.STABLE
            ),
            hrDirection = mapOf(
                "sit" to "stand_early" to Direction.INCREASE,
                "stand_early" to "stand_late" to Direction.STABLE
            )
        )
    )
    
    /**
     * Get all available protocols.
     */
    fun getAllProtocols(): List<Protocol> {
        return listOf(
            PROTOCOL_A_PACED_BREATHING,
            PROTOCOL_B_ORTHOSTATIC
        )
    }
    
    /**
     * Get protocol by ID.
     */
    fun getProtocolById(id: String): Protocol? {
        return getAllProtocols().find { it.id == id }
    }
}

/**
 * Protocol definition.
 */
data class Protocol(
    val id: String,
    val name: String,
    val description: String,
    val totalDurationS: Int,
    val phases: List<Phase>,
    val expectedChanges: ExpectedChanges
)

/**
 * Protocol phase.
 */
data class Phase(
    val id: String,
    val name: String,
    val description: String,
    val durationS: Int,
    val instructions: String,
    val metronome: MetronomeConfig?
)

/**
 * Metronome configuration for paced breathing.
 */
data class MetronomeConfig(
    val bpm: Int,                // Breaths per minute
    val inhaleDurationS: Double, // Inhale duration
    val exhaleDurationS: Double  // Exhale duration
)

/**
 * Expected physiological changes.
 */
data class ExpectedChanges(
    val pttDirection: Map<Pair<String, String>, Direction>,  // phase1 to phase2 → expected direction
    val hrDirection: Map<Pair<String, String>, Direction>
)

/**
 * Change direction enum.
 */
enum class Direction {
    INCREASE,   // Expected to increase
    DECREASE,   // Expected to decrease
    STABLE      // Expected to remain stable
}

