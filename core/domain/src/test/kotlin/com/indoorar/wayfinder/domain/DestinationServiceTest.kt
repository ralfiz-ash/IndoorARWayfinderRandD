package com.indoorar.wayfinder.domain

import com.indoorar.wayfinder.domain.destination.Destination
import com.indoorar.wayfinder.domain.destination.DestinationError
import com.indoorar.wayfinder.domain.destination.DestinationId
import com.indoorar.wayfinder.domain.destination.DestinationRepository
import com.indoorar.wayfinder.domain.destination.DestinationResult
import com.indoorar.wayfinder.domain.destination.DestinationService
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.localization.LocalizationConfidence
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.localization.NotLocalizedReason
import com.indoorar.wayfinder.domain.selection.HitSource
import com.indoorar.wayfinder.domain.venue.Floor
import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.domain.venue.MarkerMounting
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import com.indoorar.wayfinder.domain.venue.Venue
import com.indoorar.wayfinder.domain.venue.VenueAlignment
import com.indoorar.wayfinder.domain.venue.VenueId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class InMemoryDestinationRepository : DestinationRepository {
    val items = MutableStateFlow<Map<DestinationId, Destination>>(emptyMap())
    override fun observeDestinations(venueId: VenueId): Flow<List<Destination>> =
        items.map { m -> m.values.filter { it.venueId == venueId }.sortedBy { it.name } }
    override suspend fun getDestination(id: DestinationId) = items.value[id]
    override suspend fun findByName(venueId: VenueId, name: String) =
        items.value.values.firstOrNull { it.venueId == venueId && it.name.equals(name, ignoreCase = true) }
    override suspend fun save(destination: Destination) { items.value += destination.id to destination }
    override suspend fun delete(id: DestinationId) { items.value -= id }
}

class DestinationServiceTest {
    private val venueId = VenueId("venue")
    private val venue = Venue(
        venueId, "Office", frameVersion = 3,
        marker = MarkerSpec("venue_marker", 0.2, MarkerMounting.WALL),
        floors = listOf(Floor(FloorId("g"), venueId, "Ground", 0)),
    )
    private val repo = InMemoryDestinationRepository()
    private var now = 1_000L
    private var nextId = 0
    private val service = DestinationService(repo, clock = { now }, idFactory = { "id-${nextId++}" })

    private val alignment = VenueAlignment(Vec3(10.0, 0.0, 5.0), PI / 2)
    private val localized = LocalizationStatus.Localized(alignment, 0.1, LocalizationConfidence.HIGH, true)

    private suspend fun create(name: String, status: LocalizationStatus = localized) =
        service.create(name, Vec3(10.0, -1.4, 7.0), PI / 2, HitSource.PLANE, status, venue)

    @Test
    fun createsDestinationInVenueFrame() = runTest {
        val d = (create("  Meeting   Room A ") as DestinationResult.Success).value
        assertEquals("Meeting Room A", d.name)
        assertEquals(DestinationId("id-0"), d.id)
        assertEquals(3, d.frameVersion)
        assertEquals(FloorId("g"), d.floorId)
        // Session point is 2 m along session +Z from the origin; venue yaw +90° maps that to venue −X.
        assertTrue(d.positionInVenue.isApproximately(Vec3(-2.0, -1.4, 0.0), 1e-9))
        assertEquals(0.0, d.captureHeadingRadians!!, 1e-9)
        assertEquals(d, repo.getDestination(d.id))
        assertTrue(d.isValidFor(venue))
        assertFalse(d.isValidFor(venue.copy(frameVersion = 4)))
    }

    @Test
    fun refusesCreationWithoutReliableLocalization() = runTest {
        assertEquals(DestinationResult.Failure(DestinationError.NotLocalized),
            create("A", LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER)))
        assertEquals(DestinationResult.Failure(DestinationError.LocalizationUnreliable),
            create("A", localized.copy(confidence = LocalizationConfidence.LOW)))
        assertTrue(repo.items.value.isEmpty())
    }

    @Test
    fun validatesNames() = runTest {
        assertEquals(DestinationResult.Failure(DestinationError.BlankName), create("   "))
        assertEquals(DestinationResult.Failure(DestinationError.NameTooLong(60)), create("x".repeat(61)))
        create("Reception")
        assertEquals(DestinationResult.Failure(DestinationError.DuplicateName), create("reception"))
    }

    @Test
    fun renamesDestination() = runTest {
        val d = (create("Exit") as DestinationResult.Success).value
        create("Reception")
        now = 2_000L
        val renamed = (service.rename(d.id, "Main Exit") as DestinationResult.Success).value
        assertEquals("Main Exit", renamed.name)
        assertEquals(2_000L, renamed.updatedAtEpochMillis)
        assertEquals(1_000L, renamed.createdAtEpochMillis)
        assertEquals(DestinationResult.Failure(DestinationError.DuplicateName), service.rename(d.id, "RECEPTION"))
        // Renaming to a different casing of its own name is allowed.
        assertTrue(service.rename(d.id, "MAIN EXIT") is DestinationResult.Success)
        assertEquals(DestinationResult.Failure(DestinationError.NotFound), service.rename(DestinationId("nope"), "X"))
    }

    @Test
    fun deletesDestination() = runTest {
        val d = (create("Exit") as DestinationResult.Success).value
        assertEquals(1, repo.observeDestinations(venueId).first().size)
        assertEquals(DestinationResult.Success(Unit), service.delete(d.id))
        assertNull(repo.getDestination(d.id))
        assertTrue(repo.observeDestinations(venueId).first().isEmpty())
        assertEquals(DestinationResult.Failure(DestinationError.NotFound), service.delete(d.id))
    }
}
