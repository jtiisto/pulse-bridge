package dev.jtiisto.wellnesssync.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.jtiisto.wellnesssync.core.database.dao.DeviceSessionDao
import dev.jtiisto.wellnesssync.core.database.dao.IntervalDao
import dev.jtiisto.wellnesssync.core.database.dao.SyncStatusDao
import dev.jtiisto.wellnesssync.core.database.entity.DeviceSessionEntity
import dev.jtiisto.wellnesssync.core.database.entity.IntervalEntity
import dev.jtiisto.wellnesssync.core.database.entity.SyncStatusEntity

@Database(
    entities = [
        IntervalEntity::class,
        DeviceSessionEntity::class,
        SyncStatusEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class WellnessSyncDatabase : RoomDatabase() {
    abstract fun intervalDao(): IntervalDao
    abstract fun deviceSessionDao(): DeviceSessionDao
    abstract fun syncStatusDao(): SyncStatusDao
}
