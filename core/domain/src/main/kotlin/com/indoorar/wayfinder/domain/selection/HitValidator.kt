package com.indoorar.wayfinder.domain.selection

import com.indoorar.wayfinder.domain.geometry.Vec3
import kotlin.math.cos

enum class HitRejection {
    NO_SURFACE,
    FEATURE_POINT_ONLY,
    NOT_HORIZONTAL,
    OUTSIDE_PLANE,
    TOO_CLOSE,
    TOO_FAR,
    ABOVE_FLOOR_LEVEL,
}

sealed interface HitValidation {
    data class Accepted(val hit: HitCandidate, val isLikelyFloor: Boolean) : HitValidation
    data class Rejected(val reason: HitRejection) : HitValidation
}

data class HitPolicy(
    val minDistanceMeters: Double = 0.3,
    val maxDistanceMeters: Double = 6.0,
    /** Max angle between the surface normal and +Y for depth hits. */
    val maxNormalTiltRadians: Double = Math.toRadians(25.0),
    /** A walkable destination must be at least this far below the camera. */
    val minDropBelowCameraMeters: Double = 0.3,
    /** Below this drop the surface is more likely a table than the floor. */
    val likelyFloorDropMeters: Double = 0.9,
)

/**
 * Chooses a trustworthy destination point from raw hit results.
 * Preference: horizontal upward plane inside its polygon, then a depth hit with an upward normal.
 */
class HitValidator(private val policy: HitPolicy = HitPolicy()) {

    fun select(candidates: List<HitCandidate>, cameraPosition: Vec3): HitValidation {
        if (candidates.isEmpty()) return HitValidation.Rejected(HitRejection.NO_SURFACE)
        val evaluated = candidates.map { it to evaluate(it, cameraPosition) }
        val accepted = evaluated.filter { it.second == null }.map { it.first }
        val best = accepted.firstOrNull { it.source == HitSource.PLANE }
            ?: accepted.firstOrNull { it.source == HitSource.DEPTH }
        if (best != null) {
            val drop = cameraPosition.y - best.positionInSession.y
            return HitValidation.Accepted(best, isLikelyFloor = drop >= policy.likelyFloorDropMeters)
        }
        // Report the rejection of the nearest candidate: that is what the user aimed at.
        return HitValidation.Rejected(evaluated.minBy { it.first.distanceFromCamera }.second!!)
    }

    /** Returns null when acceptable, otherwise the rejection reason. */
    fun evaluate(hit: HitCandidate, cameraPosition: Vec3): HitRejection? {
        when (hit.source) {
            HitSource.FEATURE_POINT -> return HitRejection.FEATURE_POINT_ONLY
            HitSource.OTHER -> return HitRejection.NO_SURFACE
            HitSource.PLANE -> {
                if (hit.planeOrientation != PlaneOrientation.HORIZONTAL_UP) return HitRejection.NOT_HORIZONTAL
                if (!hit.insidePlanePolygon) return HitRejection.OUTSIDE_PLANE
            }
            HitSource.DEPTH -> {
                val normal = hit.surfaceNormal ?: return HitRejection.NOT_HORIZONTAL
                if (normal.length < 1e-6 || normal.normalized().y < cos(policy.maxNormalTiltRadians)) {
                    return HitRejection.NOT_HORIZONTAL
                }
            }
        }
        if (hit.distanceFromCamera < policy.minDistanceMeters) return HitRejection.TOO_CLOSE
        if (hit.distanceFromCamera > policy.maxDistanceMeters) return HitRejection.TOO_FAR
        if (cameraPosition.y - hit.positionInSession.y < policy.minDropBelowCameraMeters) {
            return HitRejection.ABOVE_FLOOR_LEVEL
        }
        return null
    }
}
