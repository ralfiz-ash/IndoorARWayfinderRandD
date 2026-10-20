package com.indoorar.wayfinder.domain.venue

import com.indoorar.wayfinder.domain.geometry.Quaternion
import com.indoorar.wayfinder.domain.geometry.RigidTransform
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.geometry.yawOf
import kotlin.math.abs

/*
 * Venue coordinate frame (documented in docs/COORDINATE_FRAMES.md):
 *  - Origin: centre of the reference marker.
 *  - +Y: up, opposite to gravity (both ARCore and ARKit world frames are gravity aligned).
 *  - +Z: horizontal "forward" of the marker (out of the wall for WALL markers, towards the image's
 *        top edge for FLOOR markers).
 *  - +X: +Y × +Z (right-handed, metres).
 *
 * Because both frames are gravity aligned, the session↔venue relationship is a translation plus a
 * rotation about Y only. Pitch/roll noise in the marker pose estimate therefore never tilts the venue.
 */

/** Relationship between the current AR session frame and the persistent venue frame. */
data class VenueAlignment(
    /** Venue origin expressed in AR session coordinates. */
    val originInSession: Vec3,
    /** Rotation about +Y taking venue axes to session axes. */
    val yawRadians: Double,
) {
    val sessionFromVenue: RigidTransform
        get() = RigidTransform(originInSession, Quaternion.fromYaw(yawRadians))

    val venueFromSession: RigidTransform get() = sessionFromVenue.inverse()

    fun toVenue(sessionPoint: Vec3): Vec3 = venueFromSession.apply(sessionPoint)
    fun toSession(venuePoint: Vec3): Vec3 = sessionFromVenue.apply(venuePoint)

    /** Converts a yaw measured in the session frame to the venue frame. */
    fun yawToVenue(sessionYaw: Double): Double = sessionYaw - yawRadians
    fun yawToSession(venueYaw: Double): Double = venueYaw + yawRadians
}

sealed interface AlignmentResult {
    data class Valid(val alignment: VenueAlignment) : AlignmentResult
    /** The detected marker orientation does not match the configured mounting. */
    data class OrientationMismatch(val expected: MarkerMounting) : AlignmentResult
}

object VenueFrameEstimator {
    /** Max |normal·up| for a marker to count as wall-mounted (≈ ±35° from vertical). */
    const val WALL_MAX_NORMAL_UP = 0.57
    /** Min normal·up for a marker to count as lying on the floor (≈ 30° tilt). */
    const val FLOOR_MIN_NORMAL_UP = 0.86

    /**
     * Computes the alignment from the marker's pose in the session frame.
     *
     * The marker pose uses the common image-tracking convention: +X towards the image's right
     * edge, +Z towards its bottom edge, +Y the outward surface normal (ARCore `AugmentedImage`
     * centre pose; ARKit `ARImageAnchor` uses the same convention).
     */
    fun estimate(markerPoseInSession: RigidTransform, mounting: MarkerMounting): AlignmentResult {
        val rotation = markerPoseInSession.rotation
        val normal = rotation.rotate(Vec3.UNIT_Y)
        val forward = when (mounting) {
            MarkerMounting.WALL -> {
                if (abs(normal.y) > WALL_MAX_NORMAL_UP) return AlignmentResult.OrientationMismatch(mounting)
                normal.horizontal()
            }
            MarkerMounting.FLOOR -> {
                if (normal.y < FLOOR_MIN_NORMAL_UP) return AlignmentResult.OrientationMismatch(mounting)
                (-rotation.rotate(Vec3.UNIT_Z)).horizontal()
            }
        }
        if (forward.length < 1e-3) return AlignmentResult.OrientationMismatch(mounting)
        return AlignmentResult.Valid(
            VenueAlignment(markerPoseInSession.translation, yawOf(forward.normalized())),
        )
    }
}
