package com.indoorar.wayfinder.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indoorar.wayfinder.domain.destination.Destination
import com.indoorar.wayfinder.domain.destination.DestinationId
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.selection.HitSource
import com.indoorar.wayfinder.domain.venue.Floor
import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.domain.venue.MarkerMounting
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import com.indoorar.wayfinder.domain.venue.Venue
import com.indoorar.wayfinder.domain.venue.VenueId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRepositoryTest {
    private val db = WayfinderDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    private val venueId = VenueId("v1")
    private val defaultVenue = Venue(
        venueId, "Test venue", 1, MarkerSpec("venue_marker", 0.2, MarkerMounting.WALL),
        listOf(Floor(FloorId("f0"), venueId, "Ground", 0)),
    )
    private val venues = RoomVenueRepository(db.venueDao(), defaultVenue)
    private val destinations = RoomDestinationRepository(db.destinationDao())

    @After fun close() = db.close()

    private fun destination(id: String, name: String) = Destination(
        DestinationId(id), venueId, FloorId("f0"), name, Vec3(1.0, -1.4, 2.5), 0.3, 1,
        HitSource.PLANE, 0.1, 10L, 10L,
    )

    @Test
    fun seedsVenueOnceAndIncrementsFrameVersion() = runTest {
        assertEquals(defaultVenue, venues.getActiveVenue())
        assertEquals(2, venues.incrementFrameVersion(venueId).frameVersion)
        // Re-seeding must not reset the stored version.
        assertEquals(2, venues.getActiveVenue().frameVersion)
        assertEquals(2, venues.observeActiveVenue().first().frameVersion)
    }

    @Test
    fun namesAreUniquePerVenueIgnoringCase() = runTest {
        venues.getActiveVenue()
        destinations.save(destination("a", "Exit"))
        val failure = runCatching { destinations.save(destination("b", "EXIT")) }.exceptionOrNull()
        assertTrue("expected unique-constraint violation, got $failure", failure is android.database.sqlite.SQLiteConstraintException)
        assertEquals(1, destinations.observeDestinations(venueId).first().size)
    }

    @Test
    fun venueFrameCoordinatesAndVersionSurviveReopen() = runTest {
        venues.getActiveVenue()
        val d = destination("a", "Reception").copy(captureHeadingRadians = null, frameVersion = 1)
        destinations.save(d)
        venues.incrementFrameVersion(venueId)
        val reloaded = destinations.getDestination(d.id)!!
        assertEquals(d, reloaded)
        // The stored point keeps its original frame version and is flagged invalid for the new frame.
        assertEquals(false, reloaded.isValidFor(venues.getActiveVenue()))
    }

    @Test
    fun destinationCrudRoundTrip() = runTest {
        venues.getActiveVenue()
        val d = destination("a", "Reception")
        destinations.save(d)
        assertEquals(d, destinations.getDestination(d.id))
        assertEquals(d, destinations.findByName(venueId, "RECEPTION"))
        destinations.save(d.copy(name = "Front desk"))
        assertEquals(listOf("Front desk"), destinations.observeDestinations(venueId).first().map { it.name })
        destinations.delete(d.id)
        assertNull(destinations.getDestination(d.id))
        assertTrue(destinations.observeDestinations(venueId).first().isEmpty())
    }
}
