package com.indoorar.wayfinder.domain

import com.indoorar.wayfinder.domain.destination.DestinationLocation
import com.indoorar.wayfinder.domain.destination.DestinationLocator
import com.indoorar.wayfinder.domain.destination.DestinationResult
import com.indoorar.wayfinder.domain.destination.DestinationService
import com.indoorar.wayfinder.domain.geometry.Quaternion
import com.indoorar.wayfinder.domain.geometry.RigidTransform
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.localization.CameraTracking
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.localization.LocalizationTracker
import com.indoorar.wayfinder.domain.localization.LostReason
import com.indoorar.wayfinder.domain.localization.MarkerObservation
import com.indoorar.wayfinder.domain.localization.MarkerTrackingQuality
import com.indoorar.wayfinder.domain.localization.NotLocalizedReason
import com.indoorar.wayfinder.domain.localization.TrackingInput
import com.indoorar.wayfinder.domain.selection.HitSource
import com.indoorar.wayfinder.domain.venue.Floor
import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.domain.venue.MarkerMounting
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import com.indoorar.wayfinder.domain.venue.Venue
import com.indoorar.wayfinder.domain.venue.VenueId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Simulates a physical room seen by two AR sessions whose world frames differ arbitrarily
 * (as ARCore guarantees nothing across restarts), and checks the saved point maps to the same
 * physical place only after the marker is rescanned in the second session.
 */
class SessionRestartTest {
    private val venueId = VenueId("v")
    private val venue = Venue(
        venueId, "Office", 1, MarkerSpec("venue_marker", 0.2, MarkerMounting.WALL),
        listOf(Floor(FloorId("g"), venueId, "Ground", 0)),
    )

    /** Physical world ("room") frame: marker on the wall at z = 0, facing +Z, centre 1.3 m high. */
    private val markerInRoom = RigidTransform(Vec3(0.0, 1.3, 0.0), Quaternion.fromAxisAngle(Vec3.UNIT_X, PI / 2))
    private val destinationInRoom = Vec3(1.5, 0.0, 4.0)

    /** A session frame = room frame seen through an arbitrary gravity-aligned offset. */
    private class Session(yaw: Double, offset: Vec3) {
        val sessionFromRoom = RigidTransform(offset, Quaternion.fromYaw(yaw))
        fun fromRoom(p: Vec3) = sessionFromRoom.apply(p)
        fun fromRoom(t: RigidTransform) = sessionFromRoom compose t
    }

    private fun fix(tracker: LocalizationTracker, s: Session, t: Long, quality: MarkerTrackingQuality = MarkerTrackingQuality.FULL) =
        tracker.update(
            TrackingInput(
                timestampNanos = t,
                camera = CameraTracking.Tracking,
                cameraPosition = s.fromRoom(Vec3(0.0, 1.4, 1.5)),
                marker = MarkerObservation("venue_marker", s.fromRoom(markerInRoom), quality),
            ),
        )

    @Test
    fun savedPointRestoresToSamePhysicalLocationAfterRestart() = runTest {
        val repo = InMemoryDestinationRepository()
        val service = DestinationService(repo)

        // Session 1: localize, tap the floor point (hit test gives session coordinates), save.
        val s1 = Session(yaw = 0.7, offset = Vec3(3.0, -0.2, -8.0))
        val tracker1 = LocalizationTracker(venue.marker)
        val loc1 = fix(tracker1, s1, 1)
        val saved = (service.create("Exit", s1.fromRoom(destinationInRoom), 0.0, HitSource.PLANE, loc1, venue)
            as DestinationResult.Success).value
        // Stored in the venue frame: 4 m in front of the marker, 1.5 m to the side, 1.3 m below.
        assertTrue(saved.positionInVenue.toString(), saved.positionInVenue.isApproximately(Vec3(1.5, -1.3, 4.0), 1e-9))

        // Session 2: different, arbitrary world frame. Before rescanning: no position.
        val s2 = Session(yaw = -2.1, offset = Vec3(-5.0, 0.4, 2.0))
        val tracker2 = LocalizationTracker(venue.marker)
        assertEquals(LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER), tracker2.status)
        val before = DestinationLocator.locate(saved, venue, tracker2.status)
        assertTrue(before is DestinationLocation.RequiresRelocalization)

        // A last-known marker pose alone (e.g. ARCore restoring stale state) must not localize.
        val stale = fix(tracker2, s2, 1, MarkerTrackingQuality.LAST_KNOWN)
        assertTrue(DestinationLocator.locate(saved, venue, stale) is DestinationLocation.RequiresRelocalization)

        // After a full marker fix the saved point lands on the same physical spot.
        val loc2 = fix(tracker2, s2, 2)
        val located = DestinationLocator.locate(saved, venue, loc2) as DestinationLocation.Located
        assertTrue(located.positionInSession.isApproximately(s2.fromRoom(destinationInRoom), 1e-9))
        // Using session-1 coordinates in session 2 would be wrong by metres.
        assertTrue(s1.fromRoom(destinationInRoom).distanceTo(s2.fromRoom(destinationInRoom)) > 1.0)
    }

    @Test
    fun locatorRefusesUnreliableLostAndStaleFrames() = runTest {
        val repo = InMemoryDestinationRepository()
        val s = Session(0.0, Vec3.ZERO)
        val tracker = LocalizationTracker(venue.marker)
        val loc = fix(tracker, s, 1) as LocalizationStatus.Localized
        val saved = (DestinationService(repo).create("A", destinationInRoom, null, HitSource.DEPTH, loc, venue)
            as DestinationResult.Success).value

        val located = DestinationLocator.locate(saved, venue, loc) as DestinationLocation.Located
        assertEquals(saved.estimatedErrorMeters + loc.estimatedErrorMeters, located.estimatedErrorMeters, 1e-12)
        assertTrue(DestinationLocator.locate(saved, venue, loc.copy(confidence = com.indoorar.wayfinder.domain.localization.LocalizationConfidence.LOW))
            is DestinationLocation.RequiresRelocalization)
        assertTrue(DestinationLocator.locate(saved, venue, LocalizationStatus.Lost(LostReason.TRACKING_STOPPED))
            is DestinationLocation.RequiresRelocalization)
        val moved = venue.copy(frameVersion = 2)
        assertEquals(DestinationLocation.StaleFrame(saved, 1, 2), DestinationLocator.locate(saved, moved, loc))
    }
}
