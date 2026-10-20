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

/** Thresholds for [LocalizationTracker]. Defaults are conservative starting points; tune on device. */
data class LocalizationPolicy(
    /** Error attributed to a fresh marker fix, plus [fixErrorPerMeterToMarker] per metre of viewing distance. */
    val fixErrorMeters: Double = 0.05,
    val fixErrorPerMeterToMarker: Double = 0.02,
    /** Markers observed from further away than this do not count as a fresh fix. */
    val maxFixDistanceMeters: Double = 4.0,
    /** Visual-inertial odometry drift per metre walked since the last fix. */
    val driftPerMeterWalked: Double = 0.02,
    /** Added error after each recovered tracking interruption. */
    val interruptionPenaltyMeters: Double = 0.15,
    /** Interruptions longer than this invalidate the alignment. */
    val maxInterruptionNanos: Long = 5_000_000_000L,
    /** Camera displacement within one frame above this is treated as a relocalization jump. */
    val maxFrameJumpMeters: Double = 0.75,
    val highConfidenceMaxErrorMeters: Double = 0.25,
    val mediumConfidenceMaxErrorMeters: Double = 0.6,
    /** Beyond this estimated error the alignment is dropped and a rescan is required. */
    val lostErrorMeters: Double = 1.5,
    /** Pose change beyond which a new marker fix replaces the smoothed alignment instead of blending. */
    val smoothingResetMeters: Double = 0.3,
    val smoothingResetRadians: Double = Math.toRadians(15.0),
    /** Time constant of the exponential alignment smoothing. */
    val smoothingTimeConstantSeconds: Double = 0.25,
)
