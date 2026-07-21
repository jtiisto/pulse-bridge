package dev.jtiisto.pulsebridge.core.ble.buffer

import dev.jtiisto.pulsebridge.core.ble.model.HeartRateSample
import dev.jtiisto.pulsebridge.core.common.DiagnosticLog
import dev.jtiisto.pulsebridge.core.database.dao.IntervalDao
import dev.jtiisto.pulsebridge.core.database.entity.IntervalEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class IntervalBuffer(
    private val intervalDao: IntervalDao,
    private val diagnosticLog: DiagnosticLog,
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

    /** @return number of interval rows generated from this sample */
    suspend fun add(sample: HeartRateSample, sessionId: String?): Int {
        mutex.withLock {
            val entities = mapToEntities(sample, sessionId)
            buffer.addAll(entities)
            if (buffer.size >= config.maxBufferSize) {
                flushLocked()
            }
            return entities.size
        }
    }

    /** @return true when the buffer is fully persisted (or was already empty) */
    suspend fun flush(): Boolean {
        mutex.withLock {
            return flushLocked()
        }
    }

    private suspend fun flushLocked(): Boolean {
        if (buffer.isEmpty()) return true
        val batch = buffer.toList()
        try {
            intervalDao.insertAll(batch)
        } catch (e: CancellationException) {
            throw e // buffer kept; the singleton flushes on the next session
        } catch (e: Exception) {
            // Clearing before the insert is confirmed would lose the batch on
            // disk pressure or transient DB errors — keep it and retry later
            diagnosticLog.log("buffer", "flush FAILED, keeping ${batch.size} rows: ${e.message}")
            return false
        }
        buffer.clear()
        return true
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

        // Anchor the sequence so the LAST beat lands at receipt time — the
        // beats happened BEFORE the notification arrived, never after. A
        // future-drifting clock would collide with the next real notification.
        val rrs = sample.rrIntervalsMs
        val candidates = LongArray(rrs.size) { index ->
            val remainingMs = rrs.subList(index + 1, rrs.size).sumOf { it.toLong() }
            sample.timestampDevice - remainingMs
        }
        // Zero-RR sentinels collide at the anchor; spread them BACKWARD so
        // uniqueness never pushes a beat past receipt time
        for (i in candidates.size - 2 downTo 0) {
            if (candidates[i] >= candidates[i + 1]) {
                candidates[i] = candidates[i + 1] - 1
            }
        }
        return rrs.mapIndexed { index, rrMs ->
            IntervalEntity(
                deviceId = sample.deviceId,
                timestampDevice = nextTimestamp(sample.deviceId, candidates[index]),
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
