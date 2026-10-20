package com.indoorar.wayfinder.domain.geometry

import kotlin.math.PI
import kotlin.math.atan2

/** Wraps an angle to the range (-π, π]. */
fun wrapAngle(radians: Double): Double {
    var a = radians % (2 * PI)
    if (a <= -PI) a += 2 * PI
    if (a > PI) a -= 2 * PI
    return a
}

/** Signed smallest difference `to - from`, in (-π, π]. */
fun angleDifference(from: Double, to: Double): Double = wrapAngle(to - from)

/**
 * Heading (yaw) of a horizontal direction, measured from +Z towards +X, i.e. the yaw
 * that `Quaternion.fromYaw` needs to rotate +Z onto [direction].
 */
fun yawOf(direction: Vec3): Double = atan2(direction.x, direction.z)
