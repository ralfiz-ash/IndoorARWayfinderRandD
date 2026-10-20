package com.indoorar.wayfinder.domain.geometry

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit rotation quaternion in (x, y, z, w) order — the same layout used by ARCore `Pose`
 * and ARKit `simd_quatf`, so adapters can copy components directly.
 */
data class Quaternion(val x: Double, val y: Double, val z: Double, val w: Double) {

    operator fun times(o: Quaternion) = Quaternion(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    fun conjugate() = Quaternion(-x, -y, -z, w)

    fun normalized(): Quaternion {
        val n = sqrt(x * x + y * y + z * z + w * w)
        require(n > Vec3.EPSILON) { "Cannot normalize a zero quaternion" }
        return Quaternion(x / n, y / n, z / n, w / n)
    }

    /** Rotates [v] by this quaternion (assumed unit length). */
    fun rotate(v: Vec3): Vec3 {
        val u = Vec3(x, y, z)
        val t = (u cross v) * 2.0
        return v + t * w + (u cross t)
    }

    companion object {
        val IDENTITY = Quaternion(0.0, 0.0, 0.0, 1.0)

        /** Rotation of [radians] around the vertical (+Y, gravity-up) axis. */
        fun fromYaw(radians: Double) = Quaternion(0.0, sin(radians / 2), 0.0, cos(radians / 2))

        fun fromAxisAngle(axis: Vec3, radians: Double): Quaternion {
            val a = axis.normalized()
            val s = sin(radians / 2)
            return Quaternion(a.x * s, a.y * s, a.z * s, cos(radians / 2))
        }
    }
}
