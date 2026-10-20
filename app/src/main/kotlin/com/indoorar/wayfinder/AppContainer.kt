package com.indoorar.wayfinder

import android.content.Context
import com.indoorar.wayfinder.data.WayfinderData
import com.indoorar.wayfinder.domain.destination.DestinationService
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.venue.Floor
import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.domain.venue.MarkerMounting
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import com.indoorar.wayfinder.domain.venue.Venue
import com.indoorar.wayfinder.domain.venue.VenueId
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

/** Manual dependency container: small enough that a DI framework would add more than it saves. */
class AppContainer(private val context: Context) {
    val venueConfig: Venue = DefaultVenue.venue
    private val data = WayfinderData(context, venueConfig)
    val venueRepository = data.venueRepository
    val destinationRepository = data.destinationRepository
    val destinationService = DestinationService(destinationRepository)

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
