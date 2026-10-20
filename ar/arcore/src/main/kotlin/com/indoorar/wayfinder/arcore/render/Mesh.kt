package com.indoorar.wayfinder.arcore.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Non-indexed triangle mesh with interleaved position (xyz) and normal (xyz), in metres. */
class Mesh(val vertexData: FloatArray) {
    val vertexCount: Int get() = vertexData.size / 6
}

/** Procedural meshes; keeps the MVP free of model files and loaders. */
object MeshFactory {
    private class Builder {
        val data = ArrayList<Float>()
        fun tri(a: FloatArray, b: FloatArray, c: FloatArray, n: FloatArray? = null) {
            val normal = n ?: normalOf(a, b, c)
            for (v in arrayOf(a, b, c)) { data += v.toList(); data += normal.toList() }
        }
        fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) { tri(a, b, c); tri(a, c, d) }
        fun build() = Mesh(data.toFloatArray())
    }

    private fun normalOf(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
        val u = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        val v = floatArrayOf(c[0] - a[0], c[1] - a[1], c[2] - a[2])
        val n = floatArrayOf(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
        val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).coerceAtLeast(1e-6f)
        return floatArrayOf(n[0] / l, n[1] / l, n[2] / l)
    }

    private fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    /** Flat ring lying in the XZ plane, facing +Y. */
    fun annulus(inner: Float, outer: Float, segments: Int = 48): Mesh = Builder().apply {
        val up = p(0f, 1f, 0f)
        for (i in 0 until segments) {
            val a0 = (2 * PI * i / segments).toFloat()
            val a1 = (2 * PI * (i + 1) / segments).toFloat()
            val i0 = p(inner * cos(a0), 0f, inner * sin(a0)); val o0 = p(outer * cos(a0), 0f, outer * sin(a0))
            val i1 = p(inner * cos(a1), 0f, inner * sin(a1)); val o1 = p(outer * cos(a1), 0f, outer * sin(a1))
            tri(i0, i1, o1, up); tri(i0, o1, o0, up)
        }
    }.build()

    /** Filled disc in the XZ plane, facing +Y. */
    fun disc(radius: Float, segments: Int = 48): Mesh = Builder().apply {
        val up = p(0f, 1f, 0f)
        for (i in 0 until segments) {
            val a0 = (2 * PI * i / segments).toFloat()
            val a1 = (2 * PI * (i + 1) / segments).toFloat()
            tri(p(0f, 0f, 0f), p(radius * cos(a1), 0f, radius * sin(a1)), p(radius * cos(a0), 0f, radius * sin(a0)), up)
        }
    }.build()

    /** Cone with its apex at the origin, opening upwards to [radius] at [height]. Used as a location pin. */
    fun invertedCone(radius: Float, height: Float, segments: Int = 24): Mesh = Builder().apply {
        val apex = p(0f, 0f, 0f)
        val top = p(0f, height, 0f)
        for (i in 0 until segments) {
            val a0 = (2 * PI * i / segments).toFloat()
            val a1 = (2 * PI * (i + 1) / segments).toFloat()
            val b0 = p(radius * cos(a0), height, radius * sin(a0))
            val b1 = p(radius * cos(a1), height, radius * sin(a1))
            tri(apex, b0, b1)
            tri(top, b1, b0, p(0f, 1f, 0f))
        }
    }.build()

    /** Axis-aligned box centred at [cx],[cy],[cz]. */
    fun box(sx: Float, sy: Float, sz: Float, cx: Float = 0f, cy: Float = 0f, cz: Float = 0f): Mesh = Builder().apply {
        val x0 = cx - sx / 2; val x1 = cx + sx / 2
        val y0 = cy - sy / 2; val y1 = cy + sy / 2
        val z0 = cz - sz / 2; val z1 = cz + sz / 2
        quad(p(x0, y1, z0), p(x0, y1, z1), p(x1, y1, z1), p(x1, y1, z0)) // top
        quad(p(x0, y0, z0), p(x1, y0, z0), p(x1, y0, z1), p(x0, y0, z1)) // bottom
        quad(p(x0, y0, z1), p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1)) // +z
        quad(p(x1, y0, z0), p(x0, y0, z0), p(x0, y1, z0), p(x1, y1, z0)) // -z
        quad(p(x1, y0, z1), p(x1, y0, z0), p(x1, y1, z0), p(x1, y1, z1)) // +x
        quad(p(x0, y0, z0), p(x0, y0, z1), p(x0, y1, z1), p(x0, y1, z0)) // -x
    }.build()
}
