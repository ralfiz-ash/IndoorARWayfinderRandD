package com.indoorar.wayfinder.ui.ar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.indoorar.wayfinder.arcore.ArSessionController

/**
 * Hosts the AR GL surface and drives the session from the lifecycle. The session itself is owned
 * (and finally closed) by the screen's ViewModel, so it survives this composable leaving and re-entering.
 */
@Composable
fun ArCameraView(controller: ArSessionController, onTap: (Offset) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val surfaceView = remember { controller.createSurfaceView(context) }

    DisposableEffect(lifecycleOwner, surfaceView) {
        var running = false
        fun stop() {
            if (running) {
                surfaceView.onPause()
                controller.pause()
                running = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!running && controller.resume(activity)) {
                    surfaceView.onResume()
                    running = true
                }
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }

    Box(modifier) {
        AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = onTap) },
        )
    }
}

/** Shows [content] once the camera permission is granted, with an explanation before asking. */
@Composable
fun CameraPermissionGate(onCancel: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    if (granted) {
        content()
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Camera access needed", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(
            "The AR view uses the camera to show the live scene, recognise the venue marker and detect the floor. " +
                "Camera images are processed on the device and never stored or uploaded. " +
                "Location access is not needed: indoor positions come from the marker, not GPS.",
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
        if (denied) {
            Text(
                "Permission was denied. If the dialog no longer appears, enable Camera for this app in system Settings.",
                color = Color(0xFFFFB4A9),
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        OutlinedButton(onClick = onCancel) { Text("Not now", color = Color.White) }
    }
}
