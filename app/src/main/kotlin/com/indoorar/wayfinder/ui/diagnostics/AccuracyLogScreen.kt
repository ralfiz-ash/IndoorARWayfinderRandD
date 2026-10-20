package com.indoorar.wayfinder.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.indoorar.wayfinder.AppContainer
import com.indoorar.wayfinder.domain.accuracy.AccuracyCheckKind
import com.indoorar.wayfinder.domain.accuracy.AccuracySample
import com.indoorar.wayfinder.domain.accuracy.AccuracyStats
import com.indoorar.wayfinder.ui.UiText
import kotlinx.coroutines.launch

/** Measured accuracy evidence. Repeatability and absolute accuracy are reported separately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccuracyLogScreen(container: AppContainer, onBack: () -> Unit) {
    val samples by container.accuracyLog.observeSamples().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Accuracy log") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            actions = { TextButton(onClick = { confirmClear = true }, enabled = samples.isNotEmpty()) { Text("Clear") } },
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Each entry is a point you tapped at a physically marked spot, compared with a tape-measured reference " +
                        "point (absolute accuracy) or a previously saved destination (repeatability). Only these entries are " +
                        "evidence of accuracy; the app's ± figure is a model estimate.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            for (kind in AccuracyCheckKind.entries) {
                val subset = samples.filter { it.kind == kind }
                item { SummaryCard(kind, subset) }
            }
            item {
                Text(
                    "CSV: adb shell run-as com.indoorar.wayfinder cat files/${container.accuracyCsvFile.name}",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
            }
            items(samples.reversed()) { s -> SampleRow(s) }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear accuracy log?") },
            text = { Text("Deletes all ${samples.size} measurements from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { container.accuracyLog.clear(); container.exportAccuracyCsv(emptyList()) }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SummaryCard(kind: AccuracyCheckKind, samples: List<AccuracySample>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (kind == AccuracyCheckKind.ABSOLUTE) "Absolute (vs tape-measured points)" else "Repeatability (vs saved destinations)",
                style = MaterialTheme.typography.titleSmall,
            )
            val summary = AccuracyStats.summarize(samples)
            if (summary == null) {
                Text("No measurements yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "n=${summary.count}  median ${UiText.meters(summary.medianMeters)}  p90 ${UiText.meters(summary.p90Meters)}  " +
                        "max ${UiText.meters(summary.maxMeters)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Within the app's own estimate: ${(summary.withinEstimateFraction * 100).toInt()} %" +
                        if (summary.count < 10) "  (too few samples to conclude)" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                AccuracyStats.spreadByReference(samples).forEach {
                    Text(
                        "• ${it.referenceName}: n=${it.count}, mean error ${UiText.meters(it.meanErrorMeters)}, spread ${UiText.meters(it.maxSpreadMeters)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SampleRow(s: AccuracySample) {
    Text(
        "${s.referenceName}: ${UiText.meters(s.horizontalError)} (est ±${UiText.meters(s.estimatedErrorMeters)}), " +
            "${UiText.meters(s.distanceFromMarkerMeters)} from marker, heading ${s.approachHeadingDegrees?.toInt() ?: "?"}°, " +
            "walked ${s.walkedSinceFixMeters.toInt()} m since fix, session ${s.sessionId}",
        style = MaterialTheme.typography.bodySmall,
    )
}
