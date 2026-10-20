package com.indoorar.wayfinder.ui.navigation

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.indoorar.wayfinder.ui.diagnostics.DiagnosticsPanel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.indoorar.wayfinder.AppContainer
import com.indoorar.wayfinder.domain.destination.Destination
import com.indoorar.wayfinder.domain.destination.DestinationId
import com.indoorar.wayfinder.navigation.guidance.NavigationState
import com.indoorar.wayfinder.ui.UiText
import com.indoorar.wayfinder.ui.ar.ArCameraView
import com.indoorar.wayfinder.ui.ar.CameraPermissionGate
import com.indoorar.wayfinder.ui.selection.LocalizationChip
import com.indoorar.wayfinder.ui.theme.StatusColors
import kotlin.math.roundToInt

@Composable
fun NavigationScreen(container: AppContainer, destinationId: String, onExit: () -> Unit) {
    CameraPermissionGate(onCancel = onExit) {
        val app = LocalContext.current.applicationContext as Application
        val vm: NavigationViewModel = viewModel(key = "nav-$destinationId") {
            NavigationViewModel(app, container, DestinationId(destinationId))
        }
        val state by vm.state.collectAsStateWithLifecycle()
        val nav = state.guidance.state
        var showDiagnostics by rememberSaveable { mutableStateOf(false) }
        val name = state.destination?.name ?: "destination"

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ArCameraView(vm.controller, onTap = {}, modifier = Modifier.fillMaxSize())

            // Distance label attached to the world-space beacon (only when the beacon is on screen).
            state.guidance.beaconScreen?.let { p ->
                val progress = (nav as? NavigationState.Navigating)?.progress ?: (nav as? NavigationState.OffRoute)?.progress
                if (progress != null) {
                    val density = LocalDensity.current
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = StatusColors.Good.copy(alpha = 0.85f),
                        modifier = Modifier.offset {
                            IntOffset((p.x - with(density) { 60.dp.toPx() }).roundToInt(), (p.y - with(density) { 36.dp.toPx() }).roundToInt())
                        },
                    ) {
                        Text(
                            "$name · ${NavigationText.distance(progress.remainingDistance)}",
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LocalizationChip(UiText.localization(state.snapshot.localization), Modifier.weight(1f))
                    TextButton(onClick = { showDiagnostics = !showDiagnostics }) { Text("Diag", color = Color.White) }
                }
                if (showDiagnostics) DiagnosticsPanel(state.snapshot.localization, state.snapshot.diagnostics)
                NavigationText.warning(nav, state.snapshot.localization, state.snapshot.diagnostics)?.let { WarningBanner(it) }
                state.failure?.let { Banner(UiText.failure(it), StatusColors.Bad) }
                state.loadError?.let { Banner(it, StatusColors.Bad) }
            }

            // Screen-space fallback: only while world guidance is valid but the 3D arrow is out of view.
            val bearing = state.guidance.relativeBearingRadians
            if (state.guidance.worldGuidanceVisible && !state.guidance.arrowOnScreen && bearing != null) {
                FallbackIndicator(bearing, Modifier.align(Alignment.Center))
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (nav) {
                    is NavigationState.Navigating -> InstructionCard(NavigationText.instruction(nav.progress, name), nav.rerouteCount)
                    is NavigationState.OffRoute -> InstructionCard(NavigationText.instruction(nav.progress, name), null, dimmed = true)
                    is NavigationState.Failed -> Banner("Cannot navigate to $name: ${NavigationText.failure(nav.failure)}", StatusColors.Bad)
                    is NavigationState.Paused -> Banner("Navigation paused. Guidance is hidden.", Color.Black.copy(alpha = 0.7f))
                    else -> Unit
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (nav is NavigationState.Paused) {
                        Button(onClick = vm::resume, modifier = Modifier.weight(1f)) { Text("Resume") }
                    } else if (nav !is NavigationState.Failed && nav !is NavigationState.Arrived && nav !is NavigationState.Idle) {
                        OutlinedButton(onClick = vm::pause, modifier = Modifier.weight(1f)) { Text("Pause", color = Color.White) }
                    }
                    Button(onClick = { vm.finish(); onExit() }, modifier = Modifier.weight(1f)) { Text("Exit") }
                }
            }
        }

        if (state.showArrival) {
            AlertDialog(
                onDismissRequest = {}, // explicit choice required
                title = { Text("Arrived") },
                text = { Text("You have reached $name.") },
                confirmButton = { TextButton(onClick = { vm.finish(); onExit() }) { Text("Finish") } },
                dismissButton = { TextButton(onClick = vm::chooseAnotherDestination) { Text("Navigate to another destination") } },
            )
        }
        if (state.choosingNext) {
            DestinationChooser(state.alternatives, onPick = vm::navigateTo, onCancel = { vm.dismissChooser(); vm.finish(); onExit() })
        }
    }
}

@Composable
private fun InstructionCard(instruction: Instruction, reroutes: Int?, dimmed: Boolean = false) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF10223F).copy(alpha = if (dimmed) 0.6f else 0.88f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(instruction.glyph, fontSize = 34.sp, color = Color.White, modifier = Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                Text(instruction.primary, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val secondary = listOfNotNull(instruction.secondary, reroutes?.takeIf { it > 0 }?.let { "re-routed $it×" }).joinToString(" · ")
                if (secondary.isNotEmpty()) Text(secondary, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WarningBanner(warning: GuidanceWarning) =
    Banner(warning.text, if (warning.severe) StatusColors.Bad.copy(alpha = 0.92f) else StatusColors.Warn.copy(alpha = 0.92f))

@Composable
private fun Banner(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = color, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = Color.White, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Clearly differentiated 2D fallback (dashed outline, grey, labelled): points towards the world
 * arrow when it is outside the camera view. Not a substitute for the world-aligned guidance.
 */
@Composable
private fun FallbackIndicator(bearingRadians: Double, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(96.dp).border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape).background(Color.Black.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Positive bearing = left = counter-clockwise on screen.
            Canvas(Modifier.size(64.dp).rotate(-Math.toDegrees(bearingRadians).toFloat())) {
                val w = size.width
                val path = Path().apply {
                    moveTo(w / 2, 0f); lineTo(w * 0.85f, w * 0.55f); lineTo(w * 0.6f, w * 0.55f)
                    lineTo(w * 0.6f, w); lineTo(w * 0.4f, w); lineTo(w * 0.4f, w * 0.55f); lineTo(w * 0.15f, w * 0.55f); close()
                }
                drawPath(path, Color.LightGray, style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            }
        }
        Spacer(Modifier.size(6.dp))
        Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.6f)) {
            Text("AR arrow out of view: turn this way", color = Color.White, modifier = Modifier.padding(6.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DestinationChooser(options: List<Destination>, onPick: (Destination) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Navigate to") },
        text = {
            if (options.isEmpty()) Text("No other saved destinations.")
            else LazyColumn {
                items(options, key = { it.id.value }) { d ->
                    Text(d.name, modifier = Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Finish") } },
    )
}
