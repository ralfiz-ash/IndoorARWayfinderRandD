package com.indoorar.wayfinder.arcore

import com.google.ar.core.AugmentedImage
import com.google.ar.core.Camera
import com.google.ar.core.DepthPoint
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.indoorar.wayfinder.domain.geometry.Quaternion
import com.indoorar.wayfinder.domain.geometry.RigidTransform
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.localization.CameraTracking
import com.indoorar.wayfinder.domain.localization.MarkerObservation
import com.indoorar.wayfinder.domain.localization.MarkerTrackingQuality
import com.indoorar.wayfinder.domain.localization.TrackingIssue
import com.indoorar.wayfinder.domain.selection.HitCandidate
import com.indoorar.wayfinder.domain.selection.HitSource
import com.indoorar.wayfinder.domain.selection.PlaneOrientation

/** ARCore ↔ domain conversions. ARCore world space is gravity-aligned with +Y up, metres. */

fun Pose.toRigidTransform() = RigidTransform(
    Vec3(tx().toDouble(), ty().toDouble(), tz().toDouble()),
    Quaternion(qx().toDouble(), qy().toDouble(), qz().toDouble(), qw().toDouble()),
)

fun Pose.translationVec() = Vec3(tx().toDouble(), ty().toDouble(), tz().toDouble())

fun Vec3.toFloatArray() = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())

fun Camera.toCameraTracking(): CameraTracking = when (trackingState) {
    TrackingState.TRACKING -> CameraTracking.Tracking
    TrackingState.PAUSED -> CameraTracking.Limited(trackingFailureReason.toIssue())
    TrackingState.STOPPED -> CameraTracking.Stopped
}

private fun TrackingFailureReason.toIssue() = when (this) {
    TrackingFailureReason.NONE -> TrackingIssue.INITIALIZING
    TrackingFailureReason.BAD_STATE -> TrackingIssue.BAD_STATE
    TrackingFailureReason.INSUFFICIENT_LIGHT -> TrackingIssue.INSUFFICIENT_LIGHT
    TrackingFailureReason.EXCESSIVE_MOTION -> TrackingIssue.EXCESSIVE_MOTION
    TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingIssue.INSUFFICIENT_FEATURES
    TrackingFailureReason.CAMERA_UNAVAILABLE -> TrackingIssue.CAMERA_UNAVAILABLE
}

fun AugmentedImage.toObservation(): MarkerObservation? {
    if (trackingState != TrackingState.TRACKING) return null
    val quality = when (trackingMethod) {
        AugmentedImage.TrackingMethod.FULL_TRACKING -> MarkerTrackingQuality.FULL
        AugmentedImage.TrackingMethod.LAST_KNOWN_POSE -> MarkerTrackingQuality.LAST_KNOWN
        else -> return null
    }
    return MarkerObservation(name, centerPose.toRigidTransform(), quality)
}

/** Converts an ARCore hit to a platform-neutral candidate. Validation happens in the domain layer. */
fun HitResult.toCandidate(): HitCandidate {
    val pose = hitPose
    val position = pose.translationVec()
    val normal = Vec3(pose.yAxis[0].toDouble(), pose.yAxis[1].toDouble(), pose.yAxis[2].toDouble())
    return when (val trackable = trackable) {
        is Plane -> HitCandidate(
            source = HitSource.PLANE,
            positionInSession = position,
            surfaceNormal = normal,
            distanceFromCamera = distance.toDouble(),
            planeOrientation = when (trackable.type) {
                Plane.Type.HORIZONTAL_UPWARD_FACING -> PlaneOrientation.HORIZONTAL_UP
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> PlaneOrientation.HORIZONTAL_DOWN
                else -> PlaneOrientation.VERTICAL
            },
            insidePlanePolygon = trackable.trackingState == TrackingState.TRACKING &&
                trackable.subsumedBy == null && trackable.isPoseInPolygon(pose),
        )
        is DepthPoint -> HitCandidate(HitSource.DEPTH, position, normal, distance.toDouble())
        is Point -> HitCandidate(HitSource.FEATURE_POINT, position, null, distance.toDouble())
        else -> HitCandidate(HitSource.OTHER, position, null, distance.toDouble())
    }
}
