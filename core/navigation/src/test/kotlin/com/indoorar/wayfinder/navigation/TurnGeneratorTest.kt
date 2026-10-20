package com.indoorar.wayfinder.navigation

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.navigation.Fixtures.GROUND
import com.indoorar.wayfinder.navigation.Fixtures.p
import com.indoorar.wayfinder.navigation.route.ManeuverType
import com.indoorar.wayfinder.navigation.route.RoutePlan
import com.indoorar.wayfinder.navigation.route.RoutePlanner
import com.indoorar.wayfinder.navigation.route.RouteSegment
import com.indoorar.wayfinder.navigation.route.SegmentKind
import com.indoorar.wayfinder.navigation.route.TurnGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnGeneratorTest {
    private fun segments(vararg points: Vec3): List<RouteSegment> {
        var d = 0.0
        return points.toList().zipWithNext().map { (a, b) -> RouteSegment(a, b, SegmentKind.GRAPH, d).also { d = it.endDistance } }
    }

    private fun types(vararg points: Vec3) = TurnGenerator().generate(segments(*points), points.last()).map { it.type }

    @Test
    fun handednessFollowsVenueFrame() {
        // Walking +Z, the left-hand side is +X (venue frame is right-handed, +Y up).
        assertEquals(listOf(ManeuverType.STRAIGHT, ManeuverType.TURN_LEFT, ManeuverType.ARRIVE), types(p(0.0, 0.0), p(0.0, 5.0), p(5.0, 5.0)))
        assertEquals(listOf(ManeuverType.STRAIGHT, ManeuverType.TURN_RIGHT, ManeuverType.ARRIVE), types(p(0.0, 0.0), p(0.0, 5.0), p(-5.0, 5.0)))
        // Walking +X, a turn towards +Z is a right turn.
        assertEquals(listOf(ManeuverType.STRAIGHT, ManeuverType.TURN_RIGHT, ManeuverType.ARRIVE), types(p(0.0, 0.0), p(5.0, 0.0), p(5.0, 5.0)))
    }

    @Test
    fun officeRouteInstructions() {
        val route = (RoutePlanner(Fixtures.office).plan(p(0.0, 1.0), GROUND, p(10.0, 19.5), GROUND) as RoutePlan.Found).route
        val m = route.maneuvers
        assertEquals(listOf(ManeuverType.STRAIGHT, ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.ARRIVE), m.map { it.type })
        assertEquals(9.0, m[1].atDistance, 1e-9)
        assertEquals("j1", m[1].nodeId?.value)
        assertEquals(19.0, m[2].atDistance, 1e-9)
        assertEquals("j3", m[2].nodeId?.value)
        assertEquals(28.5, m[3].atDistance, 1e-9)
    }

    @Test
    fun gentleBendsAreStraightAndSharpTurnsFlagged() {
        assertEquals(listOf(ManeuverType.STRAIGHT, ManeuverType.ARRIVE), types(p(0.0, 0.0), p(0.0, 5.0), p(1.0, 10.0)))
        val sharp = TurnGenerator().generate(segments(p(0.0, 0.0), p(0.0, 5.0), p(1.0, 1.0)), p(1.0, 1.0))[1]
        assertEquals(ManeuverType.TURN_LEFT, sharp.type)
        assertTrue(sharp.sharp)
    }

    @Test
    fun cornerDrawnWithTwoNearbyPointsYieldsOneTurn() {
        val m = types(p(0.0, 0.0), p(0.0, 5.0), p(0.2, 5.1), p(5.0, 5.1))
        assertEquals(listOf(ManeuverType.STRAIGHT, ManeuverType.TURN_LEFT, ManeuverType.ARRIVE), m)
    }

    @Test
    fun shortFinalAccessLegDoesNotInventATurn() {
        // Destination 0.3 m to the side of the corridor end: heading of that leg is noise.
        assertEquals(listOf(ManeuverType.STRAIGHT, ManeuverType.ARRIVE), types(p(0.0, 0.0), p(0.0, 5.0), p(0.3, 5.0)))
    }

    @Test
    fun emptyRouteIsJustArrival() {
        assertEquals(listOf(ManeuverType.ARRIVE), TurnGenerator().generate(emptyList(), p(1.0, 1.0)).map { it.type })
    }
}
