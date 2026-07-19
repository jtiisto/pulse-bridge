package dev.jtiisto.wellnesssync.core.sync

import dev.jtiisto.wellnesssync.core.common.DiagnosticLog
import dev.jtiisto.wellnesssync.core.common.EnvironmentStore
import dev.jtiisto.wellnesssync.core.database.dao.AccelerometerSummaryDao
import dev.jtiisto.wellnesssync.core.database.dao.IntervalDao
import dev.jtiisto.wellnesssync.core.database.dao.SyncStatusDao
import dev.jtiisto.wellnesssync.core.database.entity.AccelerometerSummaryEntity
import dev.jtiisto.wellnesssync.core.database.entity.IntervalEntity
import dev.jtiisto.wellnesssync.core.database.entity.SyncStatusEntity
import dev.jtiisto.wellnesssync.core.network.AccelerometerApi
import dev.jtiisto.wellnesssync.core.network.IntervalApi
import dev.jtiisto.wellnesssync.core.network.dto.AccelerometerBatchDto
import dev.jtiisto.wellnesssync.core.network.dto.AccelerometerSummaryDto
import dev.jtiisto.wellnesssync.core.network.dto.IntervalBatchDto
import dev.jtiisto.wellnesssync.core.network.dto.IntervalDto
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode

