package com.indoorar.wayfinder.navigation

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
import com.indoorar.wayfinder.navigation.graph.GraphBuildResult
import com.indoorar.wayfinder.navigation.graph.GraphLoadResult
import com.indoorar.wayfinder.navigation.graph.NodeId
import com.indoorar.wayfinder.navigation.graph.NodeType
import com.indoorar.wayfinder.navigation.graph.RouteEdge
import com.indoorar.wayfinder.navigation.graph.RouteGraph
import com.indoorar.wayfinder.navigation.graph.RouteNode
import com.indoorar.wayfinder.navigation.graph.VenueGraphJson

/**
 * Example office (docs/graph/venue_graph.example.json), venue frame, top view (x right is LEFT
 * when walking +Z, because facing +Z the right-hand side is −X):
 *
 *   z=20   j2 ───────── j4
 *           │           │
 *   z=10   j1 ───────── j3        room-a(−6,10) ── door-a(−3,10) ── j1
 *           │
 *   z=1  entrance                   storage wing (30,0)–(30,5) is disconnected
 *         x=0          x=10
 */
object Fixtures {
    val GROUND = FloorId("ground")
    val venue = Venue(
        VenueId("default-venue"), "Office", 1, MarkerSpec("venue_marker", 0.2, MarkerMounting.WALL),
        listOf(Floor(GROUND, VenueId("default-venue"), "Ground", 0)),
    )

    val exampleJson: String = checkNotNull(Fixtures::class.java.getResource("/venue_graph.example.json")).readText()

    val office: RouteGraph = (VenueGraphJson.parse(exampleJson) as GraphLoadResult.Loaded).graph

    fun node(id: String, x: Double, z: Double, type: NodeType = NodeType.JUNCTION, floor: FloorId = GROUND) =
        RouteNode(NodeId(id), type, floor, Vec3(x, 0.0, z))

    fun edge(a: String, b: String, bidirectional: Boolean = true, attachable: Boolean = true, blocked: Boolean = false) =
        RouteEdge(NodeId(a), NodeId(b), bidirectional, attachable, blocked)

    fun graph(nodes: List<RouteNode>, edges: List<RouteEdge>): RouteGraph =
        (RouteGraph.build(venue.id, venue.frameVersion, nodes, edges) as GraphBuildResult.Valid).graph

    /** The example office with some edges modified. */
    fun officeWith(transform: (RouteEdge) -> RouteEdge): RouteGraph =
        graph(office.nodes.values.toList(), office.edges.map(transform))

    fun destination(x: Double, z: Double, name: String = "Dest", frameVersion: Int = 1) = Destination(
        DestinationId(name), venue.id, GROUND, name, Vec3(x, -1.3, z), null, frameVersion, HitSource.PLANE, 0.05, 0, 0,
    )

    fun p(x: Double, z: Double) = Vec3(x, 0.0, z)
}
