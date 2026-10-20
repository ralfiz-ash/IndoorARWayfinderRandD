package com.indoorar.wayfinder.navigation.graph

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.domain.venue.Venue
import com.indoorar.wayfinder.domain.venue.VenueId

@JvmInline value class NodeId(val value: String)

enum class NodeType {
    /** Point where corridors meet or a corridor bends. */
    JUNCTION,
    /** Intermediate point along a corridor (useful to follow curved paths). */
    CORRIDOR,
    /** Doorway between a room and a corridor. */
    ENTRANCE,
    /** Point inside a room. */
    ROOM,
    /** Named point of interest on the graph. */
    DESTINATION,
}

/** A walkable point in the venue frame (metres). Only x/z are used for routing; floors separate levels. */
data class RouteNode(
    val id: NodeId,
    val type: NodeType,
    val floorId: FloorId,
    val position: Vec3,
    val name: String? = null,
)

/**
 * A walkable connection. The straight segment between the two nodes must be physically walkable:
 * the graph author is responsible for that; routing never leaves the graph except for short,
 * bounded access legs (see RoutingPolicy).
 */
data class RouteEdge(
    val from: NodeId,
    val to: NodeId,
    /** False for one-way paths (e.g. an exit-only door): traversable from [from] to [to] only. */
    val bidirectional: Boolean = true,
    /** False if users/destinations must not be attached to this edge (e.g. a corridor right behind a thin wall). */
    val attachable: Boolean = true,
    /** Temporarily closed paths stay in the config but are never used. */
    val blocked: Boolean = false,
    /** Walking length if it differs from the straight-line distance (e.g. a curved path). */
    val lengthOverrideMeters: Double? = null,
)

sealed interface GraphError {
    data class DuplicateNode(val id: NodeId) : GraphError
    data class UnknownNode(val edgeIndex: Int, val id: NodeId) : GraphError
    data class SelfLoop(val edgeIndex: Int) : GraphError
    data class ZeroLengthEdge(val edgeIndex: Int) : GraphError
    data class InvalidLength(val edgeIndex: Int) : GraphError
    /** Multi-floor routing (stairs/lifts) is out of scope for the MVP. */
    data class CrossFloorEdge(val edgeIndex: Int) : GraphError
    data class DuplicateEdge(val edgeIndex: Int) : GraphError
}

sealed interface GraphBuildResult {
    data class Valid(val graph: RouteGraph) : GraphBuildResult
    data class Invalid(val errors: List<GraphError>) : GraphBuildResult
}

/** Directed traversal of an edge, used by the shortest-path search. */
data class Arc(val to: NodeId, val lengthMeters: Double, val edgeIndex: Int)

/** Immutable, validated venue route graph. Build with [RouteGraph.build]. */
class RouteGraph private constructor(
    val venueId: VenueId,
    /** Venue frame version the node coordinates were measured in. */
    val frameVersion: Int,
    val nodes: Map<NodeId, RouteNode>,
    val edges: List<RouteEdge>,
) {
    private val lengths: DoubleArray = DoubleArray(edges.size) { i ->
        val e = edges[i]
        e.lengthOverrideMeters ?: nodes.getValue(e.from).position.horizontalDistanceTo(nodes.getValue(e.to).position)
    }

    private val adjacency: Map<NodeId, List<Arc>> = buildMap<NodeId, MutableList<Arc>> {
        edges.forEachIndexed { i, e ->
            if (e.blocked) return@forEachIndexed
            getOrPut(e.from) { mutableListOf() } += Arc(e.to, lengths[i], i)
            if (e.bidirectional) getOrPut(e.to) { mutableListOf() } += Arc(e.from, lengths[i], i)
        }
    }

    val isEmpty: Boolean get() = edges.none { !it.blocked }

    fun lengthOf(edgeIndex: Int): Double = lengths[edgeIndex]

    fun arcsFrom(id: NodeId): List<Arc> = adjacency[id].orEmpty()

    fun node(id: NodeId): RouteNode = nodes.getValue(id)

    /** Coordinates are only meaningful if measured in the venue's current frame. */
    fun isCompatibleWith(venue: Venue) = venue.id == venueId && venue.frameVersion == frameVersion

    companion object {
        fun build(venueId: VenueId, frameVersion: Int, nodes: List<RouteNode>, edges: List<RouteEdge>): GraphBuildResult {
            val errors = mutableListOf<GraphError>()
            val byId = LinkedHashMap<NodeId, RouteNode>()
            for (n in nodes) {
                if (byId.put(n.id, n) != null) errors += GraphError.DuplicateNode(n.id)
            }
            val seen = HashSet<Set<NodeId>>()
            edges.forEachIndexed { i, e ->
                val a = byId[e.from]
                val b = byId[e.to]
                if (a == null) errors += GraphError.UnknownNode(i, e.from)
                if (b == null) errors += GraphError.UnknownNode(i, e.to)
                if (e.from == e.to) errors += GraphError.SelfLoop(i)
                if (a != null && b != null && e.from != e.to) {
                    if (a.floorId != b.floorId) errors += GraphError.CrossFloorEdge(i)
                    if (a.position.horizontalDistanceTo(b.position) < MIN_EDGE_LENGTH) errors += GraphError.ZeroLengthEdge(i)
                    e.lengthOverrideMeters?.let { if (it <= 0.0 || !it.isFinite()) errors += GraphError.InvalidLength(i) }
                    if (!seen.add(setOf(e.from, e.to))) errors += GraphError.DuplicateEdge(i)
                }
            }
            return if (errors.isEmpty()) {
                GraphBuildResult.Valid(RouteGraph(venueId, frameVersion, byId, edges))
            } else {
                GraphBuildResult.Invalid(errors)
            }
        }

        const val MIN_EDGE_LENGTH = 0.05
    }
}
