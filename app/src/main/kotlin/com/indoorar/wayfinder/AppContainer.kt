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
class AppContainer(context: Context) {
    val venueConfig: Venue = DefaultVenue.venue
    private val data = WayfinderData(context, venueConfig)
    val venueRepository = data.venueRepository
    val destinationRepository = data.destinationRepository
    val destinationService = DestinationService(destinationRepository)

    /** Last localization state reported by an AR screen in this app run (for the home status card). */
    val lastLocalization = MutableStateFlow<LocalizationStatus?>(null)
}
