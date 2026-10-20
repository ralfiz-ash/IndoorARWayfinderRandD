package com.indoorar.wayfinder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VenueDao {
    @Query("SELECT * FROM venues WHERE id = :id")
    fun observeVenue(id: String): Flow<VenueEntity?>

    @Query("SELECT * FROM venues WHERE id = :id")
    suspend fun getVenue(id: String): VenueEntity?

    @Query("SELECT * FROM floors WHERE venue_id = :venueId ORDER BY level")
    suspend fun getFloors(venueId: String): List<FloorEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVenue(venue: VenueEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFloors(floors: List<FloorEntity>)

    @Query("UPDATE venues SET frame_version = frame_version + 1 WHERE id = :id")
    suspend fun incrementFrameVersion(id: String)

    @Transaction
    suspend fun seed(venue: VenueEntity, floors: List<FloorEntity>) {
        insertVenue(venue)
        insertFloors(floors)
    }
}

@Dao
interface DestinationDao {
    @Query("SELECT * FROM destinations WHERE venue_id = :venueId ORDER BY name")
    fun observeByVenue(venueId: String): Flow<List<DestinationEntity>>

    @Query("SELECT * FROM destinations WHERE id = :id")
    suspend fun getById(id: String): DestinationEntity?

    @Query("SELECT * FROM destinations WHERE venue_id = :venueId AND name = :name LIMIT 1")
    suspend fun findByName(venueId: String, name: String): DestinationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(destination: DestinationEntity)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(destination: DestinationEntity): Int

    /**
     * Insert or update by id. Deliberately not `@Upsert`: on a unique *name* conflict Upsert falls
     * back to an UPDATE by primary key, which silently drops a new row. Here conflicts throw.
     */
    @Transaction
    suspend fun save(destination: DestinationEntity) {
        if (update(destination) == 0) insert(destination)
    }

    @Query("DELETE FROM destinations WHERE id = :id")
    suspend fun deleteById(id: String)
}
