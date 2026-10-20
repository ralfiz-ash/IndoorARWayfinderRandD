package com.indoorar.wayfinder.domain.localization

import com.indoorar.wayfinder.domain.geometry.angleDifference
import com.indoorar.wayfinder.domain.geometry.wrapAngle
import com.indoorar.wayfinder.domain.venue.VenueAlignment
import kotlin.math.abs
import kotlin.math.exp

/**
 * Exponential smoothing of successive marker-derived alignments to suppress pose jitter.
 * Large discrepancies reset the filter so that genuine corrections are applied immediately.
 */
class AlignmentSmoother(
    private val timeConstantSeconds: Double,
    private val resetDistanceMeters: Double,
    private val resetAngleRadians: Double,
) {
    var current: VenueAlignment? = null
        private set
    private var lastTimestampNanos: Long = 0

    fun reset() {
        current = null
    }

    fun update(measurement: VenueAlignment, timestampNanos: Long): VenueAlignment {
        val previous = current
        val result = if (
            previous == null ||
            previous.originInSession.distanceTo(measurement.originInSession) > resetDistanceMeters ||
            abs(angleDifference(previous.yawRadians, measurement.yawRadians)) > resetAngleRadians
        ) {
            measurement
        } else {
            val dt = ((timestampNanos - lastTimestampNanos).coerceAtLeast(0)) / 1e9
            val alpha = if (timeConstantSeconds <= 0.0) 1.0 else 1.0 - exp(-dt / timeConstantSeconds)
            VenueAlignment(
                originInSession = previous.originInSession +
                    (measurement.originInSession - previous.originInSession) * alpha,
                yawRadians = wrapAngle(
                    previous.yawRadians + angleDifference(previous.yawRadians, measurement.yawRadians) * alpha,
                ),
            )
        }
        current = result
        lastTimestampNanos = timestampNanos
        return result
    }
}
