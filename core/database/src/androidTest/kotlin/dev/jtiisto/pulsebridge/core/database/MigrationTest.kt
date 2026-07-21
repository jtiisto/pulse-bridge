package dev.jtiisto.pulsebridge.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.jtiisto.pulsebridge.core.database.entity.AccelerometerSummaryEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Builds a REAL version-1 database file with hand-written v1 DDL, then opens
 * it through Room with the production migrations. Room validates the migrated
 * schema against the current entities on open, so a migration that produces a
 * wrong schema fails this test instead of a real user's upgrade.
 */
class MigrationTest {

    private companion object {
        const val DB_NAME = "migration-test.db"
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun deleteExisting() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun cleanup() {
        context.deleteDatabase(DB_NAME)
    }

    private fun createVersion1Database() {
        val helper = object : SQLiteOpenHelper(context, DB_NAME, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS intervals (
                        deviceId TEXT NOT NULL,
                        timestampDevice INTEGER NOT NULL,
                        timestampPhone INTEGER NOT NULL,
                        heartRateBpm INTEGER NOT NULL,
                        rrIntervalMs INTEGER NOT NULL,
                        rrSequenceIndex INTEGER NOT NULL,
                        isGap INTEGER NOT NULL,
                        windowLabel TEXT,
                        sensorType TEXT NOT NULL,
                        sessionId TEXT,
                        isSynced INTEGER NOT NULL,
                        syncedAt INTEGER,
                        PRIMARY KEY(deviceId, timestampDevice)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_intervals_timestampDevice ON intervals(timestampDevice)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_intervals_isSynced ON intervals(isSynced)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_intervals_windowLabel ON intervals(windowLabel)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_intervals_sessionId ON intervals(sessionId)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS device_sessions (
                        sessionId TEXT NOT NULL,
                        deviceId TEXT NOT NULL,
                        sensorType TEXT NOT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER,
                        totalIntervals INTEGER NOT NULL,
                        averageHr INTEGER,
                        PRIMARY KEY(sessionId)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_status (
                        id INTEGER NOT NULL,
                        lastSyncTime INTEGER,
                        pendingCount INTEGER NOT NULL,
                        lastError TEXT,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent()
                )

                // Real v1 data that must survive both migrations
                db.execSQL(
                    """
                    INSERT INTO intervals (
                        deviceId, timestampDevice, timestampPhone, heartRateBpm,
                        rrIntervalMs, rrSequenceIndex, isGap, sensorType, isSynced
                    ) VALUES ('AA:BB', 1000, 1005, 75, 800, 0, 0, 'garmin_hrm', 0)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO device_sessions (
                        sessionId, deviceId, sensorType, startTime, totalIntervals
                    ) VALUES ('s1', 'AA:BB', 'garmin_hrm', 900, 0)
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.close()
        helper.close()
    }

    @Test
    fun migrateFrom1To3_preservesDataAndPassesRoomValidation() = runTest {
        createVersion1Database()

        val db = Room.databaseBuilder(context, PulseBridgeDatabase::class.java, DB_NAME)
            .addMigrations(
                PulseBridgeDatabase.MIGRATION_1_2,
                PulseBridgeDatabase.MIGRATION_2_3,
            )
            .build()

        try {
            // v1 interval survived and is neither synced nor quarantined
            val unsynced = db.intervalDao().getUnsyncedIntervals(10)
            assertEquals(1, unsynced.size)
            assertEquals(800, unsynced[0].rrIntervalMs)
            assertEquals(false, unsynced[0].isQuarantined)

            // v3 quarantine column is functional
            db.intervalDao().quarantine("AA:BB", listOf(1000L))
            assertTrue(db.intervalDao().getUnsyncedIntervals(10).isEmpty())

            // v2 accelerometer table exists with the v3 column
            db.accelerometerSummaryDao().insertAll(
                listOf(
                    AccelerometerSummaryEntity(
                        deviceId = "1A2B3C4D",
                        windowStart = 60_000L,
                        magnitudeMean = 1.0,
                        magnitudeStd = 0.1,
                        magnitudeMax = 1.5,
                        sampleCount = 100,
                        sensorType = "polar_pvs",
                    )
                )
            )
            assertEquals(1, db.accelerometerSummaryDao().getUnsyncedSummaries(10).size)

            // v1 session survived
            assertEquals("s1", db.deviceSessionDao().getById("s1")?.sessionId)
        } finally {
            db.close()
        }
    }
}
