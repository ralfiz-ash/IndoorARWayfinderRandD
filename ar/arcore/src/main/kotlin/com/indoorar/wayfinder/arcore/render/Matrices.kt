package com.indoorar.wayfinder.arcore.render

import android.opengl.Matrix
import com.indoorar.wayfinder.domain.geometry.Quaternion
import com.indoorar.wayfinder.domain.geometry.RigidTransform
import com.indoorar.wayfinder.domain.geometry.Vec3

/** Column-major 4x4 model matrix for a rigid transform, optionally scaled uniformly. */
fun RigidTransform.toModelMatrix(out: FloatArray = FloatArray(16), scale: Float = 1f): FloatArray {
    val q: Quaternion = rotation
    val x = q.x.toFloat(); val y = q.y.toFloat(); val z = q.z.toFloat(); val w = q.w.toFloat()
    out[0] = (1 - 2 * (y * y + z * z)) * scale; out[1] = (2 * (x * y + z * w)) * scale; out[2] = (2 * (x * z - y * w)) * scale; out[3] = 0f
    out[4] = (2 * (x * y - z * w)) * scale; out[5] = (1 - 2 * (x * x + z * z)) * scale; out[6] = (2 * (y * z + x * w)) * scale; out[7] = 0f
    out[8] = (2 * (x * z + y * w)) * scale; out[9] = (2 * (y * z - x * w)) * scale; out[10] = (1 - 2 * (x * x + y * y)) * scale; out[11] = 0f
    out[12] = translation.x.toFloat(); out[13] = translation.y.toFloat(); out[14] = translation.z.toFloat(); out[15] = 1f
    return out
}

fun translationMatrix(p: Vec3, out: FloatArray = FloatArray(16)): FloatArray {
    Matrix.setIdentityM(out, 0)
    Matrix.translateM(out, 0, p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
    return out
}
