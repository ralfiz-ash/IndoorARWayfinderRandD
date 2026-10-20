package com.indoorar.wayfinder.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [VenueEntity::class, FloorEntity::class, DestinationEntity::class, AccuracySampleEntity::class],
    version = 2,
    exportSchema = true,
    // v2 adds accuracy_samples; existing destinations are preserved.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class WayfinderDatabase : RoomDatabase() {
    abstract fun venueDao(): VenueDao
    abstract fun destinationDao(): DestinationDao
    abstract fun accuracySampleDao(): AccuracySampleDao

    companion object {
        fun create(context: Context): WayfinderDatabase =
            Room.databaseBuilder(context.applicationContext, WayfinderDatabase::class.java, "wayfinder.db").build()

        fun inMemory(context: Context): WayfinderDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, WayfinderDatabase::class.java).build()
    }
}
