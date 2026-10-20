package com.indoorar.wayfinder.domain.geometry

import kotlin.math.sqrt

/** Immutable 3D vector in metres. Axis meaning depends on the frame it is expressed in. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    val length: Double get() = sqrt(x * x + y * y + z * z)

    /** Length ignoring the vertical (Y) component. */
    val horizontalLength: Double get() = sqrt(x * x + z * z)

    fun normalized(): Vec3 {
        val l = length
        require(l > EPSILON) { "Cannot normalize a zero-length vector" }
        return Vec3(x / l, y / l, z / l)
    }

    /** Projection onto the horizontal (XZ) plane. */
    fun horizontal() = Vec3(x, 0.0, z)

    fun distanceTo(o: Vec3) = (this - o).length
    fun horizontalDistanceTo(o: Vec3) = (this - o).horizontalLength

    fun isApproximately(o: Vec3, tolerance: Double = 1e-6) = distanceTo(o) <= tolerance

    companion object {
        const val EPSILON = 1e-9
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UNIT_X = Vec3(1.0, 0.0, 0.0)
        val UNIT_Y = Vec3(0.0, 1.0, 0.0)
        val UNIT_Z = Vec3(0.0, 0.0, 1.0)
    }
}
