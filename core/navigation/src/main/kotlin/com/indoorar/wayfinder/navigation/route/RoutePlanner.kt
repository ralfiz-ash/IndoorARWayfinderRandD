package com.indoorar.wayfinder.navigation.route

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.navigation.graph.NodeId
import com.indoorar.wayfinder.navigation.graph.RouteGraph
import java.util.PriorityQueue

enum class RouteFailure {
    /** The graph has no usable edges. */
    NO_GRAPH,
    /** Start and destination are on different floors (multi-floor routing not supported yet). */
    DIFFERENT_FLOORS,
    /** No attachable path within [RoutingPolicy.maxStartSnapMeters] of the user. */
    START_OFF_GRAPH,
    /** No attachable path within [RoutingPolicy.maxDestinationSnapMeters] of the destination. */
    DESTINATION_OFF_GRAPH,
    /** Both ends are on the graph but not connected (or only against one-way edges). */
    NO_CONNECTED_PATH,
}

sealed interface RoutePlan {
    data class Found(val route: Route) : RoutePlan
    data class NotFound(val reason: RouteFailure) : RoutePlan
}

data class RoutingPolicy(
    /**
     * Max horizontal distance between the user and the nearest walkable edge. Kept small: the
     * leg from the user to that edge is assumed walkable and is never rendered as a path.
     */
    val maxStartSnapMeters: Double = 3.0,
    /** Max distance from a saved destination to the graph; the last access leg is a straight line. */
    val maxDestinationSnapMeters: Double = 2.0,
    val turns: TurnPolicy = TurnPolicy(),
)

/** Point on an edge closest to a query position. [t] ∈ [0,1] from edge.from to edge.to. */
data class EdgeAttachment(val edgeIndex: Int, val t: Double, val point: Vec3, val distance: Double)

/**
 * Shortest walkable route over the venue graph (Dijkstra). The user and destination are attached
 * to their nearest attachable, unblocked edge on the same floor; routes never cut across
 * unmapped space.
 */
class RoutePlanner(private val graph: RouteGraph, private val policy: RoutingPolicy = RoutingPolicy()) {

    fun plan(start: Vec3, startFloor: FloorId, destination: Vec3, destinationFloor: FloorId): RoutePlan {
        if (graph.isEmpty) return RoutePlan.NotFound(RouteFailure.NO_GRAPH)
        if (startFloor != destinationFloor) return RoutePlan.NotFound(RouteFailure.DIFFERENT_FLOORS)
        val a = attach(start, startFloor, policy.maxStartSnapMeters)
            ?: return RoutePlan.NotFound(RouteFailure.START_OFF_GRAPH)
        val b = attach(destination, destinationFloor, policy.maxDestinationSnapMeters)
            ?: return RoutePlan.NotFound(RouteFailure.DESTINATION_OFF_GRAPH)
        val path = shortestPath(a, b) ?: return RoutePlan.NotFound(RouteFailure.NO_CONNECTED_PATH)
        return RoutePlan.Found(buildRoute(path, a, b, destination, startFloor))
    }

    /** Nearest point on an attachable, unblocked edge on [floor] within [maxDistance]. */
    fun attach(position: Vec3, floor: FloorId, maxDistance: Double): EdgeAttachment? {
        var best: EdgeAttachment? = null
        graph.edges.forEachIndexed { i, e ->
            if (e.blocked || !e.attachable) return@forEachIndexed
            val from = graph.node(e.from)
            if (from.floorId != floor) return@forEachIndexed
            val to = graph.node(e.to)
            val ab = (to.position - from.position).horizontal()
            val ap = (position - from.position).horizontal()
            val t = ((ap dot ab) / (ab dot ab)).coerceIn(0.0, 1.0)
            val point = from.position + (to.position - from.position) * t
            val d = point.horizontalDistanceTo(position)
            if (d <= maxDistance && (best == null || d < best.distance - 1e-9)) best = EdgeAttachment(i, t, point, d)
        }
        return best
    }

    // ---- Dijkstra over graph nodes plus two virtual nodes on the attached edges ----

