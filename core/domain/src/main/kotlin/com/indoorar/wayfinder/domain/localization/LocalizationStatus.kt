package com.indoorar.wayfinder.domain.localization

import com.indoorar.wayfinder.domain.venue.VenueAlignment

enum class LocalizationConfidence { HIGH, MEDIUM, LOW }

enum class NotLocalizedReason {
    /** The marker has not been observed in this AR session. */
    SCAN_MARKER,
    /** A marker was seen but its orientation does not match the configured mounting. */
    MARKER_ORIENTATION_MISMATCH,
}

enum class LostReason {
    TRACKING_STOPPED,
    INTERRUPTION_TOO_LONG,
    DRIFT_TOO_LARGE,
    MARKER_TRACKING_LOST,
}

sealed interface LocalizationStatus {
    /** True only when positions in the venue frame may be trusted for saving points and arrival. */
    val isReliable: Boolean get() = false

    data class NotLocalized(val reason: NotLocalizedReason) : LocalizationStatus

    data class Localized(
        val alignment: VenueAlignment,
        val estimatedErrorMeters: Double,
        val confidence: LocalizationConfidence,
        val markerInView: Boolean,
    ) : LocalizationStatus {
        override val isReliable: Boolean get() = confidence != LocalizationConfidence.LOW
    }

    /** Camera tracking is temporarily limited. The last alignment is kept only as a hint. */
    data class Interrupted(
        val lastAlignment: VenueAlignment,
        val issue: TrackingIssue,
        val sinceNanos: Long,
    ) : LocalizationStatus

    /** The venue frame can no longer be trusted. The marker must be scanned again. */
    data class Lost(val reason: LostReason) : LocalizationStatus
}

/**
 * Thresholds and error-model parameters for [LocalizationTracker].
 *
 * IMPORTANT: the error-model numbers (fix error, yaw uncertainty, drift rate) are engineering
 * assumptions, not measurements. They must be calibrated with the accuracy log against
 * tape-measured reference points (docs/ACCURACY_AUDIT.md). The estimate is used to gate
 * behaviour conservatively; it is not a proven accuracy.
 */
data class LocalizationPolicy(
    /** Position error attributed to a fresh marker fix, plus [fixErrorPerMeterToMarker] per metre of viewing distance. */
    val fixErrorMeters: Double = 0.05,
    val fixErrorPerMeterToMarker: Double = 0.02,
    /** Markers observed from further away than this do not count as a fix (a 20 cm marker is tiny beyond ~3 m). */
    val maxFixDistanceMeters: Double = 3.0,
    /** Max angle between the marker's normal and the direction to the camera for a fix (oblique views give poor normals). */
    val maxViewingAngleRadians: Double = Math.toRadians(60.0),
    /** Consecutive-ish fully tracked frames required, inside [fixWindowNanos], before a frame is (re)established. */
    val minStableFixFrames: Int = 8,
    val fixWindowNanos: Long = 1_500_000_000L,
    /** Max spread of candidate origins / headings for the fix to count as stable. */
    val maxFixSpreadMeters: Double = 0.04,
    val maxFixSpreadRadians: Double = Math.toRadians(3.0),
    /**
     * Assumed 1-sigma heading uncertainty of a stable single-marker fix. Heading error turns into
     * position error proportional to distance from the marker (lever arm). Calibrate on device.
     */
    val baseYawSigmaRadians: Double = Math.toRadians(1.0),
    /** Visual-inertial odometry drift per metre walked since the last fix (assumption; calibrate). */
    val driftPerMeterWalked: Double = 0.02,
    /** Added error after each recovered tracking interruption or AR map correction. */
    val interruptionPenaltyMeters: Double = 0.15,
    /** Interruptions longer than this invalidate the alignment. */
    val maxInterruptionNanos: Long = 5_000_000_000L,
    /** Camera displacement within one frame above this is treated as a relocalization jump. */
    val maxFrameJumpMeters: Double = 0.75,
    val highConfidenceMaxErrorMeters: Double = 0.25,
    val mediumConfidenceMaxErrorMeters: Double = 0.6,
    /** Beyond this estimated error the alignment is dropped and a rescan is required. */
    val lostErrorMeters: Double = 1.5,
    /** Fix measurements closer than this to the current alignment refine it smoothly. */
    val smoothingResetMeters: Double = 0.3,
    val smoothingResetRadians: Double = Math.toRadians(15.0),
    /** Time constant of the exponential alignment smoothing. */
    val smoothingTimeConstantSeconds: Double = 0.25,
)

/** Why the current marker observation was not accepted as a fix. */
enum class FixRejection { TOO_FAR, TOO_OBLIQUE, STABILIZING, UNSTABLE, ORIENTATION_MISMATCH }

/**
 * Per-frame localization diagnostics. Distinguish:
 *  - tracking stability (camera state, interruptions, map corrections, fix spread), and
 *  - absolute-accuracy *estimate* (error components), which is a model, not a measurement.
 */
data class LocalizationDiagnostics(
    val markerQuality: MarkerTrackingQuality? = null,
    val distanceToMarkerMeters: Double? = null,
    val viewingAngleDegrees: Double? = null,
    val fixRejection: FixRejection? = null,
    val stableFrames: Int = 0,
    val requiredStableFrames: Int = 0,
    /** Spread of the candidate fix (origin cm / heading degrees) at establishment. */
    val fixSpreadMeters: Double? = null,
    val fixSpreadDegrees: Double? = null,
    val yawSigmaDegrees: Double? = null,
    /** Error estimate components (metres). */
    val errorFix: Double = 0.0,
    val errorHeadingLever: Double = 0.0,
    val errorDrift: Double = 0.0,
    val errorPenalties: Double = 0.0,
    /** Horizontal distance from the camera to the marker (heading lever arm). */
    val leverArmMeters: Double? = null,
    val walkedSinceFixMeters: Double = 0.0,
    val secondsSinceFix: Double? = null,
    /** A fresh marker view disagrees with the current frame by this much (not yet accepted). */
    val pendingDisagreementMeters: Double? = null,
    /** Size of the last accepted frame correction: direct evidence of accumulated drift. */
    val lastCorrectionMeters: Double? = null,
    val lastCorrectionDegrees: Double? = null,
    val frameCorrections: Int = 0,
    val mapCorrections: Int = 0,
    val interruptions: Int = 0,
)
