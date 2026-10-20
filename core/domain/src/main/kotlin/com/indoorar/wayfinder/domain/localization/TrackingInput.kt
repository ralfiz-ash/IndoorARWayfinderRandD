package com.indoorar.wayfinder.domain.localization

import com.indoorar.wayfinder.domain.geometry.RigidTransform
import com.indoorar.wayfinder.domain.geometry.Vec3

/** Platform-neutral camera tracking state (maps from ARCore `TrackingState` / ARKit `ARCamera.TrackingState`). */
sealed interface CameraTracking {
    data object Tracking : CameraTracking
    data class Limited(val reason: TrackingIssue) : CameraTracking
    data object Stopped : CameraTracking
}

enum class TrackingIssue {
    INITIALIZING,
    EXCESSIVE_MOTION,
    INSUFFICIENT_LIGHT,
    INSUFFICIENT_FEATURES,
    CAMERA_UNAVAILABLE,
    BAD_STATE,
    UNKNOWN,
}

enum class MarkerTrackingQuality {
    /** The marker is currently visible and its pose is measured from the camera image. */
    FULL,
    /** The marker is not in view; the AR system reports its last known pose (subject to drift). */
    LAST_KNOWN,
}

data class MarkerObservation(
    val imageName: String,
    val poseInSession: RigidTransform,
    val quality: MarkerTrackingQuality,
)

/** One AR frame's worth of tracking information, as produced by a platform AR adapter. */
data class TrackingInput(
    val timestampNanos: Long,
    val camera: CameraTracking,
    /** Camera position in session coordinates; null when the camera is not tracking. */
    val cameraPosition: Vec3?,
    /** The venue marker, if the AR system currently tracks it in any form. */
    val marker: MarkerObservation?,
)