    private sealed interface Vertex {
        data class Graph(val id: NodeId) : Vertex
        data object Start : Vertex
        data object End : Vertex
    }

    private data class Step(val to: Vertex, val length: Double)

    private fun neighbors(v: Vertex, a: EdgeAttachment, b: EdgeAttachment): List<Step> {
        val steps = mutableListOf<Step>()
        when (v) {
            Vertex.Start -> {
                val e = graph.edges[a.edgeIndex]
                val len = graph.lengthOf(a.edgeIndex)
                steps += Step(Vertex.Graph(e.to), (1 - a.t) * len)
                if (e.bidirectional) steps += Step(Vertex.Graph(e.from), a.t * len)
                if (a.edgeIndex == b.edgeIndex && (b.t >= a.t || e.bidirectional)) {
                    steps += Step(Vertex.End, kotlin.math.abs(b.t - a.t) * len)
                }
            }
            is Vertex.Graph -> {
                for (arc in graph.arcsFrom(v.id)) steps += Step(Vertex.Graph(arc.to), arc.lengthMeters)
                // Enter the destination's edge part-way.
                val e = graph.edges[b.edgeIndex]
                val len = graph.lengthOf(b.edgeIndex)
                if (v.id == e.from) steps += Step(Vertex.End, b.t * len)
                if (v.id == e.to && e.bidirectional) steps += Step(Vertex.End, (1 - b.t) * len)
            }
            Vertex.End -> Unit
        }
        return steps
    }

    private fun shortestPath(a: EdgeAttachment, b: EdgeAttachment): List<Vertex>? {
        val dist = HashMap<Vertex, Double>()
        val prev = HashMap<Vertex, Vertex>()
        var order = 0L
        data class Entry(val v: Vertex, val d: Double, val seq: Long)
        // Deterministic tie-breaking by insertion order.
        val queue = PriorityQueue<Entry>(compareBy<Entry> { it.d }.thenBy { it.seq })
        dist[Vertex.Start] = 0.0
        queue += Entry(Vertex.Start, 0.0, order++)
        while (queue.isNotEmpty()) {
            val (v, d) = queue.poll()
            if (d > (dist[v] ?: Double.MAX_VALUE)) continue
            if (v == Vertex.End) break
            for (step in neighbors(v, a, b)) {
                val nd = d + step.length
                if (nd < (dist[step.to] ?: Double.MAX_VALUE) - 1e-12) {
                    dist[step.to] = nd
                    prev[step.to] = v
                    queue += Entry(step.to, nd, order++)
                }
            }
        }
        if (Vertex.End !in dist) return null
        val path = ArrayDeque<Vertex>()
        var cur: Vertex? = Vertex.End
        while (cur != null) {
            path.addFirst(cur)
            cur = prev[cur]
        }
        return path
    }

    private fun buildRoute(path: List<Vertex>, a: EdgeAttachment, b: EdgeAttachment, destination: Vec3, floor: FloorId): Route {
        val points = mutableListOf<Pair<Vec3, NodeId?>>()
        for (v in path) {
            points += when (v) {
                Vertex.Start -> a.point to null
                Vertex.End -> b.point to null
                is Vertex.Graph -> graph.node(v.id).position to v.id
            }
        }
        // Graph segments, dropping zero-length steps (attachment exactly on a node).
        val segments = mutableListOf<RouteSegment>()
        var distance = 0.0
        fun add(from: Vec3, to: Vec3, kind: SegmentKind) {
            if (from.horizontalDistanceTo(to) < 1e-6) return
            val s = RouteSegment(from, to, kind, distance)
            segments += s
            distance = s.endDistance
        }
        for (i in 0 until points.size - 1) add(points[i].first, points[i + 1].first, SegmentKind.GRAPH)
        add(b.point, destination, SegmentKind.ACCESS)
        val nodeIds = points.mapNotNull { it.second }
        val maneuvers = TurnGenerator(policy.turns).generate(segments, destination, nodeAt = { p ->
            points.firstOrNull { it.second != null && it.first.horizontalDistanceTo(p) < 1e-6 }?.second
        })
        return Route(floor, segments, maneuvers, nodeIds, destination)
    }
}
