package com.vivopulse.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vivopulse.app.viewmodel.ProcessingViewModel
import com.vivopulse.feature.processing.SessionSummary
import com.vivopulse.feature.processing.reactivity.PhaseResult
import com.vivopulse.feature.processing.reactivity.ReactivityProtocolAnalyzer
import com.vivopulse.feature.processing.reactivity.ReactivityProtocolSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactivityProtocolScreen(
    onNavigateBack: () -> Unit,
    onNavigateToCapture: () -> Unit,
    viewModel: ProcessingViewModel = hiltViewModel()
) {
    val sessionSummary by viewModel.sessionSummary.collectAsState()
    val qualityReport by viewModel.qualityReport.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()

    // Phase storage
    var restSummary by remember { mutableStateOf<SessionSummary?>(null) }
    var postSummary by remember { mutableStateOf<SessionSummary?>(null) }
    var recoverySummary by remember { mutableStateOf<SessionSummary?>(null) }
    var restQuality by remember { mutableStateOf<Double?>(null) }       // combined SQI
    var postQuality by remember { mutableStateOf<Double?>(null) }
    var recoveryQuality by remember { mutableStateOf<Double?>(null) }
    var restPttConf by remember { mutableStateOf<Double?>(null) }       // PTT confidence percent
    var postPttConf by remember { mutableStateOf<Double?>(null) }
    var recoveryPttConf by remember { mutableStateOf<Double?>(null) }

    // Analysis
    var summary by remember { mutableStateOf<ReactivityProtocolSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vascular Reactivity (experimental)") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Intro
            Text(
                "Guided protocol to see how your cardiovascular system reacts & returns towards baseline. " +
                        "No diagnostic claims.",
                style = MaterialTheme.typography.bodyMedium
            )

            // Step 1: Rest
            PhaseCard(
                title = "Step 1 — Rest measurement (30–40s)",
                description = "Sit or stand still. Record both cameras, then process.",
                onRecord = onNavigateToCapture,
                onProcess = { viewModel.processFrames() },
                processing = isProcessing,
                onSave = {
                    if (sessionSummary != null && qualityReport != null) {
                        restSummary = sessionSummary
                        restQuality = qualityReport!!.combinedScore
                        restPttConf = qualityReport!!.pttConfidence
                    }
                },
                isSaved = restSummary != null
            )

            // Step 2: Light load
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Step 2 — Light load (30–60s)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Do 20–30 squats or brisk stepping in place. Then proceed to post-load measurement.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Step 3: Post-load measurement
            PhaseCard(
                title = "Step 3 — Post-load measurement (30–40s)",
                description = "Record both cameras right after activity, then process.",
                onRecord = onNavigateToCapture,
                onProcess = { viewModel.processFrames() },
                processing = isProcessing,
                onSave = {
                    if (sessionSummary != null && qualityReport != null) {
                        postSummary = sessionSummary
                        postQuality = qualityReport!!.combinedScore
                        postPttConf = qualityReport!!.pttConfidence
                    }
                },
                isSaved = postSummary != null
            )

            // Optional Step 4: Recovery
            PhaseCard(
                title = "Optional — Recovery measurement (after 3–5 min rest)",
                description = "Rest for 3–5 minutes, then record and process.",
                onRecord = onNavigateToCapture,
                onProcess = { viewModel.processFrames() },
                processing = isProcessing,
                onSave = {
                    if (sessionSummary != null && qualityReport != null) {
                        recoverySummary = sessionSummary
                        recoveryQuality = qualityReport!!.combinedScore
                        recoveryPttConf = qualityReport!!.pttConfidence
                    }
                },
                isSaved = recoverySummary != null,
                optional = true
            )

            // Analyze button
            Button(
                onClick = {
                    val restPhase = restSummary?.let {
                        PhaseResult("Rest", it, restPttConf, restQuality)
                    }
                    val postPhase = postSummary?.let {
                        PhaseResult("Post-Load", it, postPttConf, postQuality)
                    }
                    val recPhase = recoverySummary?.let {
                        PhaseResult("Recovery", it, recoveryPttConf, recoveryQuality)
                    }
                    summary = ReactivityProtocolAnalyzer.analyze(restPhase, postPhase, recPhase)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = restSummary != null && postSummary != null
            ) {
                Text("Analyze Reactivity")
            }

            // Results
            summary?.let { s ->
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Results", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        ArrowMetric("PTT", s.deltaPttMs, "ms", higherIs="increase", lowerIs="decrease")
                        Spacer(Modifier.height(4.dp))
                        ArrowMetric("HR", s.deltaHrBpm, "bpm", higherIs="increase", lowerIs="decrease")
                        Spacer(Modifier.height(4.dp))
                        ArrowMetric("Rise time", s.deltaRiseTimeMs, "ms", higherIs="longer", lowerIs="shorter")
                        Spacer(Modifier.height(4.dp))
                        ArrowMetric("Reflection ratio", s.deltaReflectionRatio, "", higherIs="higher", lowerIs="lower")
                        Spacer(Modifier.height(12.dp))
                        if (s.recoveryScore != null) {
                            Text("Recovery: ${s.recoveryScore}% returned towards rest", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text("Recovery: not measured", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        val reliabilityText = when (s.reliability) {
                            com.vivopulse.feature.processing.reactivity.Reliability.COMPLETE -> "complete"
                            com.vivopulse.feature.processing.reactivity.Reliability.LOW -> "incomplete / low reliability"
                            com.vivopulse.feature.processing.reactivity.Reliability.INCOMPLETE -> "incomplete"
                        }
                        Text("Reliability: $reliabilityText", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Interpretation: stronger or weaker change; faster or slower return vs your own rest. No diagnostic claims.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseCard(
    title: String,
    description: String,
    onRecord: () -> Unit,
    onProcess: () -> Unit,
    processing: Boolean,
    onSave: () -> Unit,
    isSaved: Boolean,
    optional: Boolean = false
) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRecord,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Record")
                }
                Button(
                    onClick = onProcess,
                    modifier = Modifier.weight(1f),
                    enabled = !processing
                ) {
                    Text(if (processing) "Processing..." else "Process")
                }
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isSaved) "Saved ✓" else "Save")
                }
            }
            if (optional) {
                Spacer(Modifier.height(4.dp))
                Text("Optional step", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ArrowMetric(
    label: String,
    delta: Double?,
    unit: String,
    higherIs: String,
    lowerIs: String
) {
    val text = when {
        delta == null -> "—"
        delta > 0 -> "↑ ${String.format("%.1f", delta)} $unit (${higherIs})"
        delta < 0 -> "↓ ${String.format("%.1f", -delta)} $unit (${lowerIs})"
        else -> "→ 0 $unit (stable)"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}


