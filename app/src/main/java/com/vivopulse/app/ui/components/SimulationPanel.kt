package com.vivopulse.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vivopulse.feature.processing.simulation.SimulationConfig

/**
 * Control panel for simulated mode parameters.
 */
@Composable
fun SimulationPanel(
    config: SimulationConfig,
    onConfigChange: (SimulationConfig) -> Unit,
    onRunSimulation: () -> Unit
) {
    var heartRateBPM by remember { mutableStateOf(config.getHeartRateBPM()) }
    var pttMs by remember { mutableStateOf(config.pttLagMs.toInt()) }
    var noiseEnabled by remember { mutableStateOf(config.noiseEnabled) }
    var noiseLevel by remember { mutableStateOf(config.noiseLevel.toFloat()) }
    var driftEnabled by remember { mutableStateOf(config.driftEnabled) }
    var driftRate by remember { mutableStateOf(config.driftRate.toFloat()) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🎮 Simulation Parameters",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Heart Rate
            Text(
                "Heart Rate: $heartRateBPM BPM",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = heartRateBPM.toFloat(),
                onValueChange = { heartRateBPM = it.toInt() },
                valueRange = 45f..180f,
                steps = 26
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // PTT Lag
            Text(
                "PTT Lag: $pttMs ms",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = pttMs.toFloat(),
                onValueChange = { pttMs = it.toInt() },
                valueRange = 50f..150f,
                steps = 19
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Noise toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Add Noise", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = noiseEnabled,
                    onCheckedChange = { noiseEnabled = it }
                )
            }
            
            if (noiseEnabled) {
                Text(
                    "Noise Level: ${(noiseLevel * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
                Slider(
                    value = noiseLevel,
                    onValueChange = { noiseLevel = it },
                    valueRange = 0f..0.5f
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Drift toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Add Drift", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = driftEnabled,
                    onCheckedChange = { driftEnabled = it }
                )
            }
            
            if (driftEnabled) {
                Text(
                    "Drift Rate: ${(driftRate * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
                Slider(
                    value = driftRate,
                    onValueChange = { driftRate = it },
                    valueRange = 0f..0.1f
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Preset buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val preset = SimulationConfig.ideal()
                        heartRateBPM = preset.getHeartRateBPM()
                        pttMs = preset.pttLagMs.toInt()
                        noiseEnabled = preset.noiseEnabled
                        driftEnabled = preset.driftEnabled
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Ideal", style = MaterialTheme.typography.labelSmall)
                }
                
                OutlinedButton(
                    onClick = {
                        val preset = SimulationConfig.challenging()
                        heartRateBPM = preset.getHeartRateBPM()
                        pttMs = preset.pttLagMs.toInt()
                        noiseEnabled = preset.noiseEnabled
                        noiseLevel = preset.noiseLevel.toFloat()
                        driftEnabled = preset.driftEnabled
                        driftRate = preset.driftRate.toFloat()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Challenge", style = MaterialTheme.typography.labelSmall)
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Apply button
            Button(
                onClick = {
                    val newConfig = SimulationConfig(
                        heartRateHz = heartRateBPM / 60.0,
                        pttLagMs = pttMs.toDouble(),
                        durationSeconds = config.durationSeconds,
                        captureRateHz = config.captureRateHz,
                        noiseEnabled = noiseEnabled,
                        noiseLevel = noiseLevel.toDouble(),
                        driftEnabled = driftEnabled,
                        driftRate = driftRate.toDouble()
                    )
                    onConfigChange(newConfig)
                    onRunSimulation()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Run Simulation")
            }
        }
    }
}

