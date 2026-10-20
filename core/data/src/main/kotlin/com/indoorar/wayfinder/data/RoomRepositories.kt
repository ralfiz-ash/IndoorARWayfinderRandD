package com.indoorar.wayfinder.data

import com.indoorar.wayfinder.domain.destination.Destination
import com.indoorar.wayfinder.domain.destination.DestinationId
import com.indoorar.wayfinder.domain.destination.DestinationRepository
import com.indoorar.wayfinder.domain.venue.Venue
import com.indoorar.wayfinder.domain.venue.VenueId
import com.indoorar.wayfinder.domain.venue.VenueRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

class RoomDestinationRepository(private val dao: DestinationDao) : DestinationRepository {
    override fun observeDestinations(venueId: VenueId): Flow<List<Destination>> =
        dao.observeByVenue(venueId.value).map { list -> list.map { it.toDomain() } }

    override suspend fun getDestination(id: DestinationId) = dao.getById(id.value)?.toDomain()

    override suspend fun findByName(venueId: VenueId, name: String) =
        dao.findByName(venueId.value, name)?.toDomain()

    override suspend fun save(destination: Destination) = dao.save(destination.toEntity())

    override suspend fun delete(id: DestinationId) = dao.deleteById(id.value)
}

/**
 * MVP: a single venue. [defaultVenue] is inserted on first access and never overwritten, so the
 * frame version survives app restarts.
 */
class RoomVenueRepository(
    private val dao: VenueDao,
    private val defaultVenue: Venue,
) : VenueRepository {

    private suspend fun ensureSeeded() {
        dao.seed(defaultVenue.toEntity(), defaultVenue.floors.map { it.toEntity() })
    }

    override fun observeActiveVenue(): Flow<Venue> =
        dao.observeVenue(defaultVenue.id.value)
            .onStart { ensureSeeded() }
            .filterNotNull()
            .map { it.toDomain(dao.getFloors(it.id)) }

    override suspend fun getActiveVenue(): Venue {
        ensureSeeded()
        val entity = checkNotNull(dao.getVenue(defaultVenue.id.value))
        return entity.toDomain(dao.getFloors(entity.id))
    }

    override suspend fun incrementFrameVersion(venueId: VenueId): Venue {
        dao.incrementFrameVersion(venueId.value)
        return getActiveVenue()
    }
}
