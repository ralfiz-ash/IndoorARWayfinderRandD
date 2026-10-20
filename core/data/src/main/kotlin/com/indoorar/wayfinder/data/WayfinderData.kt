package com.indoorar.wayfinder.data

import android.content.Context
import com.indoorar.wayfinder.domain.destination.DestinationRepository
import com.indoorar.wayfinder.domain.venue.Venue
import com.indoorar.wayfinder.domain.venue.VenueRepository

/** Entry point of the persistence layer; keeps Room types out of other modules. */
class WayfinderData(context: Context, defaultVenue: Venue) {
    private val database = WayfinderDatabase.create(context)
    val venueRepository: VenueRepository = RoomVenueRepository(database.venueDao(), defaultVenue)
    val destinationRepository: DestinationRepository = RoomDestinationRepository(database.destinationDao())
}