class SyncManager(
    private val intervalDao: IntervalDao,
    private val accDao: AccelerometerSummaryDao,
    private val syncStatusDao: SyncStatusDao,
    private val intervalApi: IntervalApi,
    private val accApi: AccelerometerApi,
    private val environmentStore: EnvironmentStore,
    private val diagnosticLog: DiagnosticLog,
    private val config: SyncConfig = SyncConfig(),
) {
    data class SyncResult(
        val totalSent: Int,
        val totalAccepted: Int,
        val totalDuplicates: Int,
        val batches: Int,
        val quarantined: Int = 0,
        val accSent: Int = 0,
        val accAccepted: Int = 0,
        val accDuplicates: Int = 0,
        val accBatches: Int = 0,
        val accQuarantined: Int = 0,
    )

    private data class BatchOutcome(
        val sent: Int,
        val accepted: Int,
        val duplicates: Int,
        val quarantined: Int,
    ) {
        operator fun plus(other: BatchOutcome) = BatchOutcome(
            sent + other.sent,
            accepted + other.accepted,
            duplicates + other.duplicates,
            quarantined + other.quarantined,
        )
    }

    private class RunState(var remaining: Int) {
        var successSeen = false
        var quarantinedWithoutSuccess = 0
    }

    // FastAPI reports row-validation failures as 422 and nothing else; every
    // other 4xx (404 endpoint, 409 conflict, 429 rate limit, our own 400 for
    // a bad environment header) is systemic — bisecting or quarantining on
    // those would mutilate valid data and hammer the server
    private fun isRowValidationError(e: ClientRequestException): Boolean =
        e.response.status == HttpStatusCode.UnprocessableEntity

    suspend fun sync(): SyncResult {
        var intervals = BatchOutcome(0, 0, 0, 0)
        var batches = 0
        var acc = BatchOutcome(0, 0, 0, 0)
        var accBatches = 0

        // Shared across both data streams: exhausting it means the failures
        // aren't row-specific and the run must abort instead of quarantining on
        val runState = RunState(config.maxQuarantinePerRun)

        val environment = environmentStore.current.headerValue

        // Check server is reachable first
        intervalApi.health(environment)

        while (true) {
            val unsynced = intervalDao.getUnsyncedIntervals(config.batchSize)
            if (unsynced.isEmpty()) break
            intervals += syncIntervalsIsolating(unsynced, environment, runState)
            batches++
        }

        while (true) {
            val unsynced = accDao.getUnsyncedSummaries(config.batchSize)
            if (unsynced.isEmpty()) break
            acc += syncSummariesIsolating(unsynced, environment, runState)
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
            totalSent = intervals.sent,
            totalAccepted = intervals.accepted,
            totalDuplicates = intervals.duplicates,
            batches = batches,
            quarantined = intervals.quarantined,
            accSent = acc.sent,
            accAccepted = acc.accepted,
            accDuplicates = acc.duplicates,
            accBatches = accBatches,
            accQuarantined = acc.quarantined,
        )
    }

    /**
     * Sends a batch; on a row-validation 422 bisects recursively so only the
     * rows the server actually rejects are quarantined — valid rows in the
     * same batch still sync. Any other 4xx is systemic and rethrows without
     * touching data. Each quarantined row consumes the run budget; exhaustion
     * rethrows, because that many rejections means contract drift.
     */
    private suspend fun syncIntervalsIsolating(
        entities: List<IntervalEntity>,
        environment: String,
        runState: RunState,
    ): BatchOutcome {
        val dtos = entities.map { entity ->
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

        val response = try {
            intervalApi.syncBatch(IntervalBatchDto(dtos), environment)
        } catch (e: ClientRequestException) {
            if (!isRowValidationError(e)) throw e
            if (entities.size == 1) {
                val row = entities.single()
                consumeBudgetOrAbort(runState, e)
                diagnosticLog.log(
                    "sync",
                    "quarantining interval ${row.deviceId}@${row.timestampDevice}: HTTP ${e.response.status.value}",
                )
                intervalDao.quarantine(row.deviceId, listOf(row.timestampDevice))
                return BatchOutcome(0, 0, 0, 1)
            }
            val mid = entities.size / 2
            return syncIntervalsIsolating(entities.subList(0, mid), environment, runState) +
                syncIntervalsIsolating(entities.subList(mid, entities.size), environment, runState)
        }

        runState.successSeen = true

        // Mark as synced — safe even if server already had some (idempotent)
        val syncedAt = System.currentTimeMillis()
        entities.groupBy { it.deviceId }.forEach { (deviceId, grouped) ->
            intervalDao.markSynced(
                deviceId = deviceId,
                timestamps = grouped.map { it.timestampDevice },
                syncedAt = syncedAt,
            )
        }
        return BatchOutcome(response.totalReceived, response.accepted, response.duplicates, 0)
    }

    private suspend fun syncSummariesIsolating(
        entities: List<AccelerometerSummaryEntity>,
        environment: String,
        runState: RunState,
    ): BatchOutcome {
        val dtos = entities.map { entity ->
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

        val response = try {
            accApi.syncBatch(AccelerometerBatchDto(dtos), environment)
        } catch (e: ClientRequestException) {
            if (!isRowValidationError(e)) throw e
            if (entities.size == 1) {
                val row = entities.single()
                consumeBudgetOrAbort(runState, e)
                diagnosticLog.log(
                    "sync",
                    "quarantining accelerometer summary ${row.deviceId}@${row.windowStart}: HTTP ${e.response.status.value}",
                )
                accDao.quarantine(row.deviceId, listOf(row.windowStart))
                return BatchOutcome(0, 0, 0, 1)
            }
            val mid = entities.size / 2
            return syncSummariesIsolating(entities.subList(0, mid), environment, runState) +
                syncSummariesIsolating(entities.subList(mid, entities.size), environment, runState)
        }

        runState.successSeen = true

        val syncedAt = System.currentTimeMillis()
        entities.groupBy { it.deviceId }.forEach { (deviceId, grouped) ->
            accDao.markSynced(
                deviceId = deviceId,
                windowStarts = grouped.map { it.windowStart },
                syncedAt = syncedAt,
            )
        }
        return BatchOutcome(response.totalReceived, response.accepted, response.duplicates, 0)
    }

    private fun consumeBudgetOrAbort(runState: RunState, cause: ClientRequestException) {
        if (runState.remaining <= 0) {
            diagnosticLog.log(
                "sync",
                "quarantine budget exhausted (HTTP ${cause.response.status.value}) — aborting run, likely contract drift",
            )
            throw cause
        }
        if (!runState.successSeen &&
            runState.quarantinedWithoutSuccess >= config.maxQuarantineWithoutSuccess
        ) {
            // Nothing has succeeded this run — these 422s look systemic, and
            // quarantining more valid data on that evidence is not justified
            diagnosticLog.log(
                "sync",
                "no successful request this run after ${runState.quarantinedWithoutSuccess} quarantines — aborting",
            )
            throw cause
        }
        runState.remaining--
        if (!runState.successSeen) {
            runState.quarantinedWithoutSuccess++
        }
    }

    suspend fun updateSyncStatusOnError(error: String) {
        val existing = syncStatusDao.get()
        syncStatusDao.upsert(
            (existing ?: SyncStatusEntity()).copy(lastError = error)
        )
    }
}
