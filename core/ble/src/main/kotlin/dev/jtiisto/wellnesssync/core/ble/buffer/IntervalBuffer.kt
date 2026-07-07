package dev.jtiisto.wellnesssync.core.ble.buffer

import dev.jtiisto.wellnesssync.core.ble.model.HeartRateSample
import dev.jtiisto.wellnesssync.core.database.dao.IntervalDao
import dev.jtiisto.wellnesssync.core.database.entity.IntervalEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class IntervalBuffer(
    private val intervalDao: IntervalDao,
    private val config: BufferConfig = BufferConfig(),
    private val scope: CoroutineScope,
) {
    private val buffer = mutableListOf<IntervalEntity>()
    private val mutex = Mutex()
    private var flushJob: Job? = null
    private val lastTimestampByDevice = mutableMapOf<String, Long>()

    val size: Int get() = buffer.size

    fun start() {
        flushJob?.cancel()
        flushJob = scope.launch {
            while (true) {
                delay(config.flushInterval)
                flush()
            }
        }
    }

    fun stop() {
        flushJob?.cancel()
        flushJob = null
    }

    suspend fun add(sample: HeartRateSample, sessionId: String?) {
        mutex.withLock {
            buffer.addAll(mapToEntities(sample, sessionId))
            if (buffer.size >= config.maxBufferSize) {
                flushLocked()
            }
        }
    }

    suspend fun flush() {
        mutex.withLock {
            flushLocked()
        }
    }

    private suspend fun flushLocked() {
        if (buffer.isEmpty()) return
        val batch = buffer.toList()
        buffer.clear()
        intervalDao.insertAll(batch)
    }

    private fun mapToEntities(
        sample: HeartRateSample,
        sessionId: String?,
    ): List<IntervalEntity> {
        val phoneTimestamp = System.currentTimeMillis()

        if (sample.rrIntervalsMs.isEmpty()) {
            return listOf(
                IntervalEntity(
                    deviceId = sample.deviceId,
                    timestampDevice = nextTimestamp(sample.deviceId, sample.timestampDevice),
                    timestampPhone = phoneTimestamp,
                    heartRateBpm = sample.heartRateBpm,
                    rrIntervalMs = 0,
                    rrSequenceIndex = 0,
                    isGap = sample.isGapBefore,
                    sensorType = sample.sensorType,
                    sessionId = sessionId,
                )
            )
        }

        return sample.rrIntervalsMs.mapIndexed { index, rrMs ->
            // Offset each RR interval timestamp by cumulative preceding RR values
            // to produce unique device timestamps per RR within the same notification
            val cumulativeOffsetMs = sample.rrIntervalsMs.take(index).sum().toLong()
            IntervalEntity(
                deviceId = sample.deviceId,
                timestampDevice = nextTimestamp(sample.deviceId, sample.timestampDevice + cumulativeOffsetMs),
                timestampPhone = phoneTimestamp,
                heartRateBpm = sample.heartRateBpm,
                rrIntervalMs = rrMs,
                rrSequenceIndex = index,
                isGap = sample.isGapBefore && index == 0,
                sensorType = sample.sensorType,
                sessionId = sessionId,
            )
        }
    }

    // (deviceId, timestampDevice) is the PK, and both Room and the server insert
    // with OR IGNORE — a colliding timestamp silently drops a real interval.
    // Receipt-clock bases and zero-length RRs can both produce collisions, so
    // keep timestamps strictly increasing per device. Must be called under mutex.
    private fun nextTimestamp(deviceId: String, candidate: Long): Long {
        val last = lastTimestampByDevice[deviceId]
        val timestamp = if (last != null && candidate <= last) last + 1 else candidate
        lastTimestampByDevice[deviceId] = timestamp
        return timestamp
    }
}
