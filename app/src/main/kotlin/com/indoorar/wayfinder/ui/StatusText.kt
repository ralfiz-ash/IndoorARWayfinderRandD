package com.indoorar.wayfinder.ui

import com.indoorar.wayfinder.arcore.ArFailure
import com.indoorar.wayfinder.arcore.ArFrameSnapshot
import com.indoorar.wayfinder.arcore.SelectedPoint
import com.indoorar.wayfinder.domain.localization.CameraTracking
import com.indoorar.wayfinder.domain.localization.LocalizationConfidence
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.localization.LostReason
import com.indoorar.wayfinder.domain.localization.NotLocalizedReason
import com.indoorar.wayfinder.domain.localization.TrackingIssue
import com.indoorar.wayfinder.domain.selection.HitRejection
import com.indoorar.wayfinder.domain.venue.MarkerMounting
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import java.util.Locale

enum class StatusLevel { GOOD, WARN, BAD, NEUTRAL }

data class StatusText(val text: String, val level: StatusLevel)

/** User-facing texts. Pure functions so they can be unit tested and mirrored on iOS. */
object UiText {
    fun meters(value: Double) = String.format(Locale.US, "%.2f m", value)

    fun localization(status: LocalizationStatus?): StatusText = when (status) {
        null -> StatusText("Not localized in this app run", StatusLevel.NEUTRAL)
        is LocalizationStatus.NotLocalized -> when (status.reason) {
            NotLocalizedReason.SCAN_MARKER -> StatusText("Not localized: scan the venue marker", StatusLevel.WARN)
            NotLocalizedReason.MARKER_ORIENTATION_MISMATCH ->
                StatusText("Marker orientation does not match the venue setup", StatusLevel.BAD)
        }
        is LocalizationStatus.Localized -> {
            val level = when (status.confidence) {
                LocalizationConfidence.HIGH -> StatusLevel.GOOD
                LocalizationConfidence.MEDIUM -> StatusLevel.WARN
                LocalizationConfidence.LOW -> StatusLevel.BAD
            }
            val confidence = status.confidence.name.lowercase()
            StatusText("Localized (±${meters(status.estimatedErrorMeters)}, $confidence confidence)", level)
        }
        is LocalizationStatus.Interrupted -> StatusText("Tracking interrupted: ${issue(status.issue)}", StatusLevel.WARN)
        is LocalizationStatus.Lost -> StatusText("Localization lost (${lost(status.reason)}): scan the marker again", StatusLevel.BAD)
    }

    fun lost(reason: LostReason) = when (reason) {
        LostReason.TRACKING_STOPPED -> "tracking stopped"
        LostReason.INTERRUPTION_TOO_LONG -> "tracking was interrupted for too long"
        LostReason.DRIFT_TOO_LARGE -> "position drift too large"
        LostReason.MARKER_TRACKING_LOST -> "marker no longer tracked"
    }

    fun issue(issue: TrackingIssue) = when (issue) {
        TrackingIssue.INITIALIZING -> "initializing, move the phone slowly"
        TrackingIssue.EXCESSIVE_MOTION -> "moving too fast, slow down"
        TrackingIssue.INSUFFICIENT_LIGHT -> "too dark"
        TrackingIssue.INSUFFICIENT_FEATURES -> "point at a more textured area"
        TrackingIssue.CAMERA_UNAVAILABLE -> "camera in use by another app"
        TrackingIssue.BAD_STATE, TrackingIssue.UNKNOWN -> "AR tracking problem"
    }

    fun rejection(reason: HitRejection) = when (reason) {
        HitRejection.NO_SURFACE -> "No surface detected there. Move the phone slowly to scan the floor."
        HitRejection.FEATURE_POINT_ONLY -> "Only loose feature points there; aim at a detected floor area."
        HitRejection.NOT_HORIZONTAL -> "That surface is not horizontal. Select a point on the floor."
        HitRejection.OUTSIDE_PLANE -> "Outside the detected floor area. Scan more of the floor first."
        HitRejection.TOO_CLOSE -> "Too close to the camera."
        HitRejection.TOO_FAR -> "Too far away for an accurate position. Walk closer."
        HitRejection.ABOVE_FLOOR_LEVEL -> "That surface is at camera height, not the floor."
    }

