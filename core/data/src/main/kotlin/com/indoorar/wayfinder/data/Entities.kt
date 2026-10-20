package com.indoorar.wayfinder.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "venues")
data class VenueEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "frame_version") val frameVersion: Int,
    @ColumnInfo(name = "marker_image_name") val markerImageName: String,
    @ColumnInfo(name = "marker_width_m") val markerWidthMeters: Double,
    /** [com.indoorar.wayfinder.domain.venue.MarkerMounting] name. */
    @ColumnInfo(name = "marker_mounting") val markerMounting: String,
)

@Entity(
    tableName = "floors",
    foreignKeys = [ForeignKey(VenueEntity::class, ["id"], ["venue_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("venue_id")],
)
data class FloorEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "venue_id") val venueId: String,
    val name: String,
    val level: Int,
)

/**
 * Saved destination. `x/y/z` are metres in the venue frame (marker-centred, gravity-aligned; see
 * docs/COORDINATE_FRAMES.md). No AR-session anchor identifiers are stored: they are only valid
 * inside the session that created them.
 */
@Entity(
    tableName = "destinations",
    foreignKeys = [ForeignKey(VenueEntity::class, ["id"], ["venue_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["venue_id", "name"], unique = true)],
)
data class DestinationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "venue_id") val venueId: String,
    @ColumnInfo(name = "floor_id") val floorId: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(name = "venue_x") val x: Double,
    @ColumnInfo(name = "venue_y") val y: Double,
    @ColumnInfo(name = "venue_z") val z: Double,
    @ColumnInfo(name = "capture_heading_rad") val captureHeadingRadians: Double?,
    @ColumnInfo(name = "frame_version") val frameVersion: Int,
    /** [com.indoorar.wayfinder.domain.selection.HitSource] name. */
    @ColumnInfo(name = "capture_source") val captureSource: String,
    @ColumnInfo(name = "estimated_error_m") val estimatedErrorMeters: Double,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtEpochMillis: Long,
)
