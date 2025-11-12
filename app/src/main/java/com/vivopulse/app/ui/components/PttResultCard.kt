package com.vivopulse.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vivopulse.feature.processing.ptt.PttOutput

/**
 * PTT result card with confidence-based display.
 * 
 * Shows PTT if confidence ≥ 0.60, otherwise shows guidance.
 */
@Composable
fun PttResultCard(
    pttOutput: PttOutput,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (pttOutput.isPttReportable()) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pulse Transit Time",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                ConfidenceBadge(
                    confidence = pttOutput.confidence,
                    level = pttOutput.getConfidenceLevel()
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (pttOutput.isPttReportable()) {
                // Show PTT result
                PttValueDisplay(pttOutput)
            } else {
                // Show low confidence warning
                LowConfidenceWarning(pttOutput)
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Quality metrics
            QualityMetricsRow(pttOutput)
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Heart rate comparison
            HeartRateComparisonRow(pttOutput)
        }
    }
}

@Composable
fun PttValueDisplay(pttOutput: PttOutput) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = String.format("%.1f ms", pttOutput.pttMs ?: 0.0),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "PTT",
                style = MaterialTheme.typography.labelSmall
            )
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format("%.3f", pttOutput.corrScore),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Correlation",
                style = MaterialTheme.typography.labelSmall
            )
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format("%.3f", pttOutput.peakSharpness),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Sharpness",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun LowConfidenceWarning(pttOutput: PttOutput) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "PTT Confidence Too Low (${String.format("%.0f", pttOutput.confidence * 100)}%)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        pttOutput.guidance?.let { tips ->
            Column {
                Text(
                    text = "Suggestions:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                tips.forEach { tip ->
                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("• ", style = MaterialTheme.typography.bodyMedium)
                        Text(tip, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun QualityMetricsRow(pttOutput: PttOutput) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        MetricChip(
            label = "Face SQI",
            value = "${pttOutput.sqiFace}",
            color = getSqiColor(pttOutput.sqiFace)
        )
        MetricChip(
            label = "Finger SQI",
            value = "${pttOutput.sqiFinger}",
            color = getSqiColor(pttOutput.sqiFinger)
        )
        MetricChip(
            label = "Confidence",
            value = "${(pttOutput.confidence * 100).toInt()}%",
            color = getConfidenceColor(pttOutput.confidence)
        )
    }
}

@Composable
fun HeartRateComparisonRow(pttOutput: PttOutput) {
    val agreement = pttOutput.hrAgreementGood()
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format("%.0f", pttOutput.hrFaceBpm),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Face HR (bpm)",
                style = MaterialTheme.typography.labelSmall
            )
        }
        
        Icon(
            if (agreement) Icons.Default.Info else Icons.Default.Warning,
            contentDescription = null,
            tint = if (agreement) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.size(20.dp)
        )
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format("%.0f", pttOutput.hrFingerBpm),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Finger HR (bpm)",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
    
    if (!agreement) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "⚠️ HR mismatch >5 bpm - check signal quality",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
fun MetricChip(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.2f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
fun ConfidenceBadge(
    confidence: Double,
    level: String
) {
    val (color, icon) = when (level) {
        "High" -> androidx.compose.ui.graphics.Color(0xFF4CAF50) to "✓"
        "Moderate" -> androidx.compose.ui.graphics.Color(0xFFFFA726) to "~"
        "Low" -> androidx.compose.ui.graphics.Color(0xFFF44336) to "!"
        else -> androidx.compose.ui.graphics.Color(0xFF9E9E9E) to "?"
    }
    
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.2f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$level (${(confidence * 100).toInt()}%)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

fun getSqiColor(sqi: Int): androidx.compose.ui.graphics.Color {
    return when {
        sqi >= 80 -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
        sqi >= 70 -> androidx.compose.ui.graphics.Color(0xFF8BC34A)
        sqi >= 50 -> androidx.compose.ui.graphics.Color(0xFFFFA726)
        else -> androidx.compose.ui.graphics.Color(0xFFF44336)
    }
}

fun getConfidenceColor(confidence: Double): androidx.compose.ui.graphics.Color {
    return when {
        confidence >= 0.80 -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
        confidence >= 0.60 -> androidx.compose.ui.graphics.Color(0xFFFFA726)
        else -> androidx.compose.ui.graphics.Color(0xFFF44336)
    }
}

