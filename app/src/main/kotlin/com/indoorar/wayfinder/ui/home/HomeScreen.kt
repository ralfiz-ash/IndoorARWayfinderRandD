package com.indoorar.wayfinder.ui.home

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.indoorar.wayfinder.AppContainer
import com.indoorar.wayfinder.domain.destination.Destination
import com.indoorar.wayfinder.ui.StatusLevel
import com.indoorar.wayfinder.ui.UiText
import com.indoorar.wayfinder.ui.theme.StatusColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onAddDestination: () -> Unit,
    onShowMarker: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: HomeViewModel = viewModel { HomeViewModel(app, container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<Destination?>(null) }
    var deleting by remember { mutableStateOf<Destination?>(null) }
    var confirmMarkerMoved by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messageEvents.collect { snackbarHostState.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Indoor AR Wayfinder") },
                actions = {
                    TextButton(onClick = { menuOpen = true }) { Text("Venue") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Show reference marker") }, onClick = { menuOpen = false; onShowMarker() })
                        DropdownMenuItem(text = { Text("Marker was moved…") }, onClick = { menuOpen = false; confirmMarkerMoved = true })
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAddDestination) { Text("Add destination in AR") }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(state) }
            item {
                Text("Destinations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            if (!state.loading && state.destinations.isEmpty()) {
                item {
                    Text(
                        "No destinations yet. Tap \"Add destination in AR\", scan the venue marker, then tap a point on the floor.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(state.destinations, key = { it.destination.id.value }) { item ->
                DestinationRow(
                    item = item,
                    onNavigate = { vm.navigateTo(item.destination) },
                    onRename = { renaming = item.destination },
                    onDelete = { deleting = item.destination },
                )
            }
        }
    }

    renaming?.let { d -> RenameDialog(d, onDismiss = { renaming = null }, onRename = { name, setError ->
        vm.rename(d, name) { error -> if (error == null) renaming = null else setError(error) }
    }) }

    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${d.name}\"?") },
            text = { Text("This removes the saved point from this device.") },
            confirmButton = { TextButton(onClick = { vm.delete(d); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (confirmMarkerMoved) {
        AlertDialog(
            onDismissRequest = { confirmMarkerMoved = false },
            title = { Text("Was the marker moved?") },
            text = {
                Text(
                    "Saved destinations are stored relative to the marker. If it was moved or reprinted at another size, " +
                        "they no longer match reality. This starts a new venue frame version; existing destinations will be " +
                        "marked as needing recapture.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.markerMoved(); confirmMarkerMoved = false }) { Text("Start new frame") } },
            dismissButton = { TextButton(onClick = { confirmMarkerMoved = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusCard(state: HomeUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val venue = state.venue
            Text(venue?.name ?: "Loading venue…", style = MaterialTheme.typography.titleMedium)
            if (venue != null) {
                Text("${venue.defaultFloor.name} · frame v${venue.frameVersion}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Marker \"${venue.marker.imageName}\", printed ${(venue.marker.physicalWidthMeters * 100).toInt()} cm wide, " +
                        "mounted ${UiText.mounting(venue.marker.mounting)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val loc = UiText.localization(state.lastLocalization)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(0.dp))
                Text("Localization: ", style = MaterialTheme.typography.bodyMedium)
                Text(loc.text, color = loc.level.color(), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Localization is per AR session: every time the camera view opens, scan the marker first.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.graphStatus?.let {
                val g = UiText.graphStatus(it)
                Text(g.text, color = g.level.color(), style = MaterialTheme.typography.bodySmall)
            }
            Text(state.arAvailability, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusLevel.color() = when (this) {
    StatusLevel.GOOD -> StatusColors.Good
    StatusLevel.WARN -> StatusColors.Warn
    StatusLevel.BAD -> StatusColors.Bad
    StatusLevel.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun DestinationRow(item: DestinationItem, onNavigate: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val d = item.destination
    Card(
        onClick = onNavigate,
        enabled = item.valid,
        colors = CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleSmall)
                val p = d.positionInVenue
                Text(
                    String.format(Locale.US, "x %.2f, z %.2f m from marker · ±%.2f m", p.x, p.z, d.estimatedErrorMeters),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (item.valid && item.onGraph == false) {
                    Text("Not near a mapped walkway: unreachable", color = StatusColors.Warn, style = MaterialTheme.typography.bodySmall)
                }
                if (!item.valid) {
                    Text("Captured in frame v${d.frameVersion}: recapture needed", color = StatusColors.Bad, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onRename) { Text("Rename") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun RenameDialog(destination: Destination, onDismiss: () -> Unit, onRename: (String, (String) -> Unit) -> Unit) {
    var name by remember { mutableStateOf(destination.name) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename destination") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
            )
        },
        confirmButton = { TextButton(onClick = { onRename(name) { error = it } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
