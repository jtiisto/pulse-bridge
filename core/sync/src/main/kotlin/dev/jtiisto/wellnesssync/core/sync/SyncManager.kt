package dev.jtiisto.wellnesssync.core.sync

import dev.jtiisto.wellnesssync.core.common.EnvironmentStore
import dev.jtiisto.wellnesssync.core.database.dao.AccelerometerSummaryDao
import dev.jtiisto.wellnesssync.core.database.dao.IntervalDao
import dev.jtiisto.wellnesssync.core.database.dao.SyncStatusDao
import dev.jtiisto.wellnesssync.core.database.entity.SyncStatusEntity
import dev.jtiisto.wellnesssync.core.network.AccelerometerApi
import dev.jtiisto.wellnesssync.core.network.IntervalApi
import dev.jtiisto.wellnesssync.core.network.dto.AccelerometerBatchDto
import dev.jtiisto.wellnesssync.core.network.dto.AccelerometerSummaryDto
import dev.jtiisto.wellnesssync.core.network.dto.IntervalBatchDto
import dev.jtiisto.wellnesssync.core.network.dto.IntervalDto

class SyncManager(
    private val intervalDao: IntervalDao,
    private val accDao: AccelerometerSummaryDao,
    private val syncStatusDao: SyncStatusDao,
    private val intervalApi: IntervalApi,
    private val accApi: AccelerometerApi,
    private val environmentStore: EnvironmentStore,
    private val config: SyncConfig = SyncConfig(),
) {
    data class SyncResult(
        val totalSent: Int,
        val totalAccepted: Int,
        val totalDuplicates: Int,
        val batches: Int,
        val accSent: Int = 0,
        val accAccepted: Int = 0,
        val accDuplicates: Int = 0,
        val accBatches: Int = 0,
    )

    suspend fun sync(): SyncResult {
        var totalSent = 0
        var totalAccepted = 0
        var totalDuplicates = 0
        var batches = 0

        val environment = environmentStore.current.headerValue

        // Check server is reachable first
        intervalApi.health(environment)

        // Sync intervals
        while (true) {
            val unsynced = intervalDao.getUnsyncedIntervals(config.batchSize)
            if (unsynced.isEmpty()) break

            val dtos = unsynced.map { entity ->
                IntervalDto(
                    deviceId = entity.deviceId,
                    timestampDevice = entity.timestampDevice,
                    timestampPhone = entity.timestampPhone,
                    heartRateBpm = entity.heartRateBpm,
                    rrIntervalMs = entity.rrIntervalMs,
                    rrSequenceIndex = entity.rrSequenceIndex,
                    isGap = entity.isGap,
                    windowLabel = entity.windowLabel,
                    sensorType = entity.sensorType,
                    sessionId = entity.sessionId,
                )
            }

            val response = intervalApi.syncBatch(IntervalBatchDto(dtos), environment)

            // Mark as synced — safe even if server already had some (idempotent)
            val syncedAt = System.currentTimeMillis()
            unsynced
                .groupBy { it.deviceId }
                .forEach { (deviceId, entities) ->
                    intervalDao.markSynced(
                        deviceId = deviceId,
                        timestamps = entities.map { it.timestampDevice },
                        syncedAt = syncedAt,
                    )
                }

            totalSent += response.totalReceived
            totalAccepted += response.accepted
            totalDuplicates += response.duplicates
            batches++
        }

        // Sync accelerometer summaries
        var accSent = 0
        var accAccepted = 0
        var accDuplicates = 0
        var accBatches = 0

        while (true) {
            val unsynced = accDao.getUnsyncedSummaries(config.batchSize)
            if (unsynced.isEmpty()) break

            val dtos = unsynced.map { entity ->
                AccelerometerSummaryDto(
                    deviceId = entity.deviceId,
                    windowStart = entity.windowStart,
                    magnitudeMean = entity.magnitudeMean,
                    magnitudeStd = entity.magnitudeStd,
                    magnitudeMax = entity.magnitudeMax,
                    sampleCount = entity.sampleCount,
                    sensorType = entity.sensorType,
                    sessionId = entity.sessionId,
                )
            }

            val response = accApi.syncBatch(AccelerometerBatchDto(dtos), environment)

            val syncedAt = System.currentTimeMillis()
            unsynced
                .groupBy { it.deviceId }
                .forEach { (deviceId, entities) ->
                    accDao.markSynced(
                        deviceId = deviceId,
                        windowStarts = entities.map { it.windowStart },
                        syncedAt = syncedAt,
                    )
                }

            accSent += response.totalReceived
            accAccepted += response.accepted
            accDuplicates += response.duplicates
            accBatches++
        }

        // Update sync status
        syncStatusDao.upsert(
            SyncStatusEntity(
                lastSyncTime = System.currentTimeMillis(),
                pendingCount = 0,
                lastError = null,
            )
        )

        return SyncResult(
            totalSent = totalSent,
            totalAccepted = totalAccepted,
            totalDuplicates = totalDuplicates,
            batches = batches,
            accSent = accSent,
            accAccepted = accAccepted,
            accDuplicates = accDuplicates,
            accBatches = accBatches,
        )
    }

    suspend fun updateSyncStatusOnError(error: String) {
        val existing = syncStatusDao.get()
        syncStatusDao.upsert(
            (existing ?: SyncStatusEntity()).copy(lastError = error)
        )
    }
}
