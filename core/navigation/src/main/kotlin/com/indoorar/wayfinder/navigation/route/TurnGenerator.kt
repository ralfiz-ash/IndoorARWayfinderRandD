package com.indoorar.wayfinder.navigation.route

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.geometry.angleDifference
import com.indoorar.wayfinder.domain.geometry.yawOf
import com.indoorar.wayfinder.navigation.graph.NodeId
import kotlin.math.abs

data class TurnPolicy(
    /** Direction changes smaller than this are "continue straight". */
    val straightThresholdRadians: Double = Math.toRadians(30.0),
    /** Turns at or beyond this are flagged sharp. */
    val sharpThresholdRadians: Double = Math.toRadians(120.0),
    /** Segments shorter than this have unreliable headings and are merged into neighbours. */
    val minSegmentMeters: Double = 0.4,
)

/**
 * Derives instructions from route geometry.
 *
 * Handedness: the venue frame is right-handed with +Y up and yaw measured from +Z towards +X.
 * Facing +Z, the user's right is −X, so a *positive* yaw change (towards +X) is a LEFT turn.
 *
 * No turn is invented where the geometry does not support it: very short segments are merged
 * (their heading is noise), and changes under the straight threshold produce no instruction.
 */
class TurnGenerator(private val policy: TurnPolicy = TurnPolicy()) {

    fun generate(segments: List<RouteSegment>, destination: Vec3, nodeAt: (Vec3) -> NodeId? = { null }): List<Maneuver> {
        if (segments.isEmpty()) return listOf(Maneuver(ManeuverType.ARRIVE, 0.0, destination))
        val total = segments.last().endDistance
        // Vertices with cumulative distance, then drop interior vertices that bound a too-short segment.
        val vertices = mutableListOf(segments.first().from to 0.0)
        segments.forEach { vertices += it.to to it.endDistance }
        val simplified = simplify(vertices)

        val maneuvers = mutableListOf(Maneuver(ManeuverType.STRAIGHT, 0.0, simplified.first().first))
        for (i in 1 until simplified.size - 1) {
            val (p, d) = simplified[i]
            val inHeading = yawOf(p - simplified[i - 1].first)
            val outHeading = yawOf(simplified[i + 1].first - p)
            val turn = angleDifference(inHeading, outHeading)
            if (abs(turn) < policy.straightThresholdRadians) continue
            maneuvers += Maneuver(
                type = if (turn > 0) ManeuverType.TURN_LEFT else ManeuverType.TURN_RIGHT,
                atDistance = d,
                position = p,
                turnRadians = turn,
                sharp = abs(turn) >= policy.sharpThresholdRadians,
                nodeId = nodeAt(p),
            )
        }
        maneuvers += Maneuver(ManeuverType.ARRIVE, total, destination)
        return maneuvers
    }

    /**
     * Merges vertices closer than [TurnPolicy.minSegmentMeters] to the previously kept vertex, so a
     * corner drawn with two nearby points still yields one turn. A short final leg replaces the last
     * kept vertex, keeping the destination exact without a spurious last-moment turn.
     */
    private fun simplify(vertices: List<Pair<Vec3, Double>>): List<Pair<Vec3, Double>> {
        if (vertices.size <= 2) return vertices
        val out = mutableListOf(vertices.first())
        for (i in 1 until vertices.size - 1) {
            if (vertices[i].first.horizontalDistanceTo(out.last().first) >= policy.minSegmentMeters) out += vertices[i]
        }
        val last = vertices.last()
        if (out.size > 1 && last.first.horizontalDistanceTo(out.last().first) < policy.minSegmentMeters) {
            out[out.lastIndex] = last
        } else {
            out += last
        }
        return out
    }
}
