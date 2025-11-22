package com.vivopulse.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
import androidx.compose.material3.ColorScheme

@OptIn(ExperimentalMaterial3Api::class)
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
    
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
    }
    
    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

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
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Compact banners
                    if (!isDeviceSupported) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = "⚠️ Device not optimized",
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    
                    statusBanner?.let { banner ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Text(
                                text = banner,
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
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
                    
                    // Camera previews
                    var frontPreviewView by remember { mutableStateOf<PreviewView?>(null) }
                    var backPreviewView by remember { mutableStateOf<PreviewView?>(null) }
                    var cameraStarted by remember { mutableStateOf(false) }
                    
                    LaunchedEffect(frontPreviewView, backPreviewView, cameraPermissionGranted, sequentialPrimary, cameraMode) {
                        if (cameraPermissionGranted && frontPreviewView != null && backPreviewView != null && !cameraStarted) {
                            android.util.Log.d("CaptureScreen", "Starting camera - mode: $cameraMode, sequential: $sequentialPrimary")
                            viewModel.getCameraController().startCamera(
                                lifecycleOwner = lifecycleOwner,
                                frontPreviewView = frontPreviewView!!,
                                backPreviewView = backPreviewView!!
                            )
                            cameraStarted = true
                        }
                    }
                    
                    // Reset camera started flag when mode changes
                    LaunchedEffect(cameraMode, sequentialPrimary) {
                        cameraStarted = false
                    }
                    
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
                    
                    // Camera previews - HORIZONTAL LAYOUT
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CameraPreviewCard(
                            title = "Face (Front) - ${faceWave.size} pts",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            showRoiOverlay = true,
                            faceRoi = faceRoi,
                            waveform = faceWave
                        ) { previewView ->
                            frontPreviewView = previewView
                        }
                        
                        CameraPreviewCard(
                            title = "Finger (Back) - ${fingerWave.size} pts",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            showTorchIndicator = torchEnabled,
                            waveform = fingerWave
                        ) { previewView ->
                            backPreviewView = previewView
                        }
                    }
                    
                    // Compact controls at bottom
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (isRecording) {
                                val seconds = (recordingDuration / 1000).toInt()
                                Text(
                                    text = String.format("Recording: %02d:%02d", seconds / 60, seconds % 60),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            
                            val torchButtonEnabled = !isRecording && !(sequentialModeActive && sequentialPrimary == SequentialPrimary.FACE)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.toggleTorch() },
                                    modifier = Modifier.weight(1f),
                                    enabled = torchButtonEnabled
                                ) {
                                    Icon(
                                        imageVector = if (torchEnabled) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                                        contentDescription = "Torch"
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (torchEnabled) "Torch On" else "Torch Off")
                                }
                                
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
                                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                        contentDescription = if (isRecording) "Stop" else "Start"
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isRecording) "Stop" else "Start")
                                }
                            }
                            
                            if (lastResult != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = { onNavigateToProcessing() },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isRecording
                                ) {
                                    Icon(Icons.Default.ArrowForward, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Process ${lastResult!!.frames.size} Frames")
                                }
                            }
                        }
                    }
                }
            }
            
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
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (showTorchIndicator) {
                        Icon(
                            imageVector = Icons.Default.FlashlightOn,
                            contentDescription = "Torch on",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { context ->
                        if (showRoiOverlay) {
                            FrameLayout(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                
                                val preview = PreviewView(context).apply {
                                    scaleType = PreviewView.ScaleType.FIT_CENTER
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                                addView(preview)
                                
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
                                scaleType = PreviewView.ScaleType.FIT_CENTER
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                onPreviewViewCreated(this)
                            }
                        }
                    },
                    update = { view ->
                        @Suppress("USELESS_IS_CHECK")
                        if (showRoiOverlay && view is FrameLayout && view.childCount > 1) {
                            val overlay = view.getChildAt(1) as? RoiOverlayView
                            overlay?.updateRoi(faceRoi)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // Waveform overlay
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
                            val y = h - (yNorm * h * 0.4f + h * 0.05f)
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFF80DEEA),
                            style = Stroke(width = 2f)
                        )
                    }
                }
            }
        }
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
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Sequential capture order", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose which channel you measure first on devices that cannot stream both cameras.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
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
