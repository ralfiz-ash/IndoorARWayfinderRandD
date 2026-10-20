package com.indoorar.wayfinder.domain.destination

import com.indoorar.wayfinder.domain.venue.VenueId
import kotlinx.coroutines.flow.Flow

interface DestinationRepository {
    fun observeDestinations(venueId: VenueId): Flow<List<Destination>>
    suspend fun getDestination(id: DestinationId): Destination?
    /** Case-insensitive lookup used to keep names unique within a venue. */
    suspend fun findByName(venueId: VenueId, name: String): Destination?
    suspend fun save(destination: Destination)
    suspend fun delete(id: DestinationId)
}
