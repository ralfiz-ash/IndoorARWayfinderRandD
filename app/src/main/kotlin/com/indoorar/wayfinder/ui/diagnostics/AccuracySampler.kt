package com.indoorar.wayfinder.ui.diagnostics

import com.indoorar.wayfinder.arcore.SelectedPoint
import com.indoorar.wayfinder.domain.accuracy.AccuracyCheckKind
import com.indoorar.wayfinder.domain.accuracy.AccuracySample
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.localization.LocalizationStatus
import com.indoorar.wayfinder.domain.venue.Venue

/** Something with a known venue position that the user can tap to check accuracy against. */
data class AccuracyTarget(val name: String, val kind: AccuracyCheckKind, val positionInVenue: Vec3)

sealed interface SampleResult {
    data class Ok(val sample: AccuracySample) : SampleResult
    /** Measurements are only meaningful in a reliably established venue frame. */
    data object NotLocalized : SampleResult
}

object AccuracySampler {
    fun build(point: SelectedPoint, target: AccuracyTarget, venue: Venue, sessionId: String, nowMillis: Long): SampleResult {
        val loc = point.localization as? LocalizationStatus.Localized
        if (loc == null || !loc.isReliable) return SampleResult.NotLocalized
        val measured = loc.alignment.toVenue(point.positionInSession)
        val heading = point.headingInSession?.let { Math.toDegrees(loc.alignment.yawToVenue(it)) }?.let { (it % 360 + 360) % 360 }
        return SampleResult.Ok(
            AccuracySample(
                timestampMillis = nowMillis,
                kind = target.kind,
                referenceName = target.name,
                expected = target.positionInVenue,
                measured = measured,
                estimatedErrorMeters = loc.estimatedErrorMeters,
                confidence = loc.confidence.name,
                distanceFromMarkerMeters = measured.horizontalLength,
                approachHeadingDegrees = heading,
                secondsSinceFix = point.diagnostics?.secondsSinceFix,
                walkedSinceFixMeters = point.diagnostics?.walkedSinceFixMeters ?: 0.0,
                frameVersion = venue.frameVersion,
                sessionId = sessionId,
            ),
        )
    }
}
