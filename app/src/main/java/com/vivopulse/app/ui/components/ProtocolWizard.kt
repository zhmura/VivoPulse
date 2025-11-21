package com.vivopulse.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vivopulse.feature.processing.labmode.Phase
import com.vivopulse.feature.processing.labmode.Protocol

/**
 * Protocol wizard for lab mode guided scenarios.
 */
@Composable
fun ProtocolWizard(
    protocol: Protocol,
    currentPhaseIndex: Int,
    phaseElapsedS: Int,
    @Suppress("UNUSED_PARAMETER") phaseRemainingS: Int,
    totalElapsedS: Int,
    @Suppress("UNUSED_PARAMETER") totalRemainingS: Int,
    isComplete: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Protocol title
            Text(
                text = protocol.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Phase progress indicators
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                protocol.phases.forEachIndexed { index, phase ->
                    PhaseIndicator(
                        phase = phase,
                        isActive = index == currentPhaseIndex,
                        isComplete = index < currentPhaseIndex
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (!isComplete) {
                // Current phase info
                val currentPhase = protocol.phases[currentPhaseIndex]
                
                Text(
                    text = currentPhase.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = currentPhase.instructions,
                    style = MaterialTheme.typography.bodyLarge
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Breathing metronome (if applicable)
                currentPhase.metronome?.let { metronome ->
                    BreathingMetronome(
                        config = metronome,
                        elapsedS = phaseElapsedS
                    )
                }
                
                // Phase timer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Phase", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "${phaseElapsedS}s / ${currentPhase.durationS}s",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Total", style = MaterialTheme.typography.labelSmall)
                        Text(
                            "${totalElapsedS}s / ${protocol.totalDurationS}s",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                // Progress bar
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = phaseElapsedS.toFloat() / currentPhase.durationS.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = "✅ Protocol Complete!",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun PhaseIndicator(
    phase: Phase,
    isActive: Boolean,
    isComplete: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = when {
                        isActive -> MaterialTheme.colorScheme.primary
                        isComplete -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    isComplete -> "✓"
                    isActive -> "•"
                    else -> ""
                },
                color = when {
                    isActive || isComplete -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = phase.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun BreathingMetronome(
    config: com.vivopulse.feature.processing.labmode.MetronomeConfig,
    elapsedS: Int
) {
    val cycleDurationS = config.inhaleDurationS + config.exhaleDurationS
    val cycleProgress = (elapsedS % cycleDurationS.toInt()).toDouble() / cycleDurationS
    
    val isInhaling = cycleProgress < (config.inhaleDurationS / cycleDurationS)
    
    // Animate circle size
    val animatedScale by animateFloatAsState(
        targetValue = if (isInhaling) 1.5f else 0.8f,
        animationSpec = tween(
            durationMillis = if (isInhaling) {
                (config.inhaleDurationS * 1000).toInt()
            } else {
                (config.exhaleDurationS * 1000).toInt()
            },
            easing = LinearEasing
        ),
        label = "breathing_animation"
    )
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Breathing circle
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(animatedScale)
                .background(
                    color = if (isInhaling) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isInhaling) "Inhale" else "Exhale",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isInhaling) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                }
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "${config.bpm} breaths/min",
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolSelector(
    protocols: List<Protocol>,
    onProtocolSelected: (Protocol) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "Select Lab Mode Protocol",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        protocols.forEach { protocol ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                onClick = { onProtocolSelected(protocol) }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = protocol.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = protocol.description,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Duration: ${protocol.totalDurationS}s (${protocol.phases.size} phases)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
fun ReactivityBadge(
    level: com.vivopulse.feature.processing.labmode.ReactivityLevel,
    modifier: Modifier = Modifier
) {
    val (text, color) = when (level) {
        com.vivopulse.feature.processing.labmode.ReactivityLevel.NORMAL -> "NORMAL" to Color(0xFF4CAF50)
        com.vivopulse.feature.processing.labmode.ReactivityLevel.LOW -> "LOW" to Color(0xFFFFA726)
        com.vivopulse.feature.processing.labmode.ReactivityLevel.BLUNTED -> "BLUNTED" to Color(0xFFF44336)
        com.vivopulse.feature.processing.labmode.ReactivityLevel.UNKNOWN -> "UNKNOWN" to Color(0xFF9E9E9E)
    }
    
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

