package com.indoorar.wayfinder.domain.destination

import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.selection.HitSource
import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.domain.venue.Venue
import com.indoorar.wayfinder.domain.venue.VenueId

@JvmInline value class DestinationId(val value: String)

/**
 * A saved destination. [positionInVenue] is in the venue frame (see `VenueFrame.kt`), never in raw
 * AR session coordinates, so it survives AR session restarts once the marker is rescanned.
 */
data class Destination(
    val id: DestinationId,
    val venueId: VenueId,
    val floorId: FloorId,
    val name: String,
    val positionInVenue: Vec3,
    /** Venue-frame yaw the user faced when capturing, if known. Informational only. */
    val captureHeadingRadians: Double?,
    /** Venue frame version at capture time. */
    val frameVersion: Int,
    val captureSource: HitSource,
    /** Estimated localization error at capture time. */
    val estimatedErrorMeters: Double,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    /** False when the marker was moved after capture, so the stored coordinates no longer apply. */
    fun isValidFor(venue: Venue) = venue.id == venueId && venue.frameVersion == frameVersion
}
