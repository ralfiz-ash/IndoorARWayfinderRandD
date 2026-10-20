package com.indoorar.wayfinder.navigation

import com.indoorar.wayfinder.navigation.Fixtures.GROUND
import com.indoorar.wayfinder.navigation.Fixtures.edge
import com.indoorar.wayfinder.navigation.Fixtures.node
import com.indoorar.wayfinder.navigation.Fixtures.p
import com.indoorar.wayfinder.navigation.guidance.RouteProgressCalculator
import com.indoorar.wayfinder.navigation.route.ManeuverType
import com.indoorar.wayfinder.navigation.route.RoutePlan
import com.indoorar.wayfinder.navigation.route.RoutePlanner
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class RouteProgressTest {
    // entrance(0,1) → j1(0,10) → j3(10,10) → (10,19.5); turns at 9 m (left) and 19 m (right); 28.5 m.
    private val route = (RoutePlanner(Fixtures.office).plan(p(0.0, 1.0), GROUND, p(10.0, 19.5), GROUND) as RoutePlan.Found).route

    @Test
    fun progressAlongFirstLeg() {
        val pr = RouteProgressCalculator.compute(route, p(0.4, 4.0))
        assertEquals(3.0, pr.distanceAlong, 1e-9)
        assertEquals(25.5, pr.remainingDistance, 1e-9)
        assertEquals(0.4, pr.lateralOffset, 1e-9)
        assertEquals(ManeuverType.TURN_LEFT, pr.nextManeuver.type)
        assertEquals(6.0, pr.distanceToNextManeuver, 1e-9)
        assertEquals(0.0, pr.routeHeading, 1e-9) // walking +Z
    }

    @Test
    fun turnStaysNextUntilSlightlyPassedThenAdvances() {
        assertEquals(ManeuverType.TURN_LEFT, RouteProgressCalculator.compute(route, p(0.3, 10.0)).nextManeuver.type)
        val after = RouteProgressCalculator.compute(route, p(2.0, 10.0))
        assertEquals(ManeuverType.TURN_RIGHT, after.nextManeuver.type)
        assertEquals(8.0, after.distanceToNextManeuver, 1e-9)
        assertEquals(PI / 2, after.routeHeading, 1e-9) // walking +X
    }

    @Test
    fun nearDestinationNextIsArrive() {
        val pr = RouteProgressCalculator.compute(route, p(10.0, 19.0))
        assertEquals(ManeuverType.ARRIVE, pr.nextManeuver.type)
        assertEquals(0.5, pr.remainingDistance, 1e-9)
        assertEquals(0.5, pr.straightLineToDestination, 1e-9)
    }

    @Test
    fun continuityPreventsJumpingToAParallelLegOfTheRoute() {
        // U-shaped route: up x=0, across, down x=2. A user at x=0.9 is roughly equidistant.
        val g = Fixtures.graph(
            listOf(node("a", 0.0, 0.0), node("b", 0.0, 10.0), node("c", 2.0, 10.0), node("d", 2.0, 0.0)),
            listOf(edge("a", "b"), edge("b", "c"), edge("c", "d")),
        )
        val u = (RoutePlanner(g).plan(p(0.0, 0.5), GROUND, p(2.0, 0.5), GROUND) as RoutePlan.Found).route
        val fresh = RouteProgressCalculator.compute(u, p(1.05, 3.0))
        assertEquals(9.5 + 2.0 + 7.0, fresh.distanceAlong, 1e-6) // nearest: the return leg (route is 21 m)
        val continued = RouteProgressCalculator.compute(u, p(1.05, 3.0), previousAlong = 2.0)
        assertEquals(2.5, continued.distanceAlong, 1e-6) // stays on the outbound leg
    }
}
