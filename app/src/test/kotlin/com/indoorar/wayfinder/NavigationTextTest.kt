package com.indoorar.wayfinder

import com.indoorar.wayfinder.domain.destination.Destination
import com.indoorar.wayfinder.domain.destination.DestinationId
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.localization.LostReason
import com.indoorar.wayfinder.domain.localization.NotLocalizedReason
import com.indoorar.wayfinder.domain.localization.TrackingIssue
import com.indoorar.wayfinder.domain.selection.HitSource
import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.domain.venue.VenueAlignment
import com.indoorar.wayfinder.navigation.guidance.NavigationState
import com.indoorar.wayfinder.navigation.guidance.RouteProgress
import com.indoorar.wayfinder.navigation.guidance.WaitReason
import com.indoorar.wayfinder.navigation.route.Maneuver
import com.indoorar.wayfinder.navigation.route.ManeuverType
import com.indoorar.wayfinder.navigation.route.Route
import com.indoorar.wayfinder.ui.navigation.ArrivalDialogGuard
import com.indoorar.wayfinder.ui.navigation.NavigationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTextTest {
    private val dest = Destination(DestinationId("d"), DefaultVenue.venue.id, FloorId("ground"), "Reception", Vec3.ZERO, null, 1, HitSource.PLANE, 0.1, 0, 0)

    private fun progress(type: ManeuverType, toNext: Double, remaining: Double = 20.0, sharp: Boolean = false) = RouteProgress(
        distanceAlong = 0.0, remainingDistance = remaining, lateralOffset = 0.0, pointOnRoute = Vec3.ZERO, segmentIndex = 0,
        routeHeading = 0.0, nextManeuver = Maneuver(type, toNext, Vec3.ZERO, sharp = sharp), distanceToNextManeuver = toNext,
        straightLineToDestination = remaining,
    )

    @Test
    fun instructions() {
        assertEquals("Turn left in 8 m", NavigationText.instruction(progress(ManeuverType.TURN_LEFT, 8.0), "Reception").primary)
        assertEquals("Turn right now", NavigationText.instruction(progress(ManeuverType.TURN_RIGHT, 1.2), "Reception").primary)
        assertEquals("Turn sharp left in 12 m", NavigationText.instruction(progress(ManeuverType.TURN_LEFT, 12.3, sharp = true), "Reception").primary)
        assertEquals("20 m to Reception", NavigationText.instruction(progress(ManeuverType.TURN_LEFT, 8.0), "Reception").secondary)
        assertEquals("Reception in 3.5 m", NavigationText.instruction(progress(ManeuverType.ARRIVE, 3.4, 3.4), "Reception").primary)
        assertEquals("under 1 m", NavigationText.distance(0.4))
    }

    @Test
    fun warningsWhenGuidanceIsHidden() {
        val notLocalized = LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER)
        assertTrue(NavigationText.warning(NavigationState.Preparing(dest, WaitReason.LOCALIZATION), notLocalized)!!.text.contains("marker"))
        val route = Route(FloorId("ground"), emptyList(), emptyList(), emptyList(), Vec3.ZERO)
        val interrupted = LocalizationStatus.Interrupted(VenueAlignment(Vec3.ZERO, 0.0), TrackingIssue.EXCESSIVE_MOTION, 0)
        val w = NavigationText.warning(NavigationState.LocalizationLost(dest, route, null, interrupted), interrupted)!!
        assertTrue(w.severe)
        assertTrue(w.text.contains("too fast"))
        val lost = LocalizationStatus.Lost(LostReason.DRIFT_TOO_LARGE)
        assertTrue(NavigationText.warning(NavigationState.LocalizationLost(dest, route, null, lost), lost)!!.text.contains("Scan the venue marker"))
        assertNull(NavigationText.warning(NavigationState.Arrived(dest), lost))
    }

    @Test
    fun arrivalDialogShownOncePerRun() {
        val guard = ArrivalDialogGuard()
        val arrived = NavigationState.Arrived(dest)
        assertFalse(guard.shouldShow(NavigationState.Idle, 1))
        assertTrue(guard.shouldShow(arrived, 1))
        repeat(20) { assertFalse(guard.shouldShow(arrived, 1)) }
        // A new navigation run may show it again.
        assertTrue(guard.shouldShow(NavigationState.Arrived(dest), 2))
    }
}
