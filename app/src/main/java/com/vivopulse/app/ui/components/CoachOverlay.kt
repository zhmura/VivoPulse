package com.vivopulse.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vivopulse.feature.processing.coach.TrafficLight

/**
 * AR-style coach overlay for capture screen.
 * 
 * Shows traffic-light badge, top tip, and ROI visualization.
 */
@Composable
fun CoachOverlay(
    trafficLight: TrafficLight,
    topTip: String?,
    faceSqi: Int,
    fingerSqi: Int,
    combinedSqi: Int,
    canStartRecording: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Traffic light badge
        TrafficLightBadge(trafficLight, combinedSqi)
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Top tip
        topTip?.let { tip ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ) {
                Text(
                    text = tip,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // SQI indicators
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SqiIndicator("Face", faceSqi)
            SqiIndicator("Finger", fingerSqi)
        }
        
        // Start readiness indicator
        if (!canStartRecording) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            ) {
                Text(
                    text = "Waiting for good quality...",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun TrafficLightBadge(light: TrafficLight, sqi: Int) {
    val (color, emoji) = when (light) {
        TrafficLight.GREEN -> Color(0xFF4CAF50) to "✓"
        TrafficLight.YELLOW -> Color(0xFFFFA726) to "~"
        TrafficLight.RED -> Color(0xFFF44336) to "✗"
    }
    
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = color.copy(alpha = 0.2f),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = emoji,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Column {
                Text(
                    text = when (light) {
                        TrafficLight.GREEN -> "READY"
                        TrafficLight.YELLOW -> "FAIR"
                        TrafficLight.RED -> "POOR"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                Text(
                    text = "SQI: $sqi",
                    style = MaterialTheme.typography.labelMedium,
                    color = color
                )
            }
        }
    }
}

@Composable
fun SqiIndicator(label: String, sqi: Int) {
    val color = when {
        sqi >= 70 -> Color(0xFF4CAF50)
        sqi >= 50 -> Color(0xFFFFA726)
        else -> Color(0xFFF44336)
    }
    
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.15f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$sqi",
                style = MaterialTheme.typography.labelLarge,
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

/**
 * ROI glow overlay (draws colored rectangle around ROI).
 * 
 * Green = stable, Yellow = reacquiring, Red = lost
 */
@Composable
fun RoiGlowOverlay(
    roiState: RoiGlowState,
    roiRect: androidx.compose.ui.geometry.Rect?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (roiRect != null) {
            val glowColor = when (roiState) {
                RoiGlowState.STABLE -> Color(0xFF4CAF50).copy(alpha = 0.8f)
                RoiGlowState.REACQUIRING -> Color(0xFFFFA726).copy(alpha = 0.8f)
                RoiGlowState.LOST -> Color(0xFFF44336).copy(alpha = 0.8f)
            }
            
            // Draw glowing rectangle
            drawRect(
                color = glowColor,
                topLeft = Offset(roiRect.left, roiRect.top),
                size = Size(roiRect.width, roiRect.height),
                style = Stroke(width = 6f)
            )
            
            // Draw inner glow
            drawRect(
                color = glowColor.copy(alpha = 0.1f),
                topLeft = Offset(roiRect.left, roiRect.top),
                size = Size(roiRect.width, roiRect.height)
            )
        }
    }
}

/**
 * ROI glow state.
 */
enum class RoiGlowState {
    STABLE,
    REACQUIRING,
    LOST
}

/**
 * 3A lock status badge.
 */
@Composable
fun ThreeALockBadge(
    isLocked: Boolean,
    modifier: Modifier = Modifier
) {
    if (isLocked) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.extraSmall,
            color = Color(0xFF4CAF50).copy(alpha = 0.2f)
        ) {
            Text(
                text = "🔒 LOCKED",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )
        }
    }
}



