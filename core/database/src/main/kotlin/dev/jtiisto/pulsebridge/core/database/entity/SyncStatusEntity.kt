package dev.jtiisto.pulsebridge.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_status")
data class SyncStatusEntity(
    @PrimaryKey val id: Int = 1,
    val lastSyncTime: Long? = null,
    val pendingCount: Int = 0,
    val lastError: String? = null,
)
