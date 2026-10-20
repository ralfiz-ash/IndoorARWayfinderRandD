package com.indoorar.wayfinder.navigation.guidance

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.geometry.angleDifference
import com.indoorar.wayfinder.domain.geometry.wrapAngle
import kotlin.math.abs
import kotlin.math.exp

data class SmoothingPolicy(
    /** Short time constants: suppress jitter while keeping lag around a few frames. */
    val positionTimeConstantSeconds: Double = 0.2,
    val yawTimeConstantSeconds: Double = 0.15,
    /** Larger changes are genuine (re-route, relocalization) and applied immediately. */
    val resetDistanceMeters: Double = 1.5,
    val resetAngleRadians: Double = Math.toRadians(100.0),
)

/** Exponential smoothing of a guidance pose with angle wrap-around and snap on large changes. */
class GuidanceSmoother(private val policy: SmoothingPolicy = SmoothingPolicy()) {
    private var position: Vec3? = null
    private var yaw = 0.0
    private var lastNanos = 0L

    fun reset() { position = null }

    fun update(target: RoutePose, timestampNanos: Long): RoutePose {
        val p = position
        if (p == null ||
            p.horizontalDistanceTo(target.position) > policy.resetDistanceMeters ||
            abs(angleDifference(yaw, target.yaw)) > policy.resetAngleRadians ||
            timestampNanos <= lastNanos
        ) {
            position = target.position
            yaw = target.yaw
        } else {
            val dt = (timestampNanos - lastNanos) / 1e9
            val ap = 1 - exp(-dt / policy.positionTimeConstantSeconds)
            val ay = 1 - exp(-dt / policy.yawTimeConstantSeconds)
            position = p + (target.position - p) * ap
            yaw = wrapAngle(yaw + angleDifference(yaw, target.yaw) * ay)
        }
        lastNanos = timestampNanos
        return RoutePose(position!!, yaw)
    }
}
