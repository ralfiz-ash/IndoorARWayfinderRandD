package com.indoorar.wayfinder.domain.localization

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.geometry.angleDifference
import com.indoorar.wayfinder.domain.geometry.wrapAngle
import com.indoorar.wayfinder.domain.venue.AlignmentResult
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import com.indoorar.wayfinder.domain.venue.VenueAlignment
import com.indoorar.wayfinder.domain.venue.VenueFrameEstimator
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns per-frame AR tracking data into a [LocalizationStatus] plus [LocalizationDiagnostics].
 *
 * Coordinate-frame validation:
 *  - The venue frame is established only from a *stable* set of fully tracked marker observations
 *    (min frame count, small spread), seen from within range and not too obliquely.
 *  - A later marker view that disagrees with the current frame must itself be stable before it
 *    replaces the frame; the size of that correction is reported (drift evidence).
 *  - While the marker is out of view, the estimated error grows with distance walked (odometry drift)
 *    and with distance from the marker (heading uncertainty × lever arm).
 *  - Short camera interruptions and AR map corrections add a penalty; long interruptions, a stopped
 *    session, a lost marker trackable or excessive error require a rescan.
 *
 * Not thread-safe: feed it from a single thread (the AR render thread).
 */
class LocalizationTracker(
    private val marker: MarkerSpec,
    private val policy: LocalizationPolicy = LocalizationPolicy(),
) {
    private class Candidate(val alignment: VenueAlignment, val timestampNanos: Long, val distance: Double)

    private val smoother = AlignmentSmoother(
        policy.smoothingTimeConstantSeconds,
        policy.smoothingResetMeters,
        policy.smoothingResetRadians,
    )
    private val candidates = ArrayDeque<Candidate>()
    private var fixPositionError = 0.0
    private var yawSigma = policy.baseYawSigmaRadians
    private var drift = 0.0
    private var penalties = 0.0
    private var walkedSinceFix = 0.0
    private var lastFixNanos: Long? = null
    private var lastCameraPosition: Vec3? = null
    private var interruptedSince: Long? = null
    private var pendingDisagreement: Double? = null
    private var fixSpread: Pair<Double, Double>? = null
    private var lastCorrection: Pair<Double, Double>? = null
    private var frameCorrections = 0
    private var mapCorrections = 0
    private var interruptions = 0

    var status: LocalizationStatus = LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER)
        private set

    var diagnostics: LocalizationDiagnostics = LocalizationDiagnostics()
        private set

    // Per-frame observation details for diagnostics.
    private var frameQuality: MarkerTrackingQuality? = null
    private var frameDistance: Double? = null
    private var frameAngle: Double? = null
    private var frameRejection: FixRejection? = null
    private var frameLever: Double? = null
    private var frameTimestamp = 0L

    /** Call when the AR session is (re)created: session coordinates from earlier sessions are meaningless. */
    fun resetForNewSession() {
        smoother.reset()
        candidates.clear()
        lastCameraPosition = null
        interruptedSince = null
        resetErrorModel()
        status = LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER)
        diagnostics = LocalizationDiagnostics()
    }

    /**
     * Call when the AR session resumes after the app was paused for [pausedForNanos]. The AR system
     * may silently relocalize after a pause, so the alignment survives only short pauses, with a
     * penalty; long pauses require a marker rescan.
     */
    fun onSessionResumed(pausedForNanos: Long) {
        candidates.clear()
        if (smoother.current == null) return
        interruptions++
        if (pausedForNanos > policy.maxInterruptionNanos) {
            status = dropAlignment(LostReason.INTERRUPTION_TOO_LONG, status)
        } else {
            penalties += policy.interruptionPenaltyMeters
            lastCameraPosition = null
        }
    }

    fun update(input: TrackingInput): LocalizationStatus {
        frameQuality = null; frameDistance = null; frameAngle = null; frameRejection = null; frameLever = null
        frameTimestamp = input.timestampNanos
        status = computeStatus(input)
        diagnostics = buildDiagnostics()
        return status
    }

    private fun computeStatus(input: TrackingInput): LocalizationStatus {
        val current = status
        when (val camera = input.camera) {
            CameraTracking.Stopped -> return dropAlignment(LostReason.TRACKING_STOPPED, current)
            is CameraTracking.Limited -> {
                candidates.clear()
                val alignment = smoother.current ?: return statusWithoutAlignment(current)
                val since = interruptedSince ?: input.timestampNanos.also { interruptedSince = it; interruptions++ }
                if (input.timestampNanos - since > policy.maxInterruptionNanos) {
                    return dropAlignment(LostReason.INTERRUPTION_TOO_LONG, current)
                }
                lastCameraPosition = null
                return LocalizationStatus.Interrupted(alignment, camera.reason, since)
            }
            CameraTracking.Tracking -> Unit
        }

        if (interruptedSince != null) {
            interruptedSince = null
            if (smoother.current != null) penalties += policy.interruptionPenaltyMeters
        }

        val observation = input.marker?.takeIf { it.imageName == marker.imageName }
        val cameraPosition = input.cameraPosition

        if (observation != null) {
            frameQuality = observation.quality
            when (val result = VenueFrameEstimator.estimate(observation.poseInSession, marker.mounting)) {
                is AlignmentResult.OrientationMismatch -> if (observation.quality == MarkerTrackingQuality.FULL) {
                    frameRejection = FixRejection.ORIENTATION_MISMATCH
                    candidates.clear()
                    if (smoother.current == null) {
                        return LocalizationStatus.NotLocalized(NotLocalizedReason.MARKER_ORIENTATION_MISMATCH)
                    }
                }
                is AlignmentResult.Valid -> if (observation.quality == MarkerTrackingQuality.FULL) {
                    handleFullObservation(observation, result.alignment, cameraPosition, input.timestampNanos)
                } else {
                    handleLastKnown(result.alignment, input.timestampNanos)
                }
            }
        } else if (smoother.current != null) {
            return dropAlignment(LostReason.MARKER_TRACKING_LOST, current)
        }

        val alignment = smoother.current ?: return statusWithoutAlignment(current)

        if (cameraPosition != null) {
            lastCameraPosition?.let { last ->
                val step = cameraPosition.distanceTo(last)
                if (step > policy.maxFrameJumpMeters) {
                    penalties += policy.interruptionPenaltyMeters
                } else {
                    drift += step * policy.driftPerMeterWalked
                    walkedSinceFix += step
                }
            }
            lastCameraPosition = cameraPosition
            frameLever = cameraPosition.horizontalDistanceTo(alignment.originInSession)
        }

        var error = totalError()
        // An unresolved disagreement with a fresh marker view is itself evidence of error.
        pendingDisagreement?.let { error = max(error, it) }
        if (error > policy.lostErrorMeters) return dropAlignment(LostReason.DRIFT_TOO_LARGE, current)

        return LocalizationStatus.Localized(
            alignment = alignment,
            estimatedErrorMeters = error,
            confidence = confidenceFor(error),
            markerInView = observation?.quality == MarkerTrackingQuality.FULL,
        )
    }

    private fun totalError(): Double =
        fixPositionError + yawSigma * (frameLever ?: 0.0) + drift + penalties

    private fun handleFullObservation(observation: MarkerObservation, measured: VenueAlignment, camera: Vec3?, t: Long) {
        val markerPose = observation.poseInSession
        if (camera == null) return
        val toCamera = camera - markerPose.translation
        val distance = toCamera.length
        val normal = markerPose.rotation.rotate(Vec3.UNIT_Y)
        val angle = acos((normal dot toCamera.normalized()).coerceIn(-1.0, 1.0))
        frameDistance = distance
        frameAngle = Math.toDegrees(angle)
        when {
            distance > policy.maxFixDistanceMeters -> { frameRejection = FixRejection.TOO_FAR; return }
            angle > policy.maxViewingAngleRadians -> { frameRejection = FixRejection.TOO_OBLIQUE; return }
        }

        val current = smoother.current
        if (current != null && agrees(current, measured)) {
            // Consistent refinement of the existing frame: smooth it in and reset drift.
            candidates.clear()
            pendingDisagreement = null
            smoother.update(measured, t)
            markFix(t, distance)
            return
        }

        // Either no frame yet, or this view disagrees: collect a stable candidate set first.
        candidates.addLast(Candidate(measured, t, distance))
        while (candidates.isNotEmpty() && t - candidates.first().timestampNanos > policy.fixWindowNanos) candidates.removeFirst()
        while (candidates.size > policy.minStableFixFrames) candidates.removeFirst()
        if (current != null) {
            pendingDisagreement = current.originInSession.distanceTo(measured.originInSession) +
                abs(angleDifference(current.yawRadians, measured.yawRadians)) * (frameLever ?: distance)
        }
        if (candidates.size < policy.minStableFixFrames) {
            frameRejection = FixRejection.STABILIZING
            return
        }
        val mean = meanAlignment(candidates.map { it.alignment })
        val spreadM = candidates.maxOf { it.alignment.originInSession.distanceTo(mean.originInSession) }
        val yawDiffs = candidates.map { angleDifference(mean.yawRadians, it.alignment.yawRadians) }
        val spreadYaw = yawDiffs.maxOf { abs(it) }
        if (spreadM > policy.maxFixSpreadMeters || spreadYaw > policy.maxFixSpreadRadians) {
            frameRejection = FixRejection.UNSTABLE
            return
        }
        if (current != null) {
            lastCorrection = current.originInSession.distanceTo(mean.originInSession) to
                Math.toDegrees(abs(angleDifference(current.yawRadians, mean.yawRadians)))
            frameCorrections++
        }
        val yawStd = sqrt(yawDiffs.sumOf { it * it } / yawDiffs.size)
        yawSigma = max(policy.baseYawSigmaRadians, yawStd)
        fixSpread = spreadM to Math.toDegrees(spreadYaw)
        smoother.reset()
        smoother.update(mean, t)
        candidates.clear()
        pendingDisagreement = null
        markFix(t, distance)
    }

    private fun handleLastKnown(measured: VenueAlignment, t: Long) {
        val current = smoother.current ?: return // Never establish a frame from a last-known pose.
        candidates.clear()
        if (!agrees(current, measured)) {
            // ARCore corrected its map and moved the marker: follow it, but trust it less.
            mapCorrections++
            penalties += policy.interruptionPenaltyMeters
        }
        smoother.update(measured, t)
    }

    private fun agrees(a: VenueAlignment, b: VenueAlignment) =
        a.originInSession.distanceTo(b.originInSession) <= policy.smoothingResetMeters &&
            abs(angleDifference(a.yawRadians, b.yawRadians)) <= policy.smoothingResetRadians

    private fun markFix(t: Long, distance: Double) {
        fixPositionError = policy.fixErrorMeters + policy.fixErrorPerMeterToMarker * distance
        drift = 0.0
        penalties = 0.0
        walkedSinceFix = 0.0
        lastFixNanos = t
    }

    private fun meanAlignment(list: List<VenueAlignment>): VenueAlignment {
        var origin = Vec3.ZERO
        var s = 0.0
        var c = 0.0
        for (a in list) {
            origin += a.originInSession
            s += sin(a.yawRadians)
            c += cos(a.yawRadians)
        }
        return VenueAlignment(origin * (1.0 / list.size), wrapAngle(atan2(s, c)))
    }

    private fun buildDiagnostics() = LocalizationDiagnostics(
        markerQuality = frameQuality,
        distanceToMarkerMeters = frameDistance,
        viewingAngleDegrees = frameAngle,
        fixRejection = frameRejection,
        stableFrames = candidates.size,
        requiredStableFrames = policy.minStableFixFrames,
        fixSpreadMeters = fixSpread?.first,
        fixSpreadDegrees = fixSpread?.second,
        yawSigmaDegrees = if (smoother.current != null) Math.toDegrees(yawSigma) else null,
        errorFix = if (smoother.current != null) fixPositionError else 0.0,
        errorHeadingLever = if (smoother.current != null) yawSigma * (frameLever ?: 0.0) else 0.0,
        errorDrift = drift,
        errorPenalties = penalties,
        leverArmMeters = frameLever,
        walkedSinceFixMeters = walkedSinceFix,
        secondsSinceFix = lastFixNanos?.takeIf { smoother.current != null }?.let { (frameTimestamp - it) / 1e9 },
        pendingDisagreementMeters = pendingDisagreement,
        lastCorrectionMeters = lastCorrection?.first,
        lastCorrectionDegrees = lastCorrection?.second,
        frameCorrections = frameCorrections,
        mapCorrections = mapCorrections,
        interruptions = interruptions,
    )

    /** Keeps reporting [LocalizationStatus.Lost] until a fresh marker fix, so the UI keeps asking for a rescan. */
    private fun statusWithoutAlignment(current: LocalizationStatus): LocalizationStatus =
        current as? LocalizationStatus.Lost ?: LocalizationStatus.NotLocalized(NotLocalizedReason.SCAN_MARKER)

    private fun confidenceFor(error: Double) = when {
        error <= policy.highConfidenceMaxErrorMeters -> LocalizationConfidence.HIGH
        error <= policy.mediumConfidenceMaxErrorMeters -> LocalizationConfidence.MEDIUM
        else -> LocalizationConfidence.LOW
    }

    private fun resetErrorModel() {
        fixPositionError = 0.0
        yawSigma = policy.baseYawSigmaRadians
        drift = 0.0
        penalties = 0.0
        walkedSinceFix = 0.0
        lastFixNanos = null
        pendingDisagreement = null
    }

    private fun dropAlignment(reason: LostReason, current: LocalizationStatus): LocalizationStatus {
        val hadAlignment = smoother.current != null || current is LocalizationStatus.Lost
        smoother.reset()
        candidates.clear()
        lastCameraPosition = null
        interruptedSince = null
        resetErrorModel()
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
