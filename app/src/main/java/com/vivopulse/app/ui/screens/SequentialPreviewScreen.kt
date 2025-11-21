package com.vivopulse.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.vivopulse.app.viewmodel.CaptureViewModel

/**
 * Full-screen preview screen used for sequential measurements.
 *
 * Shows a single camera preview (face or finger depending on [sequentialPrimary])
 * with minimal chrome so the user can clearly see placement during capture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SequentialPreviewScreen(
    onNavigateBack: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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

    val sequentialPrimary by viewModel.sequentialPrimary.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val torchEnabled by viewModel.torchEnabled.collectAsState()

    val controller = viewModel.getCameraController()

    var frontPreviewView by remember { mutableStateOf<PreviewView?>(null) }
    var backPreviewView by remember { mutableStateOf<PreviewView?>(null) }

    // Start camera only when permission + preview view are ready
    LaunchedEffect(
        frontPreviewView,
        backPreviewView,
        cameraPermissionGranted,
        sequentialPrimary
    ) {
        if (cameraPermissionGranted && frontPreviewView != null && backPreviewView != null) {
            android.util.Log.d(
                "SequentialPreviewScreen",
                "Starting camera for sequential=$sequentialPrimary"
            )
            controller.startCamera(
                lifecycleOwner = lifecycleOwner,
                frontPreviewView = frontPreviewView!!,
                backPreviewView = backPreviewView!!
            )
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = {
                    Text(
                        if (sequentialPrimary == com.vivopulse.feature.capture.camera.SequentialPrimary.FINGER) {
                            "Finger measurement"
                        } else {
                            "Face measurement"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isRecording) {
                            viewModel.stopRecording()
                        }
                        onNavigateBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
            // Full-screen preview
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { preview ->
                        preview.scaleType = PreviewView.ScaleType.FIT_CENTER
                        preview.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        if (sequentialPrimary == com.vivopulse.feature.capture.camera.SequentialPrimary.FACE) {
                            frontPreviewView = preview
                            // create a dummy back preview to satisfy controller
                            backPreviewView = backPreviewView ?: PreviewView(ctx)
                        } else {
                            backPreviewView = preview
                            frontPreviewView = frontPreviewView ?: PreviewView(ctx)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )

            // Bottom overlay controls
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Recording timer
                    if (isRecording) {
                        val seconds = (recordingDuration / 1000).toInt()
                        Text(
                            text = String.format("Recording: %02d:%02d", seconds / 60, seconds % 60),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = "Adjust placement until preview looks stable, then start.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val torchEnabledForMode =
                            sequentialPrimary == com.vivopulse.feature.capture.camera.SequentialPrimary.FINGER

                        OutlinedButton(
                            onClick = { viewModel.toggleTorch() },
                            modifier = Modifier.weight(1f),
                            enabled = !isRecording && torchEnabledForMode
                        ) {
                            Icon(
                                imageVector = if (torchEnabled) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                                contentDescription = "Torch"
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (!torchEnabledForMode) "Torch N/A"
                                else if (torchEnabled) "Torch On" else "Torch Off"
                            )
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
                }
            }
        }
    }
}


