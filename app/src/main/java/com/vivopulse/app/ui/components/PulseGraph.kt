package com.vivopulse.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.vivopulse.feature.processing.sync.GoodSyncSegment

/**
 * Reusable graph component for PPG signals.
 * 
 * @param data Signal data points
 * @param timeMillis Corresponding time points in milliseconds (as List<Double>)
 * @param peaks Indices of detected peaks
 * @param goodSyncSegments List of GoodSync segments to highlight
 * @param graphColor Color of the signal line
 * @param modifier Modifier for layout
 */
@Composable
fun PulseGraph(
    data: DoubleArray,
    timeMillis: List<Double>,
    peaks: Set<Int> = emptySet(),
    goodSyncSegments: List<GoodSyncSegment> = emptyList(),
    graphColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty() || timeMillis.isEmpty()) return

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val width = size.width
            val height = size.height
            
            // 1. Calculate scales
            val minVal = data.minOrNull() ?: 0.0
            val maxVal = data.maxOrNull() ?: 1.0
            val rangeVal = (maxVal - minVal).coerceAtLeast(0.0001)
            
            val startTimeMs = timeMillis.first()
            val endTimeMs = timeMillis.last()
            val durationMs = (endTimeMs - startTimeMs).coerceAtLeast(1.0)
            
            // 2. Draw GoodSync overlays (Background)
            goodSyncSegments.forEach { segment ->
                // GoodSyncSegment.window contains tStartMs and tEndMs in Long ms
                val segStartMs = segment.window.tStartMs.toDouble()
                val segEndMs = segment.window.tEndMs.toDouble()
                
                // Convert to relative position in the graph (0..1 mapped to 0..width)
                val startX = ((segStartMs - startTimeMs) / durationMs * width).toFloat()
                val endX = ((segEndMs - startTimeMs) / durationMs * width).toFloat()
                
                drawRect(
                    color = Color.Green.copy(alpha = 0.15f),
                    topLeft = Offset(startX.coerceAtLeast(0f), 0f),
                    size = androidx.compose.ui.geometry.Size((endX - startX).coerceAtLeast(0f), height)
                )
            }

            // 3. Draw Signal
            val path = Path()
            data.forEachIndexed { i, value ->
                // Map time to X
                val t = timeMillis.getOrNull(i) ?: startTimeMs
                val relTime = t - startTimeMs
                val x = (relTime / durationMs * width).toFloat()
                
                // Map value to Y (inverted because canvas Y is down)
                val normalizedY = (value - minVal) / rangeVal
                val y = (height - (normalizedY * height * 0.8 + height * 0.1)).toFloat() // 10% padding
                
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                
                // Draw Peak
                if (i in peaks) {
                    drawCircle(
                        color = Color.Red,
                        radius = 3.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }

            drawPath(
                path = path,
                color = graphColor,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
