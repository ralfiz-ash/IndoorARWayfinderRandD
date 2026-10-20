package com.indoorar.wayfinder.domain.selection

import com.indoorar.wayfinder.domain.geometry.Vec3

/** Origin of a hit-test result, independent of the AR SDK. */
enum class HitSource {
    /** Detected plane (ARCore `Plane`, ARKit `ARPlaneAnchor` / existing-plane raycast). */
    PLANE,
    /** Depth-based surface estimate (ARCore `DepthPoint`, ARKit LiDAR estimated-plane raycast). */
    DEPTH,
    /** Sparse feature point. Never accepted for saving. */
    FEATURE_POINT,
    OTHER,
}

enum class PlaneOrientation { HORIZONTAL_UP, HORIZONTAL_DOWN, VERTICAL }

/** A single raycast/hit-test result converted to domain types (session coordinates). */
data class HitCandidate(
    val source: HitSource,
    val positionInSession: Vec3,
    /** Surface normal in session coordinates, if the SDK provides one. */
    val surfaceNormal: Vec3?,
    val distanceFromCamera: Double,
    /** Plane hits only. */
    val planeOrientation: PlaneOrientation? = null,
    /** Plane hits only: whether the hit lies inside the detected plane polygon (not on its infinite extension). */
    val insidePlanePolygon: Boolean = false,
)
