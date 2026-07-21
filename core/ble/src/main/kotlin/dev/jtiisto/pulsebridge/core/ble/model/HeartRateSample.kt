package dev.jtiisto.pulsebridge.core.ble.model

data class HeartRateSample(
    val deviceId: String,
    val timestampDevice: Long,
    val heartRateBpm: Int,
    val rrIntervalsMs: List<Int>,
    val sensorPriority: SensorPriority,
    val sensorType: String,
    val isGapBefore: Boolean = false,
)
