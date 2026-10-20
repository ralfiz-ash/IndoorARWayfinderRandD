package com.indoorar.wayfinder.domain.accuracy

import com.indoorar.wayfinder.domain.geometry.Vec3
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

enum class AccuracyCheckKind {
    /** Tapped point vs a previously saved destination at the same physical spot: repeatability. */
    REPEATABILITY,
    /** Tapped point vs a tape-measured reference point: absolute accuracy. */
    ABSOLUTE,
}

/** A tape-measured point in the venue frame (from assets/reference_points.json). */
data class ReferencePoint(val name: String, val positionInVenue: Vec3)

/**
 * One measurement: the user taps the physical spot of a known point; we record where the app
 * thinks that spot is. Only horizontal error is used for statistics (floor points).
 */
data class AccuracySample(
    val timestampMillis: Long,
    val kind: AccuracyCheckKind,
    val referenceName: String,
    val expected: Vec3,
    val measured: Vec3,
    /** The app's own error estimate at measurement time. */
    val estimatedErrorMeters: Double,
    val confidence: String,
    /** Horizontal distance from the marker (venue origin) to the measured point. */
    val distanceFromMarkerMeters: Double,
    /** Venue-frame heading the camera faced when tapping, degrees: approach direction. */
    val approachHeadingDegrees: Double?,
    val secondsSinceFix: Double?,
    val walkedSinceFixMeters: Double,
    val frameVersion: Int,
    val sessionId: String,
) {
    val horizontalError: Double get() = measured.horizontalDistanceTo(expected)
    val verticalError: Double get() = abs(measured.y - expected.y)
    /** Whether the measured error fell within the app's own estimate. */
    val withinEstimate: Boolean get() = horizontalError <= estimatedErrorMeters
}

data class AccuracySummary(
    val count: Int,
    val meanMeters: Double,
    val medianMeters: Double,
    val p90Meters: Double,
    val maxMeters: Double,
    /** Fraction of samples whose measured error was within the estimate (error-model calibration). */
    val withinEstimateFraction: Double,
)

/** Per reference point: how far apart repeated measurements land (approach-direction / session drift). */
data class ReferenceSpread(val referenceName: String, val count: Int, val maxSpreadMeters: Double, val meanErrorMeters: Double)

object AccuracyStats {
    fun summarize(samples: List<AccuracySample>): AccuracySummary? {
        if (samples.isEmpty()) return null
        val errors = samples.map { it.horizontalError }.sorted()
        return AccuracySummary(
            count = errors.size,
            meanMeters = errors.average(),
            medianMeters = percentile(errors, 0.5),
            p90Meters = percentile(errors, 0.9),
            maxMeters = errors.last(),
            withinEstimateFraction = samples.count { it.withinEstimate }.toDouble() / samples.size,
        )
    }

    fun spreadByReference(samples: List<AccuracySample>): List<ReferenceSpread> =
        samples.groupBy { it.kind to it.referenceName }.map { (key, list) ->
            var spread = 0.0
            for (i in list.indices) for (j in i + 1 until list.size) {
                spread = maxOf(spread, list[i].measured.horizontalDistanceTo(list[j].measured))
            }
            ReferenceSpread(key.second, list.size, spread, list.map { it.horizontalError }.average())
        }.sortedBy { it.referenceName }

    /** Nearest-rank percentile of an ascending list. */
    fun percentile(sorted: List<Double>, p: Double): Double {
        require(sorted.isNotEmpty())
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}

object AccuracyCsv {
    const val HEADER = "timestamp_ms,session,kind,reference,expected_x,expected_z,measured_x,measured_z,horizontal_error_m," +
        "vertical_error_m,estimated_error_m,within_estimate,confidence,distance_from_marker_m,approach_heading_deg," +
        "seconds_since_fix,walked_since_fix_m,frame_version"

    fun row(s: AccuracySample): String = listOf(
        s.timestampMillis.toString(), s.sessionId, s.kind.name, quote(s.referenceName),
        f(s.expected.x), f(s.expected.z), f(s.measured.x), f(s.measured.z), f(s.horizontalError),
        f(s.verticalError), f(s.estimatedErrorMeters), s.withinEstimate.toString(), s.confidence,
        f(s.distanceFromMarkerMeters), s.approachHeadingDegrees?.let(::f).orEmpty(),
        s.secondsSinceFix?.let(::f).orEmpty(), f(s.walkedSinceFixMeters), s.frameVersion.toString(),
    ).joinToString(",")

    private fun f(v: Double) = String.format(Locale.US, "%.3f", v)
    private fun quote(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
}
