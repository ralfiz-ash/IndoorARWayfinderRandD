package com.indoorar.wayfinder.domain.venue

@JvmInline value class VenueId(val value: String)
@JvmInline value class FloorId(val value: String)

/** How the physical reference marker is mounted. Determines how the venue frame's heading is derived. */
enum class MarkerMounting {
    /** Marker on a vertical wall; venue +Z points out of the wall, into the room. */
    WALL,
    /** Marker lying flat on the floor; venue +Z points towards the top edge of the printed image. */
    FLOOR,
}

/**
 * Physical reference marker that defines the venue coordinate frame.
 *
 * @param imageName identifier of the reference image registered with the AR image tracker
 *   (ARCore Augmented Images database name / ARKit `ARReferenceImage.name`).
 * @param physicalWidthMeters printed width of the image. Must match the print for correct scale.
 */
data class MarkerSpec(
    val imageName: String,
    val physicalWidthMeters: Double,
    val mounting: MarkerMounting,
) {
    init {
        require(physicalWidthMeters > 0.0) { "Marker width must be positive" }
    }
}

data class Floor(val id: FloorId, val venueId: VenueId, val name: String, val level: Int)

/**
 * A venue and its coordinate frame definition.
 *
 * [frameVersion] identifies the physical placement of the marker. If the marker is moved or
 * re-printed at a different size, the version must be incremented; destinations captured with an
 * older version are no longer trusted.
 */
data class Venue(
    val id: VenueId,
    val name: String,
    val frameVersion: Int,
    val marker: MarkerSpec,
    val floors: List<Floor>,
) {
    val defaultFloor: Floor get() = floors.minBy { it.level }
}
