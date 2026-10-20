package com.indoorar.wayfinder.navigation.guidance

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.geometry.angleDifference
import com.indoorar.wayfinder.domain.geometry.yawOf
import com.indoorar.wayfinder.navigation.route.Route

/** A guidance glyph placed on the route: venue-frame position (x/z; y set by the renderer) and heading. */
data class RoutePose(val position: Vec3, val yaw: Double)

data class GuidanceLayout(
    /** How far ahead of the user (along the route) the floating arrow sits. */
    val arrowLookaheadMeters: Double = 2.0,
    /** Half-window used to estimate the route direction at a point; blends smoothly into turns. */
    val tangentWindowMeters: Double = 0.75,
    val chevronStartMeters: Double = 1.0,
    val chevronSpacingMeters: Double = 1.5,
    val chevronCount: Int = 5,
)

/** Pure geometry for placing world-aligned guidance along a route (venue frame). */
object GuidanceGeometry {

    /** Route direction at [distance], estimated over a small window so corners are anticipated smoothly. */
    fun tangentYaw(route: Route, distance: Double, window: Double): Double {
        val total = route.totalLength
        var a = (distance - window).coerceIn(0.0, total)
        var b = (distance + window).coerceIn(0.0, total)
        if (b - a < 1e-3) { a = (total - 2 * window).coerceAtLeast(0.0); b = total }
        val dir = route.pointAt(b) - route.pointAt(a)
        return if (dir.horizontalLength < 1e-6) 0.0 else yawOf(dir)
    }

    /** Pose of the floating arrow: on the route ahead of the user, pointing along it. */
    fun arrowPose(route: Route, distanceAlong: Double, layout: GuidanceLayout = GuidanceLayout()): RoutePose {
        val d = (distanceAlong + layout.arrowLookaheadMeters).coerceAtMost(route.totalLength)
        return RoutePose(route.pointAt(d), tangentYaw(route, d, layout.tangentWindowMeters))
    }

    /** Floor chevrons ahead of the user, stopping before the destination. */
    fun chevrons(route: Route, distanceAlong: Double, layout: GuidanceLayout = GuidanceLayout()): List<RoutePose> =
        (0 until layout.chevronCount)
            .map { distanceAlong + layout.chevronStartMeters + it * layout.chevronSpacingMeters }
            .filter { it < route.totalLength - 0.5 }
            .map { RoutePose(route.pointAt(it), tangentYaw(route, it, layout.tangentWindowMeters)) }

    /**
     * Signed angle from where the camera looks to where the user should go, in radians.
     * Positive = target is to the LEFT (venue frame: right-handed, +Y up, yaw from +Z towards +X).
     */
    fun relativeBearing(cameraYaw: Double, user: Vec3, target: Vec3): Double? {
        val dir = target - user
        if (dir.horizontalLength < 1e-3) return null
        return angleDifference(cameraYaw, yawOf(dir))
    }
}
