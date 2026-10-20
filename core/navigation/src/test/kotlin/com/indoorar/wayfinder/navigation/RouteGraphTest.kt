package com.indoorar.wayfinder.navigation

import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.navigation.Fixtures.edge
import com.indoorar.wayfinder.navigation.Fixtures.node
import com.indoorar.wayfinder.navigation.graph.GraphBuildResult
import com.indoorar.wayfinder.navigation.graph.GraphError
import com.indoorar.wayfinder.navigation.graph.GraphLoadResult
import com.indoorar.wayfinder.navigation.graph.NodeId
import com.indoorar.wayfinder.navigation.graph.NodeType
import com.indoorar.wayfinder.navigation.graph.RouteGraph
import com.indoorar.wayfinder.navigation.graph.VenueGraphJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGraphTest {
    private fun errors(nodes: List<com.indoorar.wayfinder.navigation.graph.RouteNode>, edges: List<com.indoorar.wayfinder.navigation.graph.RouteEdge>) =
        (RouteGraph.build(Fixtures.venue.id, 1, nodes, edges) as GraphBuildResult.Invalid).errors

    @Test
    fun loadsExampleGraphWithDistancesAndConnectivity() {
        val g = Fixtures.office
        assertEquals(9, g.nodes.size)
        assertEquals(8, g.edges.size)
        assertEquals(NodeType.ROOM, g.node(NodeId("room-a")).type)
        assertEquals(9.0, g.lengthOf(0), 1e-9) // entrance(0,1) → j1(0,10)
        assertEquals(setOf("entrance", "j2", "j3", "door-a"), g.arcsFrom(NodeId("j1")).map { it.to.value }.toSet())
        assertTrue(g.isCompatibleWith(Fixtures.venue))
        assertFalse(g.isCompatibleWith(Fixtures.venue.copy(frameVersion = 2)))
    }

    @Test
    fun oneWayAndBlockedEdgesAffectAdjacency() {
        val g = Fixtures.graph(
            listOf(node("a", 0.0, 0.0), node("b", 0.0, 5.0), node("c", 5.0, 5.0)),
            listOf(edge("a", "b", bidirectional = false), edge("b", "c", blocked = true)),
        )
        assertEquals(listOf("b"), g.arcsFrom(NodeId("a")).map { it.to.value })
        assertTrue(g.arcsFrom(NodeId("b")).isEmpty())
    }

    @Test
    fun rejectsInvalidConfigurations() {
        val nodes = listOf(node("a", 0.0, 0.0), node("b", 0.0, 5.0), node("c", 0.0, 5.01), node("u", 1.0, 1.0, floor = FloorId("upper")))
        val errs = errors(
            nodes + node("a", 9.0, 9.0),
            listOf(edge("a", "x"), edge("a", "a"), edge("b", "c"), edge("a", "u"), edge("a", "b"), edge("b", "a")),
        )
        assertTrue(errs.contains(GraphError.DuplicateNode(NodeId("a"))))
        assertTrue(errs.contains(GraphError.UnknownNode(0, NodeId("x"))))
        assertTrue(errs.contains(GraphError.SelfLoop(1)))
        assertTrue(errs.contains(GraphError.ZeroLengthEdge(2)))
        assertTrue(errs.contains(GraphError.CrossFloorEdge(3)))
        assertTrue(errs.contains(GraphError.DuplicateEdge(5)))
    }

    @Test
    fun jsonErrorsAreReported() {
        assertTrue(VenueGraphJson.parse("{ not json") is GraphLoadResult.ParseError)
        assertTrue(VenueGraphJson.parse("""{"venueId":"v"}""") is GraphLoadResult.ParseError) // frameVersion missing
        assertEquals(GraphLoadResult.UnsupportedFormat(2), VenueGraphJson.parse("""{"formatVersion":2,"venueId":"v","frameVersion":1}"""))
        val bad = VenueGraphJson.parse(
            """{"venueId":"v","frameVersion":1,"nodes":[{"id":"a","floor":"g","x":0,"z":0}],"edges":[{"from":"a","to":"b"}]}""",
        )
        assertEquals(GraphLoadResult.InvalidGraph(listOf(GraphError.UnknownNode(0, NodeId("b")))), bad)
    }

    @Test
    fun emptyGraphParsesButIsEmpty() {
        val g = (VenueGraphJson.parse("""{"venueId":"default-venue","frameVersion":1}""") as GraphLoadResult.Loaded).graph
        assertTrue(g.isEmpty)
    }
}
