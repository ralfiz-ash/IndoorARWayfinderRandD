package com.indoorar.wayfinder.domain.venue

import kotlinx.coroutines.flow.Flow

interface VenueRepository {
    /** Emits the active venue, seeding the default configuration on first use. */
    fun observeActiveVenue(): Flow<Venue>

    suspend fun getActiveVenue(): Venue

    /**
     * Declares that the marker was physically moved: increments the frame version so previously
     * saved destinations are flagged as needing recapture.
     */
    suspend fun incrementFrameVersion(venueId: VenueId): Venue
}
