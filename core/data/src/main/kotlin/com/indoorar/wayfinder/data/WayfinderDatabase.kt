package com.indoorar.wayfinder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [VenueEntity::class, FloorEntity::class, DestinationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class WayfinderDatabase : RoomDatabase() {
    abstract fun venueDao(): VenueDao
    abstract fun destinationDao(): DestinationDao

    companion object {
        fun create(context: Context): WayfinderDatabase =
            Room.databaseBuilder(context.applicationContext, WayfinderDatabase::class.java, "wayfinder.db").build()

        fun inMemory(context: Context): WayfinderDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, WayfinderDatabase::class.java).build()
    }
}
