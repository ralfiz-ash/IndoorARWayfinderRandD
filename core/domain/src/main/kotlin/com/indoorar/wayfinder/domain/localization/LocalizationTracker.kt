package com.indoorar.wayfinder.domain.localization

import com.indoorar.wayfinder.domain.venue.AlignmentResult
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import com.indoorar.wayfinder.domain.venue.VenueAlignment
import com.indoorar.wayfinder.domain.venue.VenueFrameEstimator
import com.indoorar.wayfinder.domain.geometry.Vec3

/**
 * Turns per-frame AR tracking data into a [LocalizationStatus].
 *
 * Rules:
 *  - The venue frame is only established from a fully tracked marker while the camera tracks.
 *  - While the marker is out of view, the alignment is kept, but its error estimate grows with the
 *    distance walked (odometry drift).
 *  - Short camera interruptions are tolerated with an error penalty; long ones, a stopped session,
 *    or excessive estimated error require the marker to be scanned again.
 *
 * Not thread-safe: feed it from a single thread (the AR render thread).
 */
class LocalizationTracker(
    private val marker: MarkerSpec,
    private val policy: LocalizationPolicy = LocalizationPolicy(),
) {
    private val smoother = AlignmentSmoother(
        policy.smoothingTimeConstantSeconds,
        policy.smoothingResetMeters,
        policy.smoothingResetRadians,
    )
    private var errorAtLastFix = 0.0
    private var accumulatedError = 0.0
    private var lastCameraPosition: Vec3? = null
    private var interruptedSince: Long? = null

    var status: LocalizationStatus = LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER)
        private set

    /** Call when the AR session is (re)created: session coordinates from earlier sessions are meaningless. */
    fun resetForNewSession() {
        smoother.reset()
        lastCameraPosition = null
        interruptedSince = null
        accumulatedError = 0.0
        status = LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER)
    }

    /**
     * Call when the AR session resumes after the app was paused for [pausedForNanos]. The AR system
     * may silently relocalize after a pause, so the alignment survives only short pauses, with a
     * penalty; long pauses require a marker rescan.
     */
    fun onSessionResumed(pausedForNanos: Long) {
        if (smoother.current == null) return
        if (pausedForNanos > policy.maxInterruptionNanos) {
            status = dropAlignment(LostReason.INTERRUPTION_TOO_LONG, status)
        } else {
            accumulatedError += policy.interruptionPenaltyMeters
            lastCameraPosition = null
        }
    }

    fun update(input: TrackingInput): LocalizationStatus {
        status = computeStatus(input)
        return status
    }

    private fun computeStatus(input: TrackingInput): LocalizationStatus {
        val current = status
        when (val camera = input.camera) {
            CameraTracking.Stopped -> return dropAlignment(LostReason.TRACKING_STOPPED, current)

            is CameraTracking.Limited -> {
                val alignment = smoother.current ?: return statusWithoutAlignment(current)
                val since = interruptedSince ?: input.timestampNanos.also { interruptedSince = it }
                if (input.timestampNanos - since > policy.maxInterruptionNanos) {
                    return dropAlignment(LostReason.INTERRUPTION_TOO_LONG, current)
                }
                lastCameraPosition = null
                return LocalizationStatus.Interrupted(alignment, camera.reason, since)
            }

            CameraTracking.Tracking -> Unit
        }

        // Camera is tracking.
        if (interruptedSince != null) {
            interruptedSince = null
            if (smoother.current != null) accumulatedError += policy.interruptionPenaltyMeters
        }

        val observation = input.marker?.takeIf { it.imageName == marker.imageName }
        val cameraPosition = input.cameraPosition

        if (observation != null) {
            when (val result = VenueFrameEstimator.estimate(observation.poseInSession, marker.mounting)) {
                is AlignmentResult.OrientationMismatch ->
                    if (observation.quality == MarkerTrackingQuality.FULL && smoother.current == null) {
                        return LocalizationStatus.NotLocalized(NotLocalizedReason.MARKER_ORIENTATION_MISMATCH)
                    }

                is AlignmentResult.Valid -> {
                    val distanceToMarker = cameraPosition?.distanceTo(observation.poseInSession.translation)
                    val isFreshFix = observation.quality == MarkerTrackingQuality.FULL &&
                        distanceToMarker != null && distanceToMarker <= policy.maxFixDistanceMeters
                    if (isFreshFix) {
                        smoother.update(result.alignment, input.timestampNanos)
                        errorAtLastFix = policy.fixErrorMeters + policy.fixErrorPerMeterToMarker * distanceToMarker
                        accumulatedError = 0.0
                    } else if (smoother.current != null) {
                        // The tracker's last-known pose follows map refinements; adopt it without
                        // resetting the accumulated drift estimate.
                        smoother.update(result.alignment, input.timestampNanos)
                    }
                }
            }
        } else if (smoother.current != null) {
            // The AR system stopped tracking the marker trackable altogether.
            return dropAlignment(LostReason.MARKER_TRACKING_LOST, current)
        }

        val alignment = smoother.current ?: return statusWithoutAlignment(current)

        if (cameraPosition != null) {
            lastCameraPosition?.let { last ->
                val step = cameraPosition.distanceTo(last)
                accumulatedError += if (step > policy.maxFrameJumpMeters) {
                    policy.interruptionPenaltyMeters
                } else {
                    step * policy.driftPerMeterWalked
                }
            }
            lastCameraPosition = cameraPosition
        }

        val error = errorAtLastFix + accumulatedError
        if (error > policy.lostErrorMeters) return dropAlignment(LostReason.DRIFT_TOO_LARGE, current)

        return LocalizationStatus.Localized(
            alignment = alignment,
            estimatedErrorMeters = error,
            confidence = confidenceFor(error),
            markerInView = observation?.quality == MarkerTrackingQuality.FULL,
        )
    }

    /** Keeps reporting [LocalizationStatus.Lost] until a fresh marker fix, so the UI keeps asking for a rescan. */
    private fun statusWithoutAlignment(current: LocalizationStatus): LocalizationStatus =
        current as? LocalizationStatus.Lost ?: LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER)

    private fun confidenceFor(error: Double) = when {
        error <= policy.highConfidenceMaxErrorMeters -> LocalizationConfidence.HIGH
        error <= policy.mediumConfidenceMaxErrorMeters -> LocalizationConfidence.MEDIUM
        else -> LocalizationConfidence.LOW
    }

    private fun dropAlignment(reason: LostReason, current: LocalizationStatus): LocalizationStatus {
        val hadAlignment = smoother.current != null || current is LocalizationStatus.Lost
        smoother.reset()
        lastCameraPosition = null
        interruptedSince = null
        accumulatedError = 0.0
        return if (hadAlignment) {
            (current as? LocalizationStatus.Lost) ?: LocalizationStatus.Lost(reason)
        } else {
            LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER)
        }
    }
}

/** Convenience: the alignment when it may be used for drawing, even if not reliable enough to save. */
val LocalizationStatus.usableAlignment: VenueAlignment?
    get() = (this as? LocalizationStatus.Localized)?.alignment
