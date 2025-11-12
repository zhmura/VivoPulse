package com.vivopulse.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vivopulse.app.ui.components.SimulationPanel
import com.vivopulse.app.util.FeatureFlags
import com.vivopulse.app.viewmodel.ProcessingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    onNavigateToResult: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: ProcessingViewModel = hiltViewModel()
) {
    val processedSeries by viewModel.processedSeries.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val showRaw by viewModel.showRawSignal.collectAsState()
    val simulationConfig by viewModel.simulationConfig.collectAsState()
    
    var showSimPanel by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        // Auto-start processing on screen load
        viewModel.processFrames()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Processing") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (FeatureFlags.isSimulatedModeEnabled()) {
                        TextButton(onClick = { showSimPanel = !showSimPanel }) {
                            Text("⚙️ Sim")
                        }
                    }
                    if (processedSeries != null) {
                        TextButton(onClick = { viewModel.toggleRawFiltered() }) {
                            Text(if (showRaw) "Filtered" else "Raw")
                        }
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
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isProcessing) {
                // Processing indicator
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Processing signals...")
                }
            } else if (processedSeries != null) {
                // Signal preview
                val series = processedSeries!!
                
                Text(
                    text = if (showRaw) "Raw Signals" else "Filtered Signals",
                    style = MaterialTheme.typography.headlineSmall
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "${series.getSampleCount()} samples at ${series.sampleRateHz.toInt()} Hz " +
                           "(${String.format("%.1f", series.getDurationSeconds())}s)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Simulation panel (if enabled and open)
                if (FeatureFlags.isSimulatedModeEnabled() && showSimPanel) {
                    SimulationPanel(
                        config = simulationConfig,
                        onConfigChange = { viewModel.updateSimulationConfig(it) },
                        onRunSimulation = { viewModel.processFrames() }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Face signal chart
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "Face Signal",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        SignalChart(
                            signal = if (showRaw) series.rawFaceSignal else series.faceSignal,
                            color = Color(0xFF2196F3), // Blue
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Finger signal chart
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "Finger Signal",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        SignalChart(
                            signal = if (showRaw) series.rawFingerSignal else series.fingerSignal,
                            color = Color(0xFFE91E63), // Pink
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = onNavigateToResult,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View Results & Analysis")
                }
            } else {
                // No data
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "No signal data",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onNavigateBack) {
                        Text("Return to Capture")
                    }
                }
            }
        }
    }
}

@Composable
fun SignalChart(
    signal: DoubleArray,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (signal.isEmpty()) {
        Box(modifier = modifier) {
            Text(
                "No data",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }
    
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        
        // Find signal range
        val minValue = signal.minOrNull() ?: -1.0
        val maxValue = signal.maxOrNull() ?: 1.0
        val range = maxValue - minValue
        
        if (range < 1e-10) {
            // Constant signal
            return@Canvas
        }
        
        // Build path
        val path = Path()
        
        signal.forEachIndexed { index, value ->
            val x = (index.toFloat() / (signal.size - 1)) * width
            val y = height - ((value - minValue) / range * height * 0.9f + height * 0.05f).toFloat()
            
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        
        // Draw signal path
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2f)
        )
        
        // Draw zero line if signal crosses zero
        if (minValue < 0 && maxValue > 0) {
            val zeroY = height - ((0.0 - minValue) / range * height * 0.9f + height * 0.05f).toFloat()
            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(0f, zeroY),
                end = Offset(width, zeroY),
                strokeWidth = 1f
            )
        }
    }
}


