package com.indoorar.wayfinder.navigation.guidance

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.geometry.yawOf
import com.indoorar.wayfinder.navigation.route.Maneuver
import com.indoorar.wayfinder.navigation.route.ManeuverType
import com.indoorar.wayfinder.navigation.route.Route
import kotlin.math.abs
import kotlin.math.max

/** Where the user is relative to the route (venue frame, horizontal). */
data class RouteProgress(
    /** Distance along the route of the user's projection onto it. */
    val distanceAlong: Double,
    val remainingDistance: Double,
    /** Horizontal distance from the user to the route. */
    val lateralOffset: Double,
    val pointOnRoute: Vec3,
    val segmentIndex: Int,
    /** Venue-frame yaw of the route at the user's projection (direction to walk). */
    val routeHeading: Double,
    val nextManeuver: Maneuver,
    val distanceToNextManeuver: Double,
    /** Straight-line distance to the destination; NOT a walkable distance. */
    val straightLineToDestination: Double,
)

data class ProgressPolicy(
    /** A maneuver counts as passed once the user is this far beyond it. */
    val passedToleranceMeters: Double = 0.75,
    /**
     * Penalty per metre of along-route jump away from the previous progress (beyond the free
     * window), so that a nearby parallel or crossing part of the route does not steal the projection.
     */
    val continuityWeight: Double = 0.3,
    val continuityFreeMeters: Double = 3.0,
)

object RouteProgressCalculator {
    fun compute(route: Route, position: Vec3, previousAlong: Double? = null, policy: ProgressPolicy = ProgressPolicy()): RouteProgress {
        val destinationDistance = position.horizontalDistanceTo(route.destination)
        if (route.segments.isEmpty()) {
            val arrive = route.maneuvers.last()
            return RouteProgress(0.0, 0.0, destinationDistance, route.destination, 0, 0.0, arrive, 0.0, destinationDistance)
        }
        var bestScore = Double.MAX_VALUE
        var bestIndex = 0
        var bestAlong = 0.0
        var bestPoint = route.segments.first().from
        var bestLateral = 0.0
        route.segments.forEachIndexed { i, s ->
            val ab = (s.to - s.from).horizontal()
            val len2 = ab dot ab
            val t = if (len2 < 1e-12) 0.0 else (((position - s.from).horizontal() dot ab) / len2).coerceIn(0.0, 1.0)
            val point = s.from + (s.to - s.from) * t
            val lateral = point.horizontalDistanceTo(position)
            val along = s.startDistance + t * s.length
            val jump = previousAlong?.let { max(0.0, abs(along - it) - policy.continuityFreeMeters) } ?: 0.0
            val score = lateral + policy.continuityWeight * jump
            if (score < bestScore - 1e-9) {
                bestScore = score; bestIndex = i; bestAlong = along; bestPoint = point; bestLateral = lateral
            }
        }
        // A turn stays "next" until the user is a little past it, so "turn now" is not skipped early.
        val next = route.maneuvers.firstOrNull {
            it.type != ManeuverType.STRAIGHT && it.atDistance > bestAlong - policy.passedToleranceMeters
        } ?: route.maneuvers.last()
        val seg = route.segments[bestIndex]
        return RouteProgress(
            distanceAlong = bestAlong,
            remainingDistance = route.totalLength - bestAlong,
            lateralOffset = bestLateral,
            pointOnRoute = bestPoint,
            segmentIndex = bestIndex,
            routeHeading = yawOf(seg.to - seg.from),
            nextManeuver = next,
            distanceToNextManeuver = max(0.0, next.atDistance - bestAlong),
            straightLineToDestination = destinationDistance,
        )
    }
}
