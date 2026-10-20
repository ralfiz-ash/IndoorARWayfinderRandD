package com.indoorar.wayfinder.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.indoorar.wayfinder.domain.accuracy.AccuracyCheckKind
import com.indoorar.wayfinder.domain.accuracy.AccuracyLogRepository
import com.indoorar.wayfinder.domain.accuracy.AccuracySample
import com.indoorar.wayfinder.domain.geometry.Vec3
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Accuracy measurements (venue frame). Local-only diagnostic data. */
@Entity(tableName = "accuracy_samples")
data class AccuracySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp_ms") val timestampMillis: Long,
    val kind: String,
    @ColumnInfo(name = "reference_name") val referenceName: String,
    @ColumnInfo(name = "expected_x") val expectedX: Double,
    @ColumnInfo(name = "expected_y") val expectedY: Double,
    @ColumnInfo(name = "expected_z") val expectedZ: Double,
    @ColumnInfo(name = "measured_x") val measuredX: Double,
    @ColumnInfo(name = "measured_y") val measuredY: Double,
    @ColumnInfo(name = "measured_z") val measuredZ: Double,
    @ColumnInfo(name = "estimated_error_m") val estimatedErrorMeters: Double,
    val confidence: String,
    @ColumnInfo(name = "distance_from_marker_m") val distanceFromMarkerMeters: Double,
    @ColumnInfo(name = "approach_heading_deg") val approachHeadingDegrees: Double?,
    @ColumnInfo(name = "seconds_since_fix") val secondsSinceFix: Double?,
    @ColumnInfo(name = "walked_since_fix_m") val walkedSinceFixMeters: Double,
    @ColumnInfo(name = "frame_version") val frameVersion: Int,
    @ColumnInfo(name = "session_id") val sessionId: String,
)

@Dao
interface AccuracySampleDao {
    @Query("SELECT * FROM accuracy_samples ORDER BY timestamp_ms")
    fun observeAll(): Flow<List<AccuracySampleEntity>>

    @Insert
    suspend fun insert(sample: AccuracySampleEntity)

    @Query("DELETE FROM accuracy_samples")
    suspend fun clear()
}

internal fun AccuracySample.toEntity() = AccuracySampleEntity(
    timestampMillis = timestampMillis, kind = kind.name, referenceName = referenceName,
    expectedX = expected.x, expectedY = expected.y, expectedZ = expected.z,
    measuredX = measured.x, measuredY = measured.y, measuredZ = measured.z,
    estimatedErrorMeters = estimatedErrorMeters, confidence = confidence,
    distanceFromMarkerMeters = distanceFromMarkerMeters, approachHeadingDegrees = approachHeadingDegrees,
    secondsSinceFix = secondsSinceFix, walkedSinceFixMeters = walkedSinceFixMeters,
    frameVersion = frameVersion, sessionId = sessionId,
)

internal fun AccuracySampleEntity.toDomain() = AccuracySample(
    timestampMillis = timestampMillis,
    kind = runCatching { AccuracyCheckKind.valueOf(kind) }.getOrDefault(AccuracyCheckKind.ABSOLUTE),
    referenceName = referenceName,
    expected = Vec3(expectedX, expectedY, expectedZ),
    measured = Vec3(measuredX, measuredY, measuredZ),
    estimatedErrorMeters = estimatedErrorMeters, confidence = confidence,
    distanceFromMarkerMeters = distanceFromMarkerMeters, approachHeadingDegrees = approachHeadingDegrees,
    secondsSinceFix = secondsSinceFix, walkedSinceFixMeters = walkedSinceFixMeters,
    frameVersion = frameVersion, sessionId = sessionId,
)

class RoomAccuracyLogRepository(private val dao: AccuracySampleDao) : AccuracyLogRepository {
    override fun observeSamples(): Flow<List<AccuracySample>> = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override suspend fun add(sample: AccuracySample) = dao.insert(sample.toEntity())
    override suspend fun clear() = dao.clear()
}
