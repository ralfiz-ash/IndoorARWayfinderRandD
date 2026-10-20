package com.indoorar.wayfinder.navigation

import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.navigation.Fixtures.GROUND
import com.indoorar.wayfinder.navigation.Fixtures.edge
import com.indoorar.wayfinder.navigation.Fixtures.node
import com.indoorar.wayfinder.navigation.Fixtures.p
import com.indoorar.wayfinder.navigation.route.RouteFailure
import com.indoorar.wayfinder.navigation.route.RoutePlan
import com.indoorar.wayfinder.navigation.route.RoutePlanner
import com.indoorar.wayfinder.navigation.route.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlannerTest {
    private fun plan(graph: com.indoorar.wayfinder.navigation.graph.RouteGraph, sx: Double, sz: Double, dx: Double, dz: Double) =
        RoutePlanner(graph).plan(p(sx, sz), GROUND, p(dx, dz), GROUND)

    private fun found(plan: RoutePlan) = (plan as RoutePlan.Found).route
    private fun nodes(plan: RoutePlan) = found(plan).nodeIds.map { it.value }

    @Test
    fun choosesShortestOfTwoAlternatives() {
        // From (0,2): via j3 8 + 10 + 9.5 = 27.5; via j2 8 + 10 + 10 + 0.5 = 28.5.
        val plan = plan(Fixtures.office, 0.0, 2.0, 10.0, 19.5)
        assertEquals(listOf("j1", "j3"), nodes(plan))
        assertEquals(27.5, found(plan).totalLength, 1e-9)
    }

    @Test
    fun avoidsBlockedEdges() {
        val g = Fixtures.officeWith { if (it.from.value == "j1" && it.to.value == "j3") it.copy(blocked = true) else it }
        val plan = plan(g, 0.0, 2.0, 10.0, 19.5)
        assertEquals(listOf("j1", "j2", "j4"), nodes(plan))
        assertEquals(28.5, found(plan).totalLength, 1e-9)
    }

    @Test
    fun respectsOneWayEdges() {
        val g = Fixtures.graph(
            listOf(node("a", 0.0, 0.0), node("b", 0.0, 10.0)),
            listOf(edge("a", "b", bidirectional = false)),
        )
        assertTrue(plan(g, 0.0, 1.0, 0.0, 9.0) is RoutePlan.Found)
        assertEquals(RoutePlan.NotFound(RouteFailure.NO_CONNECTED_PATH), plan(g, 0.0, 9.0, 0.0, 1.0))
    }

    @Test
    fun routesIntoRoomThroughItsDoor() {
        val plan = plan(Fixtures.office, 0.0, 2.0, -6.0, 10.5)
        assertEquals(listOf("j1", "door-a"), nodes(plan))
        assertEquals(8.0 + 3.0 + 3.0 + 0.5, found(plan).totalLength, 1e-9)
        // Last leg from the graph to the saved point is a short access leg.
        val last = found(plan).segments.last()
        assertEquals(SegmentKind.ACCESS, last.kind)
        assertEquals(0.5, last.length, 1e-9)
    }

    @Test
    fun disconnectedSectionIsReportedNotBridged() {
        // Storage wing is 20+ m away with no connecting edge: never draw a straight line to it.
        assertEquals(RoutePlan.NotFound(RouteFailure.NO_CONNECTED_PATH), plan(Fixtures.office, 0.0, 1.0, 30.0, 2.5))
    }

    @Test
    fun startOrDestinationAwayFromMappedPathsFails() {
        // (5,15) lies in the middle of the block, 5 m from every corridor.
        assertEquals(RoutePlan.NotFound(RouteFailure.DESTINATION_OFF_GRAPH), plan(Fixtures.office, 0.0, 1.0, 5.0, 15.0))
        assertEquals(RoutePlan.NotFound(RouteFailure.START_OFF_GRAPH), plan(Fixtures.office, 5.0, 15.0, 0.0, 1.0))
    }

    @Test
    fun nonAttachableEdgesAreNotUsedForAttachment() {
        // Destination 1 m from j1–j2 (x=0) but that corridor is marked non-attachable (wall in between).
        val g = Fixtures.officeWith { if (it.from.value == "j1" && it.to.value == "j2") it.copy(attachable = false) else it }
        assertEquals(RoutePlan.NotFound(RouteFailure.DESTINATION_OFF_GRAPH), plan(g, 0.0, 1.0, 1.0, 15.0))
        // The edge is still walkable as part of a route.
        assertEquals(listOf("j1", "j2"), nodes(plan(g, 0.0, 2.0, 9.5, 20.5)))
    }

    @Test
    fun startAndDestinationOnSameEdge() {
        val route = found(plan(Fixtures.office, 0.5, 3.0, 0.0, 8.0))
        assertEquals(5.0, route.totalLength, 1e-9)
        assertTrue(route.nodeIds.isEmpty())
    }

    @Test
    fun emptyGraphAndDifferentFloors() {
        val empty = Fixtures.graph(listOf(node("a", 0.0, 0.0)), emptyList())
        assertEquals(RoutePlan.NotFound(RouteFailure.NO_GRAPH), plan(empty, 0.0, 0.0, 1.0, 1.0))
        assertEquals(
            RoutePlan.NotFound(RouteFailure.DIFFERENT_FLOORS),
            RoutePlanner(Fixtures.office).plan(p(0.0, 1.0), GROUND, p(0.0, 5.0), FloorId("upper")),
        )
    }

    @Test
    fun routeIsContinuousAndEndsAtDestination() {
        val route = found(plan(Fixtures.office, 0.3, 2.0, -6.0, 10.5))
        route.segments.zipWithNext().forEach { (a, b) ->
            assertTrue(a.to.isApproximately(b.from, 1e-9))
            assertEquals(a.endDistance, b.startDistance, 1e-9)
        }
        assertTrue(route.segments.last().to.isApproximately(p(-6.0, 10.5), 1e-9))
        // Starts at the user's projection on the corridor, not the user's raw position.
        assertTrue(route.segments.first().from.isApproximately(p(0.0, 2.0), 1e-9))
    }
}
