package com.indoorar.wayfinder.ui.marker

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.indoorar.wayfinder.AppContainer
import com.indoorar.wayfinder.arcore.MarkerDatabase
import com.indoorar.wayfinder.ui.UiText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkerScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val marker = container.venueConfig.marker
    val bitmap = remember {
        context.assets.open("${MarkerDatabase.ASSET_DIR}/${marker.imageName}.png").use(BitmapFactory::decodeStream)
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Reference marker") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(bitmap.asImageBitmap(), contentDescription = "Venue reference marker", modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            Text("Setup", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. Print docs/marker/venue_marker_print_20cm.pdf at 100% scale (no \"fit to page\"). " +
                    "The marker must measure exactly ${(marker.physicalWidthMeters * 100).toInt()} cm wide.\n" +
                    "2. Mount it flat and rigid ${UiText.mounting(marker.mounting)}, about chest height, in good light, " +
                    "away from glare. Matte paper works best.\n" +
                    "3. Do not move it afterwards. If you must, use Venue → \"Marker was moved…\".\n" +
                    "4. Every AR session starts by pointing the camera at the marker from 0.5–2 m.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Showing this image on another screen works for quick tests, but the physical size will not match, " +
                    "so saved positions will be scaled incorrectly.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
