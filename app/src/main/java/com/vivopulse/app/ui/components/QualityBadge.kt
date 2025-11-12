package com.vivopulse.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Quality score badge component.
 */
@Composable
fun QualityBadge(label: String, score: Double) {
    val color = when {
        score >= 80 -> Color(0xFF4CAF50) // Green
        score >= 70 -> Color(0xFF2196F3) // Blue
        score >= 50 -> Color(0xFFFFC107) // Yellow
        else -> Color(0xFFF44336) // Red
    }
    
    Card(
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = String.format("%.0f", score),
                style = MaterialTheme.typography.titleLarge,
                color = color
            )
        }
    }
}

