package com.indoorar.wayfinder

import android.content.Context
import com.indoorar.wayfinder.domain.accuracy.AccuracyCsv
import com.indoorar.wayfinder.domain.accuracy.AccuracySample
import com.indoorar.wayfinder.domain.accuracy.ReferencePoint
import com.indoorar.wayfinder.navigation.calibration.ReferencePointsJson
import com.indoorar.wayfinder.navigation.calibration.ReferencePointsResult
import java.io.File
import com.indoorar.wayfinder.data.WayfinderData
import com.indoorar.wayfinder.domain.destination.DestinationService
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.venue.Floor
import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.domain.venue.MarkerMounting
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import com.indoorar.wayfinder.domain.venue.Venue
import com.indoorar.wayfinder.domain.venue.VenueId
import com.indoorar.wayfinder.navigation.guidance.NavigationPolicy
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * MVP venue configuration. The marker must be printed at [MarkerSpec.physicalWidthMeters]
 * (docs/marker/venue_marker_print_20cm.pdf) and mounted as declared here.
 */
object DefaultVenue {
    private val id = VenueId("default-venue")
    val venue = Venue(
        id = id,
        name = "My Venue",
        frameVersion = 1,
        marker = MarkerSpec(imageName = "venue_marker", physicalWidthMeters = 0.20, mounting = MarkerMounting.WALL),
        floors = listOf(Floor(FloorId("ground"), id, "Ground floor", level = 0)),
    )
}

sealed interface ReferencePointsStatus {
    data class Ready(val points: List<ReferencePoint>) : ReferencePointsStatus
    data class Invalid(val message: String) : ReferencePointsStatus
}

/** Manual dependency container: small enough that a DI framework would add more than it saves. */
class AppContainer(private val context: Context) {
    val venueConfig: Venue = DefaultVenue.venue
    private val data = WayfinderData(context, venueConfig)
    val venueRepository = data.venueRepository
    val destinationRepository = data.destinationRepository
    val destinationService = DestinationService(destinationRepository)
    val accuracyLog = data.accuracyLog

    /** Tape-measured reference points for absolute accuracy checks (assets/reference_points.json). */
    fun referencePoints(venue: Venue): ReferencePointsStatus = synchronized(this) {
        cachedReferencePoints?.takeIf { it.first == venue.frameVersion }?.second
            ?: loadReferencePoints(venue).also { cachedReferencePoints = venue.frameVersion to it }
    }
    private var cachedReferencePoints: Pair<Int, ReferencePointsStatus>? = null

    private fun loadReferencePoints(venue: Venue): ReferencePointsStatus {
        val text = runCatching { context.assets.open("reference_points.json").bufferedReader().use { it.readText() } }
            .getOrElse { return ReferencePointsStatus.Ready(emptyList()) }
        return when (val r = ReferencePointsJson.parse(text)) {
            is ReferencePointsResult.Invalid -> ReferencePointsStatus.Invalid(r.message)
            is ReferencePointsResult.Loaded -> when {
                r.venueId != venue.id.value -> ReferencePointsStatus.Invalid("reference points are for venue ${r.venueId}")
                r.frameVersion != venue.frameVersion ->
                    ReferencePointsStatus.Invalid("measured for frame v${r.frameVersion}, venue is v${venue.frameVersion}")
                else -> ReferencePointsStatus.Ready(r.points)
            }
        }
    }

    /** CSV export of the accuracy log, readable with `adb shell run-as <pkg> cat files/accuracy_log.csv`. */
    val accuracyCsvFile: File = File(context.filesDir, "accuracy_log.csv")

    fun exportAccuracyCsv(samples: List<AccuracySample>) {
        accuracyCsvFile.writeText(buildString {
            appendLine(AccuracyCsv.HEADER)
            samples.forEach { appendLine(AccuracyCsv.row(it)) }
        })
    }

    /**
     * Navigation tuning. arrivalRadiusMeters is the configurable arrival radius; arrival also
     * requires adequate localization accuracy (maxArrivalErrorMeters) and a short dwell.
     */
    val navigationPolicy = NavigationPolicy(arrivalRadiusMeters = 1.0, maxArrivalErrorMeters = 0.5)

    /**
     * Route graph status for the given venue state (frame version can change at runtime).
     * Loaded lazily from assets; small enough to parse on demand.
     */
    private val graphCache = HashMap<Int, GraphStatus>()
    fun graphStatus(venue: Venue): GraphStatus = synchronized(graphCache) {
        graphCache.getOrPut(venue.frameVersion) { VenueGraphLoader.load(context, venue) }
    }

    /** Last localization state reported by an AR screen in this app run (for the home status card). */
    val lastLocalization = MutableStateFlow<LocalizationStatus?>(null)
}
