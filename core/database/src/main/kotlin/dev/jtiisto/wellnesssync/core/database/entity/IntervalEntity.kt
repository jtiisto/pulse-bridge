package dev.jtiisto.wellnesssync.core.database.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "intervals",
    primaryKeys = ["deviceId", "timestampDevice"],
    indices = [
        Index("timestampDevice"),
        Index("isSynced"),
        Index("windowLabel"),
        Index("sessionId"),
    ],
)
data class IntervalEntity(
    val deviceId: String,
    val timestampDevice: Long,
    val timestampPhone: Long,
    val heartRateBpm: Int,
    val rrIntervalMs: Int,
    val rrSequenceIndex: Int,
    val isGap: Boolean = false,
    val windowLabel: String? = null,
    val sensorType: String,
    val sessionId: String? = null,
    val isSynced: Boolean = false,
    val syncedAt: Long? = null,
)
