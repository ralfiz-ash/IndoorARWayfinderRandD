package com.indoorar.wayfinder.navigation

import com.indoorar.wayfinder.navigation.Fixtures.GROUND
import com.indoorar.wayfinder.navigation.Fixtures.p
import com.indoorar.wayfinder.navigation.guidance.GuidanceGeometry
import com.indoorar.wayfinder.navigation.guidance.GuidanceSmoother
import com.indoorar.wayfinder.navigation.guidance.RoutePose
import com.indoorar.wayfinder.navigation.route.RoutePlan
import com.indoorar.wayfinder.navigation.route.RoutePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class GuidanceTest {
    // (0,1) → j1(0,10) → j3(10,10) → (10,19.5)
    private val route = (RoutePlanner(Fixtures.office).plan(p(0.0, 1.0), GROUND, p(10.0, 19.5), GROUND) as RoutePlan.Found).route

    @Test
    fun arrowSitsAheadOnTheRouteAndPointsAlongIt() {
        val a = GuidanceGeometry.arrowPose(route, distanceAlong = 2.0)
        assertTrue(a.position.isApproximately(p(0.0, 5.0), 1e-9))
        assertEquals(0.0, a.yaw, 1e-9) // +Z
        val afterTurn = GuidanceGeometry.arrowPose(route, distanceAlong = 10.0)
        assertTrue(afterTurn.position.isApproximately(p(3.0, 10.0), 1e-9))
        assertEquals(PI / 2, afterTurn.yaw, 1e-9) // +X
    }

    @Test
    fun arrowAnticipatesTurnSmoothlyAndClampsAtDestination() {
        // Exactly at the corner the window spans both legs: heading halfway between +Z and +X.
        assertEquals(PI / 4, GuidanceGeometry.arrowPose(route, distanceAlong = 7.0).yaw, 1e-9)
        val end = GuidanceGeometry.arrowPose(route, distanceAlong = 28.0)
        assertTrue(end.position.isApproximately(p(10.0, 19.5), 1e-9))
        assertEquals(0.0, end.yaw, 1e-9)
    }

    @Test
    fun chevronsFollowRouteAndStopBeforeDestination() {
        val c = GuidanceGeometry.chevrons(route, 0.0)
        assertEquals(5, c.size)
        assertTrue(c[0].position.isApproximately(p(0.0, 2.0), 1e-9))
        assertEquals(0, GuidanceGeometry.chevrons(route, 27.5).size)
        assertTrue(GuidanceGeometry.chevrons(route, 25.0).size in 1..2)
    }

    @Test
    fun relativeBearingSignMeansLeft() {
        // Looking +Z, a target at +X is to the left.
        assertEquals(PI / 2, GuidanceGeometry.relativeBearing(0.0, p(0.0, 0.0), p(5.0, 0.0))!!, 1e-9)
        assertEquals(-PI / 2, GuidanceGeometry.relativeBearing(0.0, p(0.0, 0.0), p(-5.0, 0.0))!!, 1e-9)
        assertEquals(PI, kotlin.math.abs(GuidanceGeometry.relativeBearing(0.0, p(0.0, 0.0), p(0.0, -5.0))!!), 1e-9)
        assertNull(GuidanceGeometry.relativeBearing(0.0, p(1.0, 1.0), p(1.0, 1.0)))
    }

    @Test
    fun smootherDampsJitterButSnapsOnLargeChanges() {
        val s = GuidanceSmoother()
        val start = s.update(RoutePose(p(0.0, 5.0), 0.0), 0)
        assertTrue(start.position.isApproximately(p(0.0, 5.0), 1e-12))
        // 10 cm / 10° jitter one frame later moves only a fraction of the way.
        val jitter = s.update(RoutePose(p(0.1, 5.0), Math.toRadians(10.0)), 33_000_000)
        assertTrue(jitter.position.x in 0.01..0.03)
        assertTrue(jitter.yaw in Math.toRadians(1.0)..Math.toRadians(3.5))
        // Converges within ~1 s (no excessive lag).
        var r = jitter
        for (i in 2..31) r = s.update(RoutePose(p(0.1, 5.0), Math.toRadians(10.0)), i * 33_000_000L)
        assertEquals(0.1, r.position.x, 0.002)
        assertEquals(Math.toRadians(10.0), r.yaw, 0.002)
        // Re-route: 4 m jump is applied immediately.
        val snap = s.update(RoutePose(p(4.0, 5.0), PI / 2), 32 * 33_000_000L)
        assertTrue(snap.position.isApproximately(p(4.0, 5.0), 1e-12))
    }

    @Test
    fun smootherWrapsAroundPi() {
        val s = GuidanceSmoother()
        s.update(RoutePose(p(0.0, 0.0), PI - 0.05), 0)
        val r = s.update(RoutePose(p(0.0, 0.0), -PI + 0.05), 100_000_000)
        // Moves the short way across ±π, not through 0.
        assertTrue(kotlin.math.abs(r.yaw) > PI - 0.06)
    }
}
