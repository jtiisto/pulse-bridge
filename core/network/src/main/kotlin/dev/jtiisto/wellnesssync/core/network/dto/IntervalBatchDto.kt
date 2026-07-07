package dev.jtiisto.wellnesssync.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IntervalDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("timestamp_device") val timestampDevice: Long,
    @SerialName("timestamp_phone") val timestampPhone: Long,
    @SerialName("heart_rate_bpm") val heartRateBpm: Int,
    @SerialName("rr_interval_ms") val rrIntervalMs: Int,
    @SerialName("rr_sequence_index") val rrSequenceIndex: Int,
    @SerialName("is_gap") val isGap: Boolean = false,
    @SerialName("window_label") val windowLabel: String? = null,
    @SerialName("sensor_type") val sensorType: String,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class IntervalBatchDto(
    val intervals: List<IntervalDto>,
)

@Serializable
data class SyncResponseDto(
    val accepted: Int,
    val duplicates: Int,
    @SerialName("total_received") val totalReceived: Int,
)

@Serializable
data class HealthResponseDto(
    val status: String,
    val environment: String,
    @SerialName("intervals_count") val intervalsCount: Int,
    @SerialName("accelerometer_summaries_count") val accelerometerSummariesCount: Int = 0,
)
