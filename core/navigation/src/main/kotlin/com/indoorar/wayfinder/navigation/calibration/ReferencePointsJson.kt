package com.indoorar.wayfinder.navigation.calibration

import com.indoorar.wayfinder.domain.accuracy.ReferencePoint
import com.indoorar.wayfinder.domain.geometry.Vec3
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Tape-measured reference points for absolute-accuracy checks (assets/reference_points.json).
 * Coordinates are venue-frame metres, measured physically from the marker, NOT taken from the app.
 */
@Serializable
data class ReferencePointsDocument(
    val venueId: String,
    val frameVersion: Int,
    val points: List<PointDto> = emptyList(),
) {
    @Serializable
    data class PointDto(val name: String, val x: Double, val z: Double, val y: Double = 0.0)
}

sealed interface ReferencePointsResult {
    data class Loaded(val venueId: String, val frameVersion: Int, val points: List<ReferencePoint>) : ReferencePointsResult
    data class Invalid(val message: String) : ReferencePointsResult
}

object ReferencePointsJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): ReferencePointsResult = try {
        val doc = json.decodeFromString(ReferencePointsDocument.serializer(), text)
        val duplicates = doc.points.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            ReferencePointsResult.Invalid("Duplicate reference names: ${duplicates.joinToString()}")
        } else {
            ReferencePointsResult.Loaded(doc.venueId, doc.frameVersion, doc.points.map { ReferencePoint(it.name, Vec3(it.x, it.y, it.z)) })
        }
    } catch (e: SerializationException) {
        ReferencePointsResult.Invalid(e.message ?: "Invalid JSON")
    } catch (e: IllegalArgumentException) {
        ReferencePointsResult.Invalid(e.message ?: "Invalid JSON")
    }
}
