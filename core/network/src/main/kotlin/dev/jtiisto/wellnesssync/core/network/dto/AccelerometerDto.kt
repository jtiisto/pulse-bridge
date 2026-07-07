package dev.jtiisto.wellnesssync.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccelerometerSummaryDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("window_start") val windowStart: Long,
    @SerialName("magnitude_mean") val magnitudeMean: Double,
    @SerialName("magnitude_std") val magnitudeStd: Double,
    @SerialName("magnitude_max") val magnitudeMax: Double,
    @SerialName("sample_count") val sampleCount: Int,
    @SerialName("sensor_type") val sensorType: String,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class AccelerometerBatchDto(
    val summaries: List<AccelerometerSummaryDto>,
)
