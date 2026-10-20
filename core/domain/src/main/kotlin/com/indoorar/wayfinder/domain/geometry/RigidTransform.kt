package com.indoorar.wayfinder.domain.geometry

/**
 * Rigid transform (rotation followed by translation). `aFromB.apply(pInB)` yields the point in
 * frame A. Naming convention used throughout: `<target>From<source>`.
 */
data class RigidTransform(
    val translation: Vec3 = Vec3.ZERO,
    val rotation: Quaternion = Quaternion.IDENTITY,
) {
    fun apply(point: Vec3): Vec3 = rotation.rotate(point) + translation

    fun applyToDirection(direction: Vec3): Vec3 = rotation.rotate(direction)

    fun inverse(): RigidTransform {
        val invRot = rotation.conjugate()
        return RigidTransform(-invRot.rotate(translation), invRot)
    }

    /** Returns `this ∘ other`, i.e. applies [other] first. */
    infix fun compose(other: RigidTransform) = RigidTransform(
        rotation.rotate(other.translation) + translation,
        (rotation * other.rotation).normalized(),
    )

    companion object {
        val IDENTITY = RigidTransform()
    }
}