    fun mounting(mounting: MarkerMounting) = when (mounting) {
        MarkerMounting.WALL -> "on a wall"
        MarkerMounting.FLOOR -> "flat on the floor"
    }

    /** Seconds without a marker fix before suggesting marker troubleshooting. */
    const val MARKER_HELP_AFTER_SECONDS = 12.0
    /** Seconds without a floor plane (while localized) before suggesting floor troubleshooting. */
    const val FLOOR_HELP_AFTER_SECONDS = 15.0

    /**
     * Main instruction for the point-selection screen, in order of what blocks the user first.
     * @param notLocalizedSeconds how long the screen has been without a usable venue frame
     * @param noFloorSeconds how long it has been localized without any detected floor plane
     */
    fun selectionInstruction(
        snapshot: ArFrameSnapshot,
        selected: SelectedPoint?,
        marker: MarkerSpec,
        notLocalizedSeconds: Double = 0.0,
        noFloorSeconds: Double = 0.0,
    ): String {
        val mounting = marker.mounting
        val loc = snapshot.localization
        return when {
            snapshot.camera is CameraTracking.Limited ->
                "Tracking limited: ${issue((snapshot.camera as CameraTracking.Limited).reason)}."
            snapshot.camera == CameraTracking.Stopped -> "Starting camera…"
            loc is LocalizationStatus.Lost -> "Localization lost (${lost(loc.reason)}). Point the camera at the venue marker again."
            loc is LocalizationStatus.NotLocalized && loc.reason == NotLocalizedReason.MARKER_ORIENTATION_MISMATCH ->
                "Marker found, but it should be mounted ${mounting(mounting)}. Check the venue setup."
            loc !is LocalizationStatus.Localized && notLocalizedSeconds >= MARKER_HELP_AFTER_SECONDS ->
                "Marker not found yet. Check that the ${(marker.physicalWidthMeters * 100).toInt()} cm printed marker is " +
                    "${mounting(mounting)}, well lit without glare, fully in view, and 0.5–2 m away. Hold the phone steady."
            loc !is LocalizationStatus.Localized -> "Step 1: Point the camera at the venue marker from within 1–2 m."
            !loc.isReliable -> "Position uncertain. Walk back to the marker and scan it again."
            selected != null -> "Point selected. Name it and save, or retry."
            !snapshot.floorPlaneDetected && noFloorSeconds >= FLOOR_HELP_AFTER_SECONDS ->
                "Floor not detected yet. Aim at the floor 1–3 m ahead and move the phone sideways slowly. " +
                    "Plain, shiny or dark floors are hard to detect: add light or include textured areas" +
                    (if (snapshot.depthEnabled) "." else ". This device has no depth support, so a detected plane is required.")
            !snapshot.floorPlaneDetected -> "Step 2: Marker found. Slowly sweep the camera over the floor to detect it."
            else -> "Step 3: Aim at the destination on the floor and tap it."
        }
    }

    fun failure(failure: ArFailure) = when (failure) {
        ArFailure.ArCoreNotInstalled -> "Google Play Services for AR is required. Install it from the Play Store and try again."
        ArFailure.DeviceNotSupported -> "This device does not support ARCore."
        ArFailure.ArCoreUpdateRequired -> "Please update Google Play Services for AR."
        ArFailure.AppUpdateRequired -> "This app's ARCore SDK is too old for the installed ARCore. Update the app."
        ArFailure.CameraUnavailable -> "The camera is unavailable. Close other camera apps and try again."
        is ArFailure.MarkerImageRejected -> "Marker image problem: ${failure.message}"
        is ArFailure.Unexpected -> "AR failed to start: ${failure.message}"
    }
}
