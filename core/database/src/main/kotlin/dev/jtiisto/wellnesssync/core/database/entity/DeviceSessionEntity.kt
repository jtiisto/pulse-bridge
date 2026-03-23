package dev.jtiisto.wellnesssync.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_sessions")
data class DeviceSessionEntity(
    @PrimaryKey val sessionId: String,
    val deviceId: String,
    val sensorType: String,
    val startTime: Long,
    val endTime: Long? = null,
    val totalIntervals: Int = 0,
    val averageHr: Int? = null,
)
