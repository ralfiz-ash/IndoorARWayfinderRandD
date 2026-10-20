package com.indoorar.wayfinder.navigation

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.localization.LocalizationConfidence
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.localization.LostReason
import com.indoorar.wayfinder.domain.localization.NotLocalizedReason
import com.indoorar.wayfinder.domain.localization.TrackingIssue
import com.indoorar.wayfinder.domain.venue.VenueAlignment
import com.indoorar.wayfinder.navigation.Fixtures.edge
import com.indoorar.wayfinder.navigation.Fixtures.node
import com.indoorar.wayfinder.navigation.Fixtures.p
import com.indoorar.wayfinder.navigation.graph.GraphBuildResult
import com.indoorar.wayfinder.navigation.graph.RouteGraph
import com.indoorar.wayfinder.navigation.guidance.NavigationFailure
import com.indoorar.wayfinder.navigation.guidance.NavigationSession
import com.indoorar.wayfinder.navigation.guidance.NavigationState
import com.indoorar.wayfinder.navigation.guidance.WaitReason
import com.indoorar.wayfinder.navigation.route.ManeuverType
import com.indoorar.wayfinder.navigation.route.RouteFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSessionTest {
    /** Session frame rotated/offset from the venue frame, to prove positions are converted. */
    private val alignment = VenueAlignment(Vec3(3.0, 0.0, -2.0), 0.8)
    private fun localized(error: Double = 0.1, confidence: LocalizationConfidence = LocalizationConfidence.HIGH) =
        LocalizationStatus.Localized(alignment, error, confidence, markerInView = false)

    /** User standing at venue (x, z) with the camera 1.4 m above the floor marker-relative height. */
    private fun user(x: Double, z: Double) = alignment.toSession(Vec3(x, 0.1, z))

    private var t = 0L
    private fun step(session: NavigationSession, x: Double, z: Double, status: LocalizationStatus = localized(), dtMs: Long = 200): NavigationState {
        t += dtMs * 1_000_000
        return session.update(t, status, user(x, z))
    }

    private val dest = Fixtures.destination(10.0, 19.5, "Meeting Room B")
    private fun session(graph: RouteGraph? = Fixtures.office) = NavigationSession(graph, Fixtures.venue)

    @Test
    fun waitsForLocalizationBeforePlanning() {
        val s = session()
        assertEquals(NavigationState.Preparing(dest, WaitReason.LOCALIZATION), s.start(dest))
        assertEquals(
            NavigationState.Preparing(dest, WaitReason.LOCALIZATION),
            step(s, 0.0, 2.0, LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER)),
        )
        // LOW confidence is not reliable either.
        assertTrue(step(s, 0.0, 2.0, localized(0.9, LocalizationConfidence.LOW)) is NavigationState.Preparing)
        val nav = step(s, 0.0, 2.0) as NavigationState.Navigating
        assertEquals(listOf("j1", "j3"), nav.route.nodeIds.map { it.value })
        assertEquals(ManeuverType.TURN_LEFT, nav.progress.nextManeuver.type)
        assertEquals(8.0, nav.progress.distanceToNextManeuver, 1e-6)
    }

    @Test
    fun refusesInvalidSetups() {
        assertEquals(NavigationState.Failed(dest, NavigationFailure.NoGraph), session(null).start(dest))
        val empty = (RouteGraph.build(Fixtures.venue.id, 1, emptyList(), emptyList()) as GraphBuildResult.Valid).graph
        assertEquals(NavigationState.Failed(dest, NavigationFailure.NoGraph), session(empty).start(dest))
        val oldGraph = (RouteGraph.build(Fixtures.venue.id, 0, Fixtures.office.nodes.values.toList(), Fixtures.office.edges) as GraphBuildResult.Valid).graph
        assertEquals(NavigationState.Failed(dest, NavigationFailure.GraphFrameMismatch), session(oldGraph).start(dest))
        val stale = Fixtures.destination(10.0, 19.5, frameVersion = 0)
        assertEquals(NavigationState.Failed(stale, NavigationFailure.StaleDestination), session().start(stale))
    }

    @Test
    fun unreachableDestinationFailsClearly() {
        val s = session()
        val storage = Fixtures.destination(30.0, 2.5, "Storage")
        s.start(storage)
        assertEquals(NavigationState.Failed(storage, NavigationFailure.NoRoute(RouteFailure.NO_CONNECTED_PATH)), step(s, 0.0, 2.0))
        val inBlock = Fixtures.destination(5.0, 15.0, "Nowhere")
        s.start(inBlock)
        assertEquals(NavigationState.Failed(inBlock, NavigationFailure.NoRoute(RouteFailure.DESTINATION_OFF_GRAPH)), step(s, 0.0, 2.0))
    }

    @Test
    fun userOffTheGraphIsAskedToReachAPathAndRetriedPeriodically() {
        val s = session()
        s.start(dest)
        assertEquals(NavigationState.Preparing(dest, WaitReason.START_OFF_GRAPH), step(s, 5.0, 15.0))
        // Retries are throttled; once in a corridor and the interval elapsed, planning succeeds.
        assertTrue(step(s, 0.0, 2.0, dtMs = 100) is NavigationState.Preparing)
        assertTrue(step(s, 0.0, 2.0, dtMs = 2_000) is NavigationState.Navigating)
    }

    @Test
    fun localizationLossSuspendsGuidanceAndRecovers() {
        val s = session()
        s.start(dest)
        step(s, 0.0, 2.0)
        val lostStatus = LocalizationStatus.Lost(LostReason.DRIFT_TOO_LARGE)
        val lost = step(s, 10.0, 19.5, lostStatus) as NavigationState.LocalizationLost
        assertEquals(lostStatus, lost.status)
        // Even standing on the destination, no arrival while interrupted.
        repeat(10) { assertTrue(step(s, 10.0, 19.5, LocalizationStatus.Interrupted(alignment, TrackingIssue.EXCESSIVE_MOTION, t)) is NavigationState.LocalizationLost) }
        // Recovery keeps the planned route.
        val back = step(s, 0.0, 5.0) as NavigationState.Navigating
        assertEquals(lost.route, back.route)
        assertEquals(0, back.rerouteCount)
    }

    @Test
    fun arrivalRequiresProximityDwellAndAccurateLocalization() {
        val s = session()
        s.start(dest)
        step(s, 0.0, 2.0)
        assertTrue(step(s, 10.0, 18.8) is NavigationState.Navigating) // conditions start
        assertTrue(step(s, 10.0, 18.8, dtMs = 500) is NavigationState.Navigating) // dwell not yet 1 s
        // Localization error too high resets the dwell even though the estimate is "close".
        assertTrue(step(s, 10.0, 19.4, localized(0.9, LocalizationConfidence.MEDIUM), dtMs = 600) is NavigationState.Navigating)
        assertTrue(step(s, 10.0, 19.4, dtMs = 600) is NavigationState.Navigating)
        assertEquals(NavigationState.Arrived(dest), step(s, 10.0, 19.4, dtMs = 1_000))
        // Arrived is terminal until a new start.
        assertEquals(NavigationState.Arrived(dest), step(s, 0.0, 2.0))
    }

    @Test
    fun noArrivalThroughAWall() {
        // Two parallel corridors 1.5 m apart joined only at the far end; destination at the start of the second.
        val g = Fixtures.graph(
            listOf(node("a", 0.0, 0.0), node("b", 0.0, 10.0), node("c", 1.5, 10.0), node("d", 1.5, 0.0)),
            listOf(edge("a", "b"), edge("b", "c"), edge("c", "d")),
        )
        val s = NavigationSession(g, Fixtures.venue)
        val target = Fixtures.destination(1.5, 0.5, "Other side")
        s.start(target)
        repeat(10) {
            // 0.9 m in a straight line from the destination, but ~19 m along the walkable route.
            val st = step(s, 0.6, 0.5, dtMs = 500)
            assertTrue(st.toString(), st is NavigationState.Navigating)
        }
    }

    @Test
    fun deviationIsConfirmedThenRerouted() {
        val s = session()
        s.start(dest)
        step(s, 0.0, 2.0) // route via j1 → j3
        step(s, 0.0, 10.0)
        // User continues straight towards j2 instead of turning left at j1.
        val off = step(s, 0.0, 16.0) as NavigationState.OffRoute
        assertEquals(null, off.rerouteFailure)
        assertTrue(step(s, 0.0, 17.0, dtMs = 1_000) is NavigationState.OffRoute) // not yet confirmed
        val rerouted = step(s, 0.0, 18.0, dtMs = 2_500) as NavigationState.Navigating
        assertEquals(1, rerouted.rerouteCount)
        assertEquals(listOf("j2", "j4"), rerouted.route.nodeIds.map { it.value })
        assertEquals(ManeuverType.TURN_LEFT, rerouted.progress.nextManeuver.type) // left at j2 towards +X
    }

    @Test
    fun briefExcursionIsNotADeviation() {
        val s = session()
        s.start(dest)
        step(s, 0.0, 2.0)
        assertTrue(step(s, 3.0, 5.0) is NavigationState.OffRoute)
        assertTrue(step(s, 0.5, 6.0, dtMs = 500) is NavigationState.Navigating)
        assertEquals(0, (step(s, 0.0, 7.0) as NavigationState.Navigating).rerouteCount)
    }

    @Test
    fun rerouteWithoutValidAlternativeStaysOffRouteWithReason() {
        val s = session()
        s.start(dest)
        step(s, 0.0, 2.0)
        step(s, 0.0, 9.0)
        step(s, 5.0, 15.0) // wandered into the unmapped middle of the block
        val st = step(s, 5.0, 15.0, dtMs = 3_500) as NavigationState.OffRoute
        assertEquals(RouteFailure.START_OFF_GRAPH, st.rerouteFailure)
        // Never invents a straight-line route; returns to normal once back on a corridor.
        assertTrue(step(s, 9.8, 14.0) is NavigationState.Navigating)
    }

    @Test
    fun pauseResumeAndStop() {
        val s = session()
        s.start(dest)
        step(s, 0.0, 2.0)
        val paused = s.pause() as NavigationState.Paused
        assertTrue(paused.route != null)
        assertEquals(paused, step(s, 10.0, 19.5)) // updates ignored while paused
        assertEquals(NavigationState.Preparing(dest, WaitReason.RESUMING), s.resume())
        assertTrue(step(s, 0.0, 3.0) is NavigationState.Navigating)
        assertEquals(NavigationState.Idle, s.stop())
        assertEquals(NavigationState.Idle, step(s, 0.0, 3.0))
    }

    @Test
    fun arrivalRadiusIsConfigurableAndErrorMustFitInsideIt() {
        val wide = NavigationSession(Fixtures.office, Fixtures.venue, com.indoorar.wayfinder.navigation.guidance.NavigationPolicy(arrivalRadiusMeters = 2.5, arrivalDwellNanos = 0))
        wide.start(dest)
        step(wide, 0.0, 2.0)
        assertEquals(NavigationState.Arrived(dest), step(wide, 10.0, 17.5)) // 2.0 m away
        val narrow = NavigationSession(Fixtures.office, Fixtures.venue, com.indoorar.wayfinder.navigation.guidance.NavigationPolicy(arrivalRadiusMeters = 1.0, arrivalDwellNanos = 0))
        narrow.start(dest)
        step(narrow, 0.0, 2.0)
        assertTrue(step(narrow, 10.0, 17.5) is NavigationState.Navigating)
        // 0.55 m estimated error is still MEDIUM (reliable for guidance) but too coarse for arrival.
        assertTrue(step(narrow, 10.0, 19.3, localized(0.55, LocalizationConfidence.MEDIUM)) is NavigationState.Navigating)
        assertEquals(NavigationState.Arrived(dest), step(narrow, 10.0, 19.3))
        val invalid = runCatching { com.indoorar.wayfinder.navigation.guidance.NavigationPolicy(arrivalRadiusMeters = 1.0, maxArrivalErrorMeters = 1.5) }
        assertTrue(invalid.isFailure)
    }
}
