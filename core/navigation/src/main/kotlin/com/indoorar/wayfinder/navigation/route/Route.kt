package com.indoorar.wayfinder.navigation.route

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.navigation.graph.NodeId

enum class SegmentKind {
    /** Along a configured graph edge (or part of one). */
    GRAPH,
    /** Short bounded leg from the graph to the saved destination point (≤ RoutingPolicy.maxDestinationSnapMeters). */
    ACCESS,
}

data class RouteSegment(
    val from: Vec3,
    val to: Vec3,
    val kind: SegmentKind,
    /** Distance along the route at [from]. */
    val startDistance: Double,
) {
    val length: Double = from.horizontalDistanceTo(to)
    val endDistance: Double get() = startDistance + length
}

enum class ManeuverType { STRAIGHT, TURN_LEFT, TURN_RIGHT, ARRIVE }

/**
 * An instruction anchored at a point on the route. STRAIGHT is the initial "head along the path"
 * instruction; turns are generated only where the graph geometry changes direction.
 */
data class Maneuver(
    val type: ManeuverType,
    /** Distance along the route where the maneuver happens. */
    val atDistance: Double,
    val position: Vec3,
    /** Signed heading change in radians, positive = left. Null for STRAIGHT start and ARRIVE. */
    val turnRadians: Double? = null,
    val sharp: Boolean = false,
    /** Graph node at the maneuver, if any (useful for naming junctions). */
    val nodeId: NodeId? = null,
)

/** A planned path in venue coordinates (horizontal geometry; one floor in the MVP). */
data class Route(
    val floorId: FloorId,
    val segments: List<RouteSegment>,
    val maneuvers: List<Maneuver>,
    /** Graph nodes visited, in order (excluding virtual attachment points). */
    val nodeIds: List<NodeId>,
    val destination: Vec3,
) {
    val totalLength: Double get() = segments.lastOrNull()?.endDistance ?: 0.0
    val polyline: List<Vec3> get() = if (segments.isEmpty()) listOf(destination) else listOf(segments.first().from) + segments.map { it.to }

    fun pointAt(distance: Double): Vec3 {
        if (segments.isEmpty()) return destination
        val d = distance.coerceIn(0.0, totalLength)
        val seg = segments.firstOrNull { d <= it.endDistance } ?: segments.last()
        if (seg.length < 1e-9) return seg.to
        return seg.from + (seg.to - seg.from) * ((d - seg.startDistance) / seg.length)
    }
}
