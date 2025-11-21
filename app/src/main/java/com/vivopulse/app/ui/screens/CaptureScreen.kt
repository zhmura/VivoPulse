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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.draw.clip
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onNavigateToProcessing: () -> Unit,
    onNavigateToReactivity: () -> Unit,
    onNavigateToSequentialPreview: () -> Unit,
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
                            text = "F:${String.format("%.0f", fps.first)} B:${
                                String.format(
                                    "%.0f",
                                    fps.second
                                )
                            } fps",
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
                val sequentialModeActive = cameraMode == CameraMode.SAFE_MODE_SEQUENTIAL
                var frontPreviewView by remember { mutableStateOf<PreviewView?>(null) }
                var backPreviewView by remember { mutableStateOf<PreviewView?>(null) }

                val controlsScroll = rememberScrollState()

                Box(modifier = Modifier.fillMaxSize()) {
                    // PREVIEW AREA (background) only for concurrent mode
                    if (!sequentialModeActive) {
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

                        // Start cameras when preview views are ready
                        LaunchedEffect(
                            frontPreviewView,
                            backPreviewView,
                            cameraPermissionGranted,
                            cameraMode
                        ) {
                            if (cameraPermissionGranted && frontPreviewView != null && backPreviewView != null) {
                                android.util.Log.d(
                                    "CaptureScreen",
                                    "Starting concurrent camera: permission=$cameraPermissionGranted"
                                )
                                viewModel.getCameraController().startCamera(
                                    lifecycleOwner = lifecycleOwner,
                                    frontPreviewView = frontPreviewView!!,
                                    backPreviewView = backPreviewView!!
                                )
                            }
                        }

                        // Concurrent mode: two previews, each taking half height
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CameraPreviewCard(
                                title = "Face (Front)",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                showRoiOverlay = true,
                                faceRoi = faceRoi,
                                waveform = faceWave
                            ) { previewView ->
                                frontPreviewView = previewView
                            }

                            CameraPreviewCard(
                                title = "Finger (Back)",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                showTorchIndicator = torchEnabled,
                                waveform = fingerWave
                            ) { previewView ->
                                backPreviewView = previewView
                            }
                        }
                    }

                    // TOP BANNERS AS COMPACT CHIPS
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!isDeviceSupported) {
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                leadingIcon = {
                                    Icon(Icons.Default.Warning, contentDescription = null)
                                },
                                label = { Text("Device not optimized for dual camera capture") }
                            )
                        }
                        statusBanner?.let { banner ->
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                leadingIcon = {
                                    Icon(Icons.Default.Info, contentDescription = null)
                                },
                                label = { Text(banner) }
                            )
                        }
                        if (FeatureFlags.isSimulatedModeEnabled()) {
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                leadingIcon = {
                                    Icon(Icons.Default.Science, contentDescription = null)
                                },
                                label = { Text("Simulated Mode Active") }
                            )
                        }
                    }

                    // BOTTOM SHEET CONTROLS / QUALITY / MODE
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .heightIn(min = 260.dp, max = 380.dp)
                            .navigationBarsPadding(),
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        tonalElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(controlsScroll)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Handle indicator
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .width(40.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )

                            // Sequential mode selector (only when not recording)
                            if (sequentialModeActive && !isRecording) {
                                SequentialModeCard(
                                    selected = sequentialPrimary,
                                    enabled = true,
                                    onSelectionChanged = { viewModel.setSequentialPrimary(it) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // Quality indicators (for concurrent mode only)
                            if (!sequentialModeActive) {
                                qualityState?.let {
                                    QualityIndicatorsSection(
                                        state = it,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            // Recording stats (if available, concurrent only)
                            if (!sequentialModeActive) {
                                lastResult?.let { result ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(
                                                text = "Captured ${result.frames.size} frames (Face: ${result.stats.faceStats.framesReceived}, Finger: ${result.stats.fingerStats.framesReceived})",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            val faceFps = String.format(
                                                "%.1f",
                                                result.stats.faceStats.averageFps
                                            )
                                            val fingerFps = String.format(
                                                "%.1f",
                                                result.stats.fingerStats.averageFps
                                            )
                                            Text(
                                                text = "Avg FPS - Face: $faceFps, Finger: $fingerFps",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            if (result.stats.totalDropped > 0) {
                                                Text(
                                                    text = "⚠️ Dropped ${result.stats.totalDropped} frames",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }

                                // Recording timer
                                if (isRecording && !sequentialModeActive) {
                                    val seconds = (recordingDuration / 1000).toInt()
                                    Text(
                                        text = String.format(
                                            "Recording: %02d:%02d",
                                            seconds / 60,
                                            seconds % 60
                                        ),
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }

                                // Controls row
                                val torchButtonEnabled =
                                    !isRecording && !(sequentialModeActive && sequentialPrimary == SequentialPrimary.FACE)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                                            if (sequentialModeActive) {
                                                onNavigateToSequentialPreview()
                                            } else {
                                                if (isRecording) {
                                                    viewModel.stopRecording()
                                                } else {
                                                    viewModel.startRecording()
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = if (!sequentialModeActive && isRecording) {
                                            ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                            )
                                        } else {
                                            ButtonDefaults.buttonColors()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (!sequentialModeActive && isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                            contentDescription = if (!sequentialModeActive && isRecording) "Stop" else "Start"
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            when {
                                                sequentialModeActive -> "Open Preview"
                                                isRecording -> "Stop"
                                                else -> "Start"
                                            }
                                        )
                                    }
                                }

                                if (!torchButtonEnabled && sequentialModeActive && sequentialPrimary == SequentialPrimary.FACE) {
                                    Text(
                                        text = "Torch is unavailable when using the face camera.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Process button
                                Button(
                                    onClick = {
                                        if (lastResult != null) {
                                            onNavigateToProcessing()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = lastResult != null && !isRecording
                                ) {
                                    Icon(Icons.Default.ArrowForward, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    val label = if (lastResult != null) {
                                        val lumaCount = lastResult!!.frames.count { it.hasLuma() }
                                        "Process ${lumaCount} Frames with Luma"
                                    } else {
                                        "Process Captured Frames"
                                    }
                                    Text(label)
                                }

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
            modifier = modifier,
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
                                        scaleType = PreviewView.ScaleType.FIT_CENTER
                                        implementationMode =
                                            PreviewView.ImplementationMode.COMPATIBLE
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
}
