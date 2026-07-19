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
    // Rows the server permanently rejected (4xx) — excluded from sync so one
    // poison batch can't head-of-line block everything behind it
    val isQuarantined: Boolean = false,
)
