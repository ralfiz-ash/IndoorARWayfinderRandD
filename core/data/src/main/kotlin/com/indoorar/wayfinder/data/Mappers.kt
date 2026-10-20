package com.indoorar.wayfinder.data

import com.indoorar.wayfinder.domain.destination.Destination
import com.indoorar.wayfinder.domain.destination.DestinationId
import com.indoorar.wayfinder.domain.geometry.Vec3
import com.indoorar.wayfinder.domain.selection.HitSource
import com.indoorar.wayfinder.domain.venue.Floor
import com.indoorar.wayfinder.domain.venue.FloorId
import com.indoorar.wayfinder.domain.venue.MarkerMounting
import com.indoorar.wayfinder.domain.venue.MarkerSpec
import com.indoorar.wayfinder.domain.venue.Venue
import com.indoorar.wayfinder.domain.venue.VenueId

internal fun DestinationEntity.toDomain() = Destination(
    id = DestinationId(id),
    venueId = VenueId(venueId),
    floorId = FloorId(floorId),
    name = name,
    positionInVenue = Vec3(x, y, z),
    captureHeadingRadians = captureHeadingRadians,
    frameVersion = frameVersion,
    captureSource = runCatching { HitSource.valueOf(captureSource) }.getOrDefault(HitSource.OTHER),
    estimatedErrorMeters = estimatedErrorMeters,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun Destination.toEntity() = DestinationEntity(
    id = id.value,
    venueId = venueId.value,
    floorId = floorId.value,
    name = name,
    x = positionInVenue.x,
    y = positionInVenue.y,
    z = positionInVenue.z,
    captureHeadingRadians = captureHeadingRadians,
    frameVersion = frameVersion,
    captureSource = captureSource.name,
    estimatedErrorMeters = estimatedErrorMeters,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun VenueEntity.toDomain(floors: List<FloorEntity>) = Venue(
    id = VenueId(id),
    name = name,
    frameVersion = frameVersion,
    marker = MarkerSpec(markerImageName, markerWidthMeters, MarkerMounting.valueOf(markerMounting)),
    floors = floors.map { Floor(FloorId(it.id), VenueId(it.venueId), it.name, it.level) },
)

internal fun Venue.toEntity() = VenueEntity(
    id = id.value,
    name = name,
    frameVersion = frameVersion,
    markerImageName = marker.imageName,
    markerWidthMeters = marker.physicalWidthMeters,
    markerMounting = marker.mounting.name,
)

internal fun Floor.toEntity() = FloorEntity(id.value, venueId.value, name, level)
