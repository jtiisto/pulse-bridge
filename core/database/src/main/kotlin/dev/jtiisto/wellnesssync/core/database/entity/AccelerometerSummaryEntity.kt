package dev.jtiisto.wellnesssync.core.database.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "accelerometer_summaries",
    primaryKeys = ["deviceId", "windowStart"],
    indices = [
        Index("isSynced"),
        Index("sessionId"),
    ],
)
data class AccelerometerSummaryEntity(
    val deviceId: String,
    val windowStart: Long,
    val magnitudeMean: Double,
    val magnitudeStd: Double,
    val magnitudeMax: Double,
    val sampleCount: Int,
    val sensorType: String,
    val sessionId: String? = null,
    val isSynced: Boolean = false,
    val syncedAt: Long? = null,
)
