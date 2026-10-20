package com.indoorar.wayfinder.ui.selection

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.indoorar.wayfinder.AppContainer
import com.indoorar.wayfinder.domain.selection.HitSource
import com.indoorar.wayfinder.ui.StatusLevel
import com.indoorar.wayfinder.ui.UiText
import com.indoorar.wayfinder.ui.ar.ArCameraView
import com.indoorar.wayfinder.ui.ar.CameraPermissionGate
import com.indoorar.wayfinder.ui.theme.StatusColors
import kotlinx.coroutines.delay

@Composable
fun PointSelectionScreen(container: AppContainer, onClose: () -> Unit, onSaved: (String) -> Unit) {
    CameraPermissionGate(onCancel = onClose) {
        val app = LocalContext.current.applicationContext as Application
        val vm: PointSelectionViewModel = viewModel { PointSelectionViewModel(app, container) }
        val state by vm.state.collectAsStateWithLifecycle()

        LaunchedEffect(vm) { vm.savedEvents.collect(onSaved) }
        LaunchedEffect(state.hint) {
            if (state.hint != null) {
                delay(3_500)
                vm.dismissHint()
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ArCameraView(vm.controller, onTap = { vm.onTap(it.x, it.y) }, modifier = Modifier.fillMaxSize())

            if (state.selected == null) Reticle(state.reticle.valid, Modifier.align(Alignment.Center))

            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LocalizationChip(UiText.localization(state.snapshot.localization), Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Cancel", color = Color.White) }
                }
                Banner(
                    UiText.selectionInstruction(
                        state.snapshot, state.selected, vm.marker, state.notLocalizedSeconds, state.noFloorSeconds,
                    ),
                )
                if (state.savedCount > 0) {
                    val shown = state.snapshot.localization.isReliable
                    Banner(
                        if (shown) "${state.savedCount} saved destination(s) shown as yellow markers."
                        else "${state.savedCount} saved destination(s) hidden until the marker is scanned in this session.",
                    )
                }
                state.hint?.let { Banner(it, background = StatusColors.Warn.copy(alpha = 0.9f)) }
                state.failure?.let { Banner(UiText.failure(it), background = StatusColors.Bad.copy(alpha = 0.92f)) }
            }

            state.selected?.let { selected ->
                SaveSheet(
                    state = state,
                    sourceLabel = when (selected.source) {
                        HitSource.PLANE -> if (selected.isLikelyFloor) "floor plane" else "raised horizontal surface"
                        HitSource.DEPTH -> "depth estimate"
                        else -> "surface"
                    },
                    onNameChange = vm::onNameChange,
                    onSave = vm::save,
                    onRetry = vm::retry,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun Reticle(valid: Boolean, modifier: Modifier) {
    val color = if (valid) StatusColors.Good else Color.White
    Canvas(modifier.size(44.dp)) {
        val r = size.minDimension / 2
        drawCircle(color, radius = r - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
        drawCircle(color, radius = 3.dp.toPx())
        val c = Offset(size.width / 2, size.height / 2)
        drawLine(color, c.copy(x = 0f), c.copy(x = c.x - 8.dp.toPx()), 2.dp.toPx())
        drawLine(color, c.copy(x = size.width), c.copy(x = c.x + 8.dp.toPx()), 2.dp.toPx())
    }
}

@Composable
fun LocalizationChip(status: com.indoorar.wayfinder.ui.StatusText, modifier: Modifier = Modifier) {
    val dot = when (status.level) {
        StatusLevel.GOOD -> StatusColors.Good
        StatusLevel.WARN -> StatusColors.Warn
        StatusLevel.BAD -> StatusColors.Bad
        StatusLevel.NEUTRAL -> StatusColors.Neutral
    }
    Surface(modifier, shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Spacer(Modifier.size(8.dp))
            Text(status.text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Banner(text: String, background: Color = Color.Black.copy(alpha = 0.6f)) {
    Surface(shape = RoundedCornerShape(12.dp), color = background, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = Color.White, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SaveSheet(
    state: SelectionUiState,
    sourceLabel: String,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier.fillMaxWidth().imePadding(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 6.dp,
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Save destination", style = MaterialTheme.typography.titleMedium)
            val loc = state.selected?.localization
            val accuracy = (loc as? com.indoorar.wayfinder.domain.localization.LocalizationStatus.Localized)
                ?.let { " · position ±${UiText.meters(it.estimatedErrorMeters)}" }.orEmpty()
            Text("Selected on $sourceLabel$accuracy", style = MaterialTheme.typography.bodySmall)
            if (state.selected?.anchorTracking == false) {
                Text("The preview point is not tracked right now. Hold still or retry.", color = StatusColors.Warn)
            }
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text("Name, e.g. Meeting Room A") },
                singleLine = true,
                isError = state.nameError != null,
                supportingText = state.nameError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Retry") }
                Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.weight(1f)) {
                    Text(if (state.saving) "Saving…" else "Save")
                }
            }
        }
    }
}
