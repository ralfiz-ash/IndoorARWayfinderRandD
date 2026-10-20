package com.indoorar.wayfinder.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indoorar.wayfinder.domain.accuracy.AccuracyCheckKind
import com.indoorar.wayfinder.domain.accuracy.AccuracySample
import com.indoorar.wayfinder.domain.geometry.Vec3
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationAndAccuracyLogTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), WayfinderDatabase::class.java)

    @Test
    fun migrationFrom1To2KeepsDestinations() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO venues VALUES ('v','Venue',1,'venue_marker',0.2,'WALL')")
            db.execSQL(
                "INSERT INTO destinations VALUES ('d1','v','ground','Exit',1.5,-1.3,4.0,NULL,1,'PLANE',0.1,1,1)",
            )
        }
        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            db.query("SELECT name, venue_x, venue_z FROM destinations").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Exit", c.getString(0))
                assertEquals(1.5, c.getDouble(1), 0.0)
                assertEquals(4.0, c.getDouble(2), 0.0)
            }
            db.query("SELECT COUNT(*) FROM accuracy_samples").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }

    @Test
    fun accuracyLogRoundTrip() = runTest {
        val db = WayfinderDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val repo = RoomAccuracyLogRepository(db.accuracySampleDao())
            val sample = AccuracySample(
                10, AccuracyCheckKind.REPEATABILITY, "Exit", Vec3(1.0, -1.3, 4.0), Vec3(1.2, -1.28, 4.1),
                0.3, "HIGH", 4.2, 135.0, 12.5, 6.0, 1, "session-a",
            )
            repo.add(sample)
            assertEquals(listOf(sample), repo.observeSamples().first())
            repo.clear()
            assertTrue(repo.observeSamples().first().isEmpty())
        } finally {
            db.close()
        }
    }

    private companion object { const val DB = "migration-test" }
}
