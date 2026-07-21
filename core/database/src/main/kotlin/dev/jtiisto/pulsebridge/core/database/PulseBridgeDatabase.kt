package dev.jtiisto.pulsebridge.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.jtiisto.pulsebridge.core.database.dao.AccelerometerSummaryDao
import dev.jtiisto.pulsebridge.core.database.dao.DeviceSessionDao
import dev.jtiisto.pulsebridge.core.database.dao.IntervalDao
import dev.jtiisto.pulsebridge.core.database.dao.SyncStatusDao
import dev.jtiisto.pulsebridge.core.database.entity.AccelerometerSummaryEntity
import dev.jtiisto.pulsebridge.core.database.entity.DeviceSessionEntity
import dev.jtiisto.pulsebridge.core.database.entity.IntervalEntity
import dev.jtiisto.pulsebridge.core.database.entity.SyncStatusEntity

@Database(
    entities = [
        IntervalEntity::class,
        DeviceSessionEntity::class,
        SyncStatusEntity::class,
        AccelerometerSummaryEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class PulseBridgeDatabase : RoomDatabase() {
    abstract fun intervalDao(): IntervalDao
    abstract fun deviceSessionDao(): DeviceSessionDao
    abstract fun syncStatusDao(): SyncStatusDao
    abstract fun accelerometerSummaryDao(): AccelerometerSummaryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS accelerometer_summaries (
                        deviceId TEXT NOT NULL,
                        windowStart INTEGER NOT NULL,
                        magnitudeMean REAL NOT NULL,
                        magnitudeStd REAL NOT NULL,
                        magnitudeMax REAL NOT NULL,
                        sampleCount INTEGER NOT NULL,
                        sensorType TEXT NOT NULL,
                        sessionId TEXT,
                        isSynced INTEGER NOT NULL DEFAULT 0,
                        syncedAt INTEGER,
                        PRIMARY KEY (deviceId, windowStart)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_accelerometer_summaries_isSynced ON accelerometer_summaries(isSynced)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_accelerometer_summaries_sessionId ON accelerometer_summaries(sessionId)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE intervals ADD COLUMN isQuarantined INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE accelerometer_summaries ADD COLUMN isQuarantined INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
