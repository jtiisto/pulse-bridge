package dev.jtiisto.wellnesssync.core.sync

import dev.jtiisto.wellnesssync.core.common.EnvironmentStore
import dev.jtiisto.wellnesssync.core.database.dao.IntervalDao
import dev.jtiisto.wellnesssync.core.database.dao.SyncStatusDao
import dev.jtiisto.wellnesssync.core.database.entity.SyncStatusEntity
import dev.jtiisto.wellnesssync.core.network.IntervalApi
import dev.jtiisto.wellnesssync.core.network.dto.IntervalBatchDto
import dev.jtiisto.wellnesssync.core.network.dto.IntervalDto

class SyncManager(
    private val intervalDao: IntervalDao,
    private val syncStatusDao: SyncStatusDao,
    private val intervalApi: IntervalApi,
    private val environmentStore: EnvironmentStore,
    private val config: SyncConfig = SyncConfig(),
) {
    data class SyncResult(
        val totalSent: Int,
        val totalAccepted: Int,
        val totalDuplicates: Int,
        val batches: Int,
    )

    suspend fun sync(): SyncResult {
        var totalSent = 0
        var totalAccepted = 0
        var totalDuplicates = 0
        var batches = 0

        val environment = environmentStore.current.headerValue

        // Check server is reachable first
        intervalApi.health(environment)

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

        // Update sync status
        val pendingCount = 0 // We just synced everything
        syncStatusDao.upsert(
            SyncStatusEntity(
                lastSyncTime = System.currentTimeMillis(),
                pendingCount = pendingCount,
                lastError = null,
            )
        )

        return SyncResult(totalSent, totalAccepted, totalDuplicates, batches)
    }

    suspend fun updateSyncStatusOnError(error: String) {
        val existing = syncStatusDao.get()
        syncStatusDao.upsert(
            (existing ?: SyncStatusEntity()).copy(lastError = error)
        )
    }
}
