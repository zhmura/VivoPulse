package com.vivopulse.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vivopulse.app.ui.components.QualityBadge
import com.vivopulse.app.viewmodel.ProcessingViewModel
import com.vivopulse.app.ui.education.EducationTextProvider
import com.vivopulse.feature.processing.PttResult
import com.vivopulse.feature.processing.SessionSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProcessingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val pttResult by viewModel.pttResult.collectAsState()
    val processedSeries by viewModel.processedSeries.collectAsState()
    val qualityReport by viewModel.qualityReport.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val waveProfile by viewModel.waveProfile.collectAsState()
    val wavePatternHint by viewModel.wavePatternHint.collectAsState()
    val vascularTrend by viewModel.vascularTrend.collectAsState()
    val sessionSummary by viewModel.sessionSummary.collectAsState()
    val exportPath by viewModel.exportPath.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val performanceReport by viewModel.performanceReport.collectAsState()
    val biomarkers by viewModel.biomarkers.collectAsState()
    
    var showDiagnostics by remember { mutableStateOf(false) }
    
    // Show toast when export completes
    LaunchedEffect(exportPath) {
        if (exportPath != null) {
            Toast.makeText(
                context,
                "Exported to:\n$exportPath",
                Toast.LENGTH_LONG
            ).show()
            viewModel.clearExportPath()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Results") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (pttResult == null) Arrangement.Center else Arrangement.Top
        ) {
            if (pttResult != null && processedSeries != null) {
                val ptt = pttResult!!
                val series = processedSeries!!
                
                // Title
                Text(
                    text = "Analysis Results",
                    style = MaterialTheme.typography.headlineMedium
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // PTT Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (ptt.getQuality()) {
                            PttResult.Quality.EXCELLENT -> MaterialTheme.colorScheme.primaryContainer
                            PttResult.Quality.GOOD -> MaterialTheme.colorScheme.tertiaryContainer
                            PttResult.Quality.FAIR -> MaterialTheme.colorScheme.secondaryContainer
                            PttResult.Quality.POOR -> MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Pulse Transit Time (PTT)",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Icon(
                                imageVector = when (ptt.getQuality()) {
                                    PttResult.Quality.EXCELLENT, PttResult.Quality.GOOD -> Icons.Default.CheckCircle
                                    else -> Icons.Default.Warning
                                },
                                contentDescription = null,
                                tint = when (ptt.getQuality()) {
                                    PttResult.Quality.EXCELLENT -> Color(0xFF4CAF50)
                                    PttResult.Quality.GOOD -> Color(0xFF2196F3)
                                    PttResult.Quality.FAIR -> Color(0xFFFFC107)
                                    PttResult.Quality.POOR -> Color(0xFFF44336)
                                }
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // PTT Value
                        Text(
                            text = String.format("%.1f ms", ptt.pttMs),
                            style = MaterialTheme.typography.displayMedium
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Quality indicator
                        Text(
                            text = "Quality: ${ptt.getQuality().name}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Divider()
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Metrics
                        MetricRow("Correlation", String.format("%.3f", ptt.correlationScore))
                        Spacer(modifier = Modifier.height(8.dp))
                        MetricRow("Stability", String.format("%.1f ms", ptt.stabilityMs))
                        if (ptt.windowCount > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            MetricRow("Windows Analyzed", "${ptt.windowCount}")
                        }
                        
                        // GoodSync Stats (Placeholder for now, assuming ptt object has these fields or we add them)
                        // For now, we'll just show a placeholder if we can't modify PttResult easily.
                        // Ideally: MetricRow("GoodSync Windows", "${ptt.goodSyncCount} (${String.format("%.0f", ptt.goodSyncPct)}%)")
                        Spacer(modifier = Modifier.height(8.dp))
                        MetricRow("GoodSync Share", "N/A (Pending)") // Placeholder until we wire up the data
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Status messages
                        if (!ptt.isPlausible) {
                            Text(
                                text = "⚠️ PTT outside typical range (50-150 ms)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (!ptt.isStable) {
                            Text(
                                text = "⚠️ PTT stability > 25 ms (variable)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (ptt.isReliable && ptt.isPlausible && ptt.isStable) {
                            Text(
                                text = "✓ Reliable PTT estimate",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Quality Report Card
                if (qualityReport != null) {
                    val quality = qualityReport!!
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (quality.isGoodQuality) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Signal Quality",
                                style = MaterialTheme.typography.titleMedium
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Quality scores
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                QualityBadge("Face", quality.faceSQI.score)
                                QualityBadge("Finger", quality.fingerSQI.score)
                                QualityBadge("Combined", quality.combinedScore)
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            MetricRow("PTT Confidence", String.format("%.0f%%", quality.pttConfidence))
                            
                            // Suggestions
                            if (quality.suggestions.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Divider()
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                Text(
                                    "Suggestions to Improve Quality:",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (quality.shouldRetry) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                quality.suggestions.forEach { suggestion ->
                                    Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                        Text("• ", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            suggestion,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Vascular wave (experimental) - shown only when baseline built and current session high quality
                val summaryHint = sessionSummary?.wavePatternHint ?: wavePatternHint
                if (vascularTrend != null && summaryHint != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Vascular wave (experimental)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            MetricRow(
                                label = "Wave pattern hint",
                                value = summaryHint
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            MetricRow(
                                label = "Vascular Trend Index",
                                value = "${vascularTrend!!.index} (compared to your previous measurements)"
                            )
                            // Optionally show summary wave metrics if present
                            sessionSummary?.waveProfile?.let { wp ->
                                val rise = wp.meanRiseTimeMs?.let { String.format("%.0f ms", it) } ?: "—"
                                val refl = wp.meanReflectionRatio?.let { String.format("%.2f", it) } ?: "—"
                                Spacer(modifier = Modifier.height(8.dp))
                                Divider()
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Wave metrics",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                MetricRow("Mean rise time", rise)
                                Spacer(modifier = Modifier.height(4.dp))
                                MetricRow("Mean reflection ratio", refl)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Biomarkers (experimental) - shown when quality ok
                if (qualityReport?.isGoodQuality == true && biomarkers != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Biomarkers (experimental)", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            MetricRow("Heart rate", String.format("%.1f bpm", biomarkers!!.hrBpm))
                            biomarkers!!.sdnnMs?.let {
                                Spacer(modifier = Modifier.height(4.dp))
                                val sdnnStr = String.format("%.0f ms", it)
                                val rmssdStr = biomarkers!!.rmssdMs?.let { r -> ", RMSSD ${String.format("%.0f ms", r)}" } ?: ""
                                MetricRow(
                                    "Heart rhythm variability (not diagnostic)",
                                    "SDNN $sdnnStr$rmssdStr"
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            MetricRow(
                                "Respiratory modulation",
                                if (biomarkers!!.respiratoryModulationDetected) "detected" else "not detected"
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Signal Info Card
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Signal Information", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        MetricRow("Duration", String.format("%.1f s", series.getDurationSeconds()))
                        Spacer(modifier = Modifier.height(4.dp))
                        MetricRow("Sample Rate", "${series.sampleRateHz.toInt()} Hz")
                        Spacer(modifier = Modifier.height(4.dp))
                        MetricRow("Sample Count", "${series.getSampleCount()}")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Session Diagnostics (expandable)
                if (performanceReport != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (performanceReport!!.isWithinBudget) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Session Diagnostics",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                                    Text(if (showDiagnostics) "Hide" else "Show")
                                }
                            }
                            
                            if (showDiagnostics) {
                                Spacer(modifier = Modifier.height(12.dp))
                                
                                val report = performanceReport!!
                                
                                Text(
                                    report.getBudgetStatus(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (report.isWithinBudget) {
                                        Color(0xFF4CAF50)
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                Divider()
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                MetricRow("Mean Processing Time", String.format("%.2f ms", report.meanProcessingMs))
                                Spacer(modifier = Modifier.height(4.dp))
                                MetricRow("95th Percentile", String.format("%.2f ms", report.p95ProcessingMs))
                                Spacer(modifier = Modifier.height(4.dp))
                                MetricRow("Max Processing Time", "${report.maxProcessingMs} ms")
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                MetricRow("Memory Footprint", String.format("%.1f MB", report.memoryFootprintMB))
                                Spacer(modifier = Modifier.height(4.dp))
                                MetricRow("Peak Memory", String.format("%.1f MB", report.peakMemoryMB))
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Budget indicators
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (report.meanProcessingMs <= 8.0) {
                                                Color(0xFF4CAF50).copy(alpha = 0.2f)
                                            } else {
                                                Color(0xFFF44336).copy(alpha = 0.2f)
                                            }
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                if (report.meanProcessingMs <= 8.0) "✓" else "⚠️",
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                "CPU",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                    
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (report.peakMemoryMB <= 200.0) {
                                                Color(0xFF4CAF50).copy(alpha = 0.2f)
                                            } else {
                                                Color(0xFFF44336).copy(alpha = 0.2f)
                                            }
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(8.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                if (report.peakMemoryMB <= 200.0) "✓" else "⚠️",
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                "Memory",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Limitations (always visible)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Important notes", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            EducationTextProvider.limitationsShort,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            EducationTextProvider.limitationsDetails,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Export button
                Button(
                    onClick = { viewModel.exportData() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isExporting
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Exporting...")
                    } else {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export Data (ZIP)")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Action buttons
                if (qualityReport?.shouldRetry == true) {
                    Button(
                        onClick = onNavigateBack,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Re-Record (Quality Too Low)")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Keep Results")
                    }
                } else {
                    OutlinedButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("New Capture")
                    }
                }
            } else {
                // No results yet
                Text(
                    text = "No analysis results",
                    style = MaterialTheme.typography.headlineMedium
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Process a recording to see PTT analysis",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(
                    onClick = onNavigateBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Return to Capture")
                }
            }
        }
    }
}

@Composable
fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}


