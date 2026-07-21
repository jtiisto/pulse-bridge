package dev.jtiisto.pulsebridge.core.ble.polar

import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarPpiData
import dev.jtiisto.pulsebridge.core.database.entity.AccelerometerSummaryEntity
import dev.jtiisto.pulsebridge.core.database.entity.IntervalEntity
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.sqrt

class PolarRecordingParser(
    private val gapThresholdMs: Long = DEFAULT_GAP_THRESHOLD_MS,
) {

    data class PpiParseResult(
        val intervals: List<IntervalEntity>,
        val gapCount: Int,
    )

    fun parsePpi(
        data: PolarPpiData,
        startTime: LocalDateTime,
        deviceId: String,
        sessionId: String,
        phoneTimestamp: Long,
    ): PpiParseResult {
        if (data.samples.isEmpty()) {
            return PpiParseResult(intervals = emptyList(), gapCount = 0)
        }

        val baseEpochMs = startTime.toInstant(ZoneOffset.UTC).toEpochMilli()
        val intervals = mutableListOf<IntervalEntity>()
        var gapCount = 0
        var cumulativeMs = 0L
        var lastTimestampMs = Long.MIN_VALUE

        data.samples.forEachIndexed { index, sample ->
            // ppi == 0 samples don't advance cumulativeMs, so the next sample
            // would repeat this timestamp and collide on the (deviceId,
            // timestampDevice) PK — INSERT OR IGNORE then silently drops it.
            // Keep timestamps strictly increasing.
            var timestampDevice = baseEpochMs + cumulativeMs
            if (timestampDevice <= lastTimestampMs) {
                timestampDevice = lastTimestampMs + 1
            }
            lastTimestampMs = timestampDevice

            val isGap = if (index > 0) {
                sample.ppi.toLong() > gapThresholdMs
            } else {
                false
            }
            if (isGap) gapCount++

            intervals.add(
                IntervalEntity(
                    deviceId = deviceId,
                    timestampDevice = timestampDevice,
                    timestampPhone = phoneTimestamp,
                    heartRateBpm = sample.hr,
                    rrIntervalMs = sample.ppi,
                    rrSequenceIndex = index,
                    isGap = isGap,
                    sensorType = SENSOR_TYPE,
                    sessionId = sessionId,
                )
            )

            cumulativeMs += sample.ppi
        }

        return PpiParseResult(intervals = intervals, gapCount = gapCount)
    }

    fun parseAccSummaries(
        data: PolarAccelerometerData,
        startTime: LocalDateTime,
        deviceId: String,
        sessionId: String,
    ): List<AccelerometerSummaryEntity> {
        if (data.samples.isEmpty()) return emptyList()

        val baseEpochMs = startTime.toInstant(ZoneOffset.UTC).toEpochMilli()

        // Group samples into 1-minute windows based on their offset from startTime
        val windows = mutableMapOf<Long, MutableList<Double>>()

        data.samples.forEach { sample ->
            // Timestamps are in nanoseconds since epoch 2000-01-01
            // Convert to offset from base using the sample's relative position
            val sampleOffsetNs = sample.timeStamp - data.samples.first().timeStamp
            val sampleOffsetMs = sampleOffsetNs / 1_000_000
            val absoluteMs = baseEpochMs + sampleOffsetMs

            // Round down to 1-minute window start
            val windowStart = absoluteMs - (absoluteMs % WINDOW_SIZE_MS)

            // Compute magnitude in millig, then convert to g
            val magnitude = sqrt(
                sample.x.toDouble() * sample.x +
                    sample.y.toDouble() * sample.y +
                    sample.z.toDouble() * sample.z
            ) / 1000.0

            windows.getOrPut(windowStart) { mutableListOf() }.add(magnitude)
        }

        return windows.map { (windowStart, magnitudes) ->
            val mean = magnitudes.average()
            val std = if (magnitudes.size > 1) {
                val variance = magnitudes.sumOf { (it - mean) * (it - mean) } / (magnitudes.size - 1)
                sqrt(variance)
            } else {
                0.0
            }
            val max = magnitudes.max()

            AccelerometerSummaryEntity(
                deviceId = deviceId,
                windowStart = windowStart,
                magnitudeMean = mean,
                magnitudeStd = std,
                magnitudeMax = max,
                sampleCount = magnitudes.size,
                sensorType = SENSOR_TYPE,
                sessionId = sessionId,
            )
        }.sortedBy { it.windowStart }
    }

    companion object {
        const val SENSOR_TYPE = "polar_pvs"
        const val DEFAULT_GAP_THRESHOLD_MS = 3000L
        const val WINDOW_SIZE_MS = 60_000L
    }
}
