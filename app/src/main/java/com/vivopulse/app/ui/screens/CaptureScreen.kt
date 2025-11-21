package com.vivopulse.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vivopulse.app.util.DeviceWhitelist
import com.vivopulse.app.util.FeatureFlags
import com.vivopulse.app.viewmodel.CaptureViewModel
import com.vivopulse.feature.capture.camera.CameraMode
import com.vivopulse.feature.capture.camera.SequentialPrimary
import com.vivopulse.feature.capture.roi.RoiOverlayView
import com.vivopulse.feature.processing.realtime.ChannelQualityIndicator
import com.vivopulse.feature.processing.realtime.QualityStatus
import com.vivopulse.feature.processing.realtime.RealTimeQualityState
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCamera2Interop::class)
@Composable
fun CaptureScreen(
    onNavigateToProcessing: () -> Unit,
    onNavigateToReactivity: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val isDeviceSupported = DeviceWhitelist.isDeviceSupported()
    var showDebugMenu by remember { mutableStateOf(false) }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
    }
    
    // Request camera permission on first launch
    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Re-check permission on resume to handle revocation in settings
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                cameraPermissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    val isRecording by viewModel.isRecording.collectAsState()
    val torchEnabled by viewModel.torchEnabled.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val drift by viewModel.drift.collectAsState()
    val isDriftValid by viewModel.isDriftValid.collectAsState()
    val faceRoi by viewModel.faceRoi.collectAsState()
    val lastResult by viewModel.lastRecordingResult.collectAsState()
    val cameraMode by viewModel.cameraMode.collectAsState()
    val sequentialPrimary by viewModel.sequentialPrimary.collectAsState()
    val qualityState by viewModel.qualityState.collectAsState()
    val controller = viewModel.getCameraController()
    val statusBanner by controller.statusBanner.collectAsState(initial = null)
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dual Camera Capture") },
                actions = {
                    // FPS and drift indicator
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = "F:${String.format("%.0f", fps.first)} B:${String.format("%.0f", fps.second)} fps",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = if (isDriftValid) {
                                "Drift: ${String.format("%.2f", drift)} ms/s"
                            } else {
                                "Drift: n/a"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDriftValid && drift > 10.0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(onClick = { showDebugMenu = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Debug Menu")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!cameraPermissionGranted) {
                // Permission request screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Camera,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Camera Permission Required",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "VivoPulse needs camera access to capture PPG signals",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }) {
                        Text("Grant Permission")
                    }
                }
            } else {
                // Main camera UI
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Device support banner
                    if (!isDeviceSupported) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = "⚠️ Device not optimized for dual camera capture",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    
                    // Controller status banner (safe mode, errors)
                    if (statusBanner != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Text(
                                text = statusBanner!!,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    
                    val sequentialModeActive = cameraMode == CameraMode.SAFE_MODE_SEQUENTIAL
                    if (sequentialModeActive) {
                        SequentialModeCard(
                            selected = sequentialPrimary,
                            enabled = !isRecording,
                            onSelectionChanged = { viewModel.setSequentialPrimary(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    
                    // Simulated mode banner
                    if (FeatureFlags.isSimulatedModeEnabled()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Text(
                                text = "⚠️ Simulated Mode Active",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    
                    // Dual camera previews
                    var frontPreviewView by remember { mutableStateOf<PreviewView?>(null) }
                    var backPreviewView by remember { mutableStateOf<PreviewView?>(null) }
                    
                    // Start cameras when both preview views are ready
                    LaunchedEffect(frontPreviewView, backPreviewView, cameraPermissionGranted, sequentialPrimary) {
                        if (cameraPermissionGranted && frontPreviewView != null && backPreviewView != null) {
                            viewModel.getCameraController().startCamera(
                                lifecycleOwner = lifecycleOwner,
                                frontPreviewView = frontPreviewView!!,
                                backPreviewView = backPreviewView!!
                            )
                        }
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                    // Rolling buffers for live waveforms
                    val faceWave = remember { mutableStateListOf<Double>() }
                    val fingerWave = remember { mutableStateListOf<Double>() }
                    val maxSamples = 300
                    LaunchedEffect(Unit) {
                        controller.faceWave.collect { v ->
                            faceWave.add(v)
                            if (faceWave.size > maxSamples) {
                                repeat(faceWave.size - maxSamples) { faceWave.removeAt(0) }
                            }
                        }
                    }
                    LaunchedEffect(Unit) {
                        controller.fingerWave.collect { v ->
                            fingerWave.add(v)
                            if (fingerWave.size > maxSamples) {
                                repeat(fingerWave.size - maxSamples) { fingerWave.removeAt(0) }
                            }
                        }
                    }
                    
                        // Front camera preview (Face) with ROI overlay
                        CameraPreviewCard(
                            title = "Face (Front)",
                        modifier = Modifier.weight(1f),
                            showRoiOverlay = true,
                        faceRoi = faceRoi,
                        waveform = faceWave
                        ) { previewView ->
                            frontPreviewView = previewView
                        }
                        
                        // Back camera preview (Finger)
                        CameraPreviewCard(
                            title = "Finger (Back)",
                            modifier = Modifier.weight(1f),
                        showTorchIndicator = torchEnabled,
                        waveform = fingerWave
                        ) { previewView ->
                            backPreviewView = previewView
                        }
                    }
                    
                    qualityState?.let {
                        QualityIndicatorsSection(
                            state = it,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // Recording stats
                    if (lastResult != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Last Recording:",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Captured ${lastResult!!.frames.size} frames " +
                                            "(Face: ${lastResult!!.stats.faceStats.framesReceived}, " +
                                            "Finger: ${lastResult!!.stats.fingerStats.framesReceived})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "Avg FPS - Face: ${String.format("%.1f", lastResult!!.stats.faceStats.averageFps)}, " +
                                            "Finger: ${String.format("%.1f", lastResult!!.stats.fingerStats.averageFps)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                if (lastResult!!.stats.totalDropped > 0) {
                                    Text(
                                        text = "⚠️ Dropped ${lastResult!!.stats.totalDropped} frames",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                    
                    // Controls
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Recording duration
                            if (isRecording) {
                                val seconds = (recordingDuration / 1000).toInt()
                                Text(
                                    text = String.format("Recording: %02d:%02d", seconds / 60, seconds % 60),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            
                            // Control buttons
                            val torchButtonEnabled = !isRecording && !(sequentialModeActive && sequentialPrimary == SequentialPrimary.FACE)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Torch toggle
                                OutlinedButton(
                                    onClick = { viewModel.toggleTorch() },
                                    modifier = Modifier.weight(1f),
                                    enabled = torchButtonEnabled
                                ) {
                                    Icon(
                                        imageVector = if (torchEnabled) Icons.Default.FlashlightOn 
                                                      else Icons.Default.FlashlightOff,
                                        contentDescription = "Torch"
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (torchEnabled) "Torch On" else "Torch Off")
                                }
                                
                                // Start/Stop recording
                                Button(
                                    onClick = {
                                        if (isRecording) {
                                            viewModel.stopRecording()
                                        } else {
                                            viewModel.startRecording()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = if (isRecording) {
                                        ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        )
                                    } else {
                                        ButtonDefaults.buttonColors()
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isRecording) Icons.Default.Stop 
                                                      else Icons.Default.FiberManualRecord,
                                        contentDescription = if (isRecording) "Stop" else "Start"
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isRecording) "Stop" else "Start")
                                }
                            }
                            
                            if (!torchButtonEnabled && sequentialModeActive && sequentialPrimary == SequentialPrimary.FACE) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Torch is unavailable when using the face camera.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Process button
                            Button(
                                onClick = {
                                    if (lastResult != null) {
                                        // Recording available, navigate to processing
                                        onNavigateToProcessing()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = lastResult != null && !isRecording
                            ) {
                                Icon(Icons.Default.ArrowForward, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (lastResult != null) {
                                    val lumaCount = lastResult!!.frames.count { it.hasLuma() }
                                    "Process ${lumaCount} Frames with Luma"
                                } else {
                                    "Process Captured Frames"
                                })
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Entry to Reactivity protocol
                            OutlinedButton(
                                onClick = { onNavigateToReactivity() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Vascular Reactivity (experimental)")
                            }
                        }
                    }
                }
            }
            
            // Debug menu
            if (showDebugMenu) {
                DebugMenu(
                    onDismiss = { showDebugMenu = false },
                    isConcurrentSupported = viewModel.isConcurrentCameraSupported()
                )
            }
        }
    }
}

@Composable
fun CameraPreviewCard(
    title: String,
    modifier: Modifier = Modifier,
    showTorchIndicator: Boolean = false,
    showRoiOverlay: Boolean = false,
    faceRoi: com.vivopulse.feature.capture.roi.FaceRoi? = null,
    waveform: List<Double>? = null,
    onPreviewViewCreated: (PreviewView) -> Unit = {}
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Title bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (showTorchIndicator) {
                        Icon(
                            imageVector = Icons.Default.FlashlightOn,
                            contentDescription = "Torch on",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            
            // Camera preview with optional ROI overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { context ->
                        if (showRoiOverlay) {
                            // Create FrameLayout to hold preview + overlay
                            FrameLayout(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                
                                // Add preview
                                val preview = PreviewView(context).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                                addView(preview)
                                
                                // Add ROI overlay
                                val overlay = RoiOverlayView(context).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                                addView(overlay)
                                
                                onPreviewViewCreated(preview)
                            }
                        } else {
                            PreviewView(context).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                onPreviewViewCreated(this)
                            }
                        }
                    },
                    update = { view ->
                        if (showRoiOverlay && view is FrameLayout && view.childCount > 1) {
                            val overlay = view.getChildAt(1) as? RoiOverlayView
                            overlay?.updateRoi(faceRoi)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // Waveform overlay (lightweight)
                if (waveform != null && waveform.isNotEmpty()) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        val path = Path()
                        val w = size.width
                        val h = size.height
                        val data = waveform
                        val minV = data.minOrNull() ?: 0.0
                        val maxV = data.maxOrNull() ?: 1.0
                        val range = (maxV - minV).let { if (it < 1e-9) 1.0 else it }
                        val count = data.size
                        for (i in data.indices) {
                            val x = (i.toFloat() / (count - 1).coerceAtLeast(1)) * w
                            val yNorm = ((data[i] - minV) / range).toFloat()
                            val y = h - (yNorm * h * 0.4f + h * 0.05f) // top band
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFF80DEEA), // teal accent
                            style = Stroke(width = 2f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityIndicatorsSection(
    state: RealTimeQualityState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        state.tip?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Text(
                    text = it,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChannelQualityCard(
                title = "Face channel",
                indicator = state.face,
                modifier = Modifier.weight(1f)
            )
            ChannelQualityCard(
                title = "Finger channel",
                indicator = state.finger,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ChannelQualityCard(
    title: String,
    indicator: ChannelQualityIndicator,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val statusColor = statusColor(indicator.status, colors)
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(statusColor, shape = MaterialTheme.shapes.small)
                )
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
            indicator.snrDb?.let {
                QualityMetricRow("SNR", "${String.format("%.1f", it)} dB")
            }
            indicator.motionRmsPx?.let {
                QualityMetricRow("Motion", "${String.format("%.2f", it)} px")
            }
            indicator.saturationPct?.let {
                QualityMetricRow("Saturation", "${String.format("%.1f", it * 100)} %")
            }
            indicator.hrEstimateBpm?.let {
                QualityMetricRow("HR", "${String.format("%.0f", it)} bpm")
            }
            indicator.acDcRatio?.let {
                QualityMetricRow("AC/DC", String.format("%.2f", it))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                val sparkline = indicator.sparkline
                if (sparkline.size >= 2) {
                    val path = Path()
                    sparkline.forEachIndexed { index, value ->
                        val x = (index.toFloat() / (sparkline.size - 1)) * size.width
                        val y = size.height - (value.toFloat() * size.height)
                        if (index == 0) {
                            path.moveTo(x, y)
                        } else {
                            path.lineTo(x, y)
                        }
                    }
                    drawPath(path, statusColor, style = Stroke(width = 2.dp.toPx()))
                }
            }
            if (indicator.diagnostics.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = indicator.diagnostics.first(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QualityMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun statusColor(status: QualityStatus, colors: ColorScheme): Color {
    return when (status) {
        QualityStatus.GREEN -> colors.primary
        QualityStatus.YELLOW -> colors.tertiary
        QualityStatus.RED -> colors.error
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SequentialModeCard(
    selected: SequentialPrimary,
    enabled: Boolean,
    onSelectionChanged: (SequentialPrimary) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Sequential capture order", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose which channel you measure first on devices that cannot stream both cameras.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selected == SequentialPrimary.FINGER,
                    onClick = { onSelectionChanged(SequentialPrimary.FINGER) },
                    enabled = enabled,
                    label = { Text("Finger first") }
                )
                FilterChip(
                    selected = selected == SequentialPrimary.FACE,
                    onClick = { onSelectionChanged(SequentialPrimary.FACE) },
                    enabled = enabled,
                    label = { Text("Face first") }
                )
            }
        }
    }
}

@Composable
fun DebugMenu(
    onDismiss: () -> Unit,
    isConcurrentSupported: Boolean
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debug Menu") },
        text = {
            Column {
                var simulatedMode by remember { 
                    mutableStateOf(FeatureFlags.isSimulatedModeEnabled()) 
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Simulated Mode", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Generate synthetic PPG signals",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = simulatedMode,
                        onCheckedChange = { 
                            simulatedMode = it
                            FeatureFlags.setSimulatedModeEnabled(it)
                        }
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Divider()
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Supported: ${if (DeviceWhitelist.isDeviceSupported()) "Yes" else "No"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Concurrent Cameras: ${if (isConcurrentSupported) "Yes" else "No"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
