package com.indoorar.wayfinder.data

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappersTest {
    private val destination = Destination(
        id = DestinationId("d1"),
        venueId = VenueId("v1"),
        floorId = FloorId("f0"),
        name = "Meeting Room A",
        positionInVenue = Vec3(1.25, -1.31, 4.5),
        captureHeadingRadians = -0.75,
        frameVersion = 3,
        captureSource = HitSource.DEPTH,
        estimatedErrorMeters = 0.12,
        createdAtEpochMillis = 100L,
        updatedAtEpochMillis = 200L,
    )

    @Test
    fun destinationRoundTripsWithAllVenueFrameFields() {
        val entity = destination.toEntity()
        assertEquals("v1", entity.venueId)
        assertEquals("f0", entity.floorId)
        assertEquals(3, entity.frameVersion)
        assertEquals(1.25, entity.x, 0.0)
        assertEquals(-1.31, entity.y, 0.0)
        assertEquals(4.5, entity.z, 0.0)
        assertEquals("DEPTH", entity.captureSource)
        assertEquals(destination, entity.toDomain())
    }

    @Test
    fun nullHeadingIsPreserved() {
        val d = destination.copy(captureHeadingRadians = null)
        assertNull(d.toEntity().toDomain().captureHeadingRadians)
    }

    @Test
    fun unknownCaptureSourceFallsBackToOther() {
        assertEquals(HitSource.OTHER, destination.toEntity().copy(captureSource = "LIDAR_FUTURE").toDomain().captureSource)
    }

    @Test
    fun venueRoundTrips() {
        val id = VenueId("v1")
        val venue = Venue(id, "Office", 2, MarkerSpec("venue_marker", 0.2, MarkerMounting.FLOOR), listOf(Floor(FloorId("f0"), id, "Ground", 0)))
        val entity = venue.toEntity()
        assertEquals("FLOOR", entity.markerMounting)
        assertEquals(venue, entity.toDomain(venue.floors.map { it.toEntity() }))
    }
}
