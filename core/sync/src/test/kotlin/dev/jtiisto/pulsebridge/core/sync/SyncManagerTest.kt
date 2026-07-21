package dev.jtiisto.pulsebridge.core.sync

import dev.jtiisto.pulsebridge.core.common.DiagnosticLog
import dev.jtiisto.pulsebridge.core.common.EnvironmentStore
import dev.jtiisto.pulsebridge.core.common.SyncEnvironment
import dev.jtiisto.pulsebridge.core.database.dao.AccelerometerSummaryDao
import dev.jtiisto.pulsebridge.core.database.dao.IntervalDao
import dev.jtiisto.pulsebridge.core.database.dao.SyncStatusDao
import dev.jtiisto.pulsebridge.core.database.entity.AccelerometerSummaryEntity
import dev.jtiisto.pulsebridge.core.database.entity.IntervalEntity
import dev.jtiisto.pulsebridge.core.database.entity.SyncStatusEntity
import dev.jtiisto.pulsebridge.core.network.AccelerometerApi
import dev.jtiisto.pulsebridge.core.network.IntervalApi
import dev.jtiisto.pulsebridge.core.network.dto.HealthResponseDto
import dev.jtiisto.pulsebridge.core.network.dto.IntervalBatchDto
import dev.jtiisto.pulsebridge.core.network.dto.SyncResponseDto
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SyncManagerTest {

    private lateinit var intervalDao: IntervalDao
    private lateinit var accDao: AccelerometerSummaryDao
    private lateinit var syncStatusDao: SyncStatusDao
    private lateinit var intervalApi: IntervalApi
    private lateinit var accApi: AccelerometerApi
    private lateinit var environmentStore: EnvironmentStore
    private lateinit var syncManager: SyncManager

    private fun makeEntity(deviceId: String = "AA:BB", ts: Long, hr: Int = 120) = IntervalEntity(
        deviceId = deviceId,
        timestampDevice = ts,
        timestampPhone = ts + 5,
        heartRateBpm = hr,
        rrIntervalMs = 800,
        rrSequenceIndex = 0,
        isGap = false,
        sensorType = "garmin_hrm",
        sessionId = "session-1",
    )

    @BeforeEach
    fun setUp() {
        intervalDao = mockk(relaxed = true)
        accDao = mockk(relaxed = true)
        syncStatusDao = mockk(relaxed = true)
        intervalApi = mockk(relaxed = true)
        accApi = mockk(relaxed = true)
        environmentStore = mockk {
            every { current } returns SyncEnvironment.TEST
        }

        coEvery { intervalApi.health(any()) } returns HealthResponseDto(
            status = "ok", environment = "test", intervalsCount = 0,
        )
        coEvery { accDao.getUnsyncedSummaries(any()) } returns emptyList()

        syncManager = SyncManager(
            intervalDao = intervalDao,
            accDao = accDao,
            syncStatusDao = syncStatusDao,
            intervalApi = intervalApi,
            accApi = accApi,
            environmentStore = environmentStore,
            diagnosticLog = DiagnosticLog(),
            config = SyncConfig(batchSize = 100),
        )
    }

    private fun makeAccEntity(deviceId: String = "1A2B3C4D", windowStart: Long) =
        AccelerometerSummaryEntity(
            deviceId = deviceId,
            windowStart = windowStart,
            magnitudeMean = 1.0,
            magnitudeStd = 0.1,
            magnitudeMax = 1.4,
            sampleCount = 120,
            sensorType = "polar_pvs",
            sessionId = "rec-1",
        )

    private fun clientError(status: HttpStatusCode): ClientRequestException {
        val response = mockk<HttpResponse>(relaxed = true) {
            every { this@mockk.status } returns status
        }
        return ClientRequestException(response, "rejected")
    }

    @Test
    fun `sync with no unsynced data returns zero counts`() = runTest {
        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns emptyList()

        val result = syncManager.sync()

        assertEquals(0, result.totalSent)
        assertEquals(0, result.totalAccepted)
        assertEquals(0, result.batches)
    }

    @Test
    fun `sync sends intervals and marks as synced`() = runTest {
        val entities = listOf(makeEntity(ts = 1000), makeEntity(ts = 2000))

        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns entities andThen emptyList()
        coEvery { intervalApi.syncBatch(any(), any()) } returns SyncResponseDto(
            accepted = 2, duplicates = 0, totalReceived = 2,
        )

        val result = syncManager.sync()

        assertEquals(2, result.totalSent)
        assertEquals(2, result.totalAccepted)
        assertEquals(0, result.totalDuplicates)
        assertEquals(1, result.batches)

        coVerify { intervalDao.markSynced("AA:BB", listOf(1000L, 2000L), any()) }
    }

    @Test
    fun `sync uses environment from store`() = runTest {
        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns emptyList()

        syncManager.sync()

        coVerify { intervalApi.health("test") }
    }

    @Test
    fun `sync sends correct DTOs`() = runTest {
        val entity = makeEntity(ts = 5000, hr = 145)
        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns listOf(entity) andThen emptyList()

        val batchSlot = slot<IntervalBatchDto>()
        coEvery { intervalApi.syncBatch(capture(batchSlot), any()) } returns SyncResponseDto(
            accepted = 1, duplicates = 0, totalReceived = 1,
        )

        syncManager.sync()

        val dto = batchSlot.captured.intervals.first()
        assertEquals("AA:BB", dto.deviceId)
        assertEquals(5000L, dto.timestampDevice)
        assertEquals(145, dto.heartRateBpm)
        assertEquals(800, dto.rrIntervalMs)
        assertEquals("garmin_hrm", dto.sensorType)
    }

    @Test
    fun `sync handles duplicates correctly`() = runTest {
        val entities = listOf(makeEntity(ts = 1000))
        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns entities andThen emptyList()
        coEvery { intervalApi.syncBatch(any(), any()) } returns SyncResponseDto(
            accepted = 0, duplicates = 1, totalReceived = 1,
        )

        val result = syncManager.sync()

        assertEquals(1, result.totalSent)
        assertEquals(0, result.totalAccepted)
        assertEquals(1, result.totalDuplicates)
        // Still marks as synced locally — idempotent
        coVerify { intervalDao.markSynced(any(), any(), any()) }
    }

    @Test
    fun `sync updates sync status on success`() = runTest {
        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns emptyList()

        syncManager.sync()

        coVerify {
            syncStatusDao.upsert(match {
                it.lastError == null && it.pendingCount == 0
            })
        }
    }

    @Test
    fun `updateSyncStatusOnError stores error message`() = runTest {
        coEvery { syncStatusDao.get() } returns SyncStatusEntity(lastSyncTime = 1000L)

        syncManager.updateSyncStatusOnError("Connection refused")

        coVerify {
            syncStatusDao.upsert(match {
                it.lastError == "Connection refused"
            })
        }
    }

    @Test
    fun `4xx batch is quarantined and later batches still sync`() = runTest {
        val poison = listOf(makeEntity(ts = 1000))
        val good = listOf(makeEntity(ts = 2000))
        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns poison andThen good andThen emptyList()
        coEvery { intervalDao.quarantine(any(), any()) } returns 1
        coEvery { intervalApi.syncBatch(any(), any()) } throws
            clientError(HttpStatusCode.UnprocessableEntity) andThen
            SyncResponseDto(accepted = 1, duplicates = 0, totalReceived = 1)

        val result = syncManager.sync()

        assertEquals(1, result.quarantined)
        assertEquals(1, result.totalSent)
        assertEquals(1, result.totalAccepted)
        coVerify { intervalDao.quarantine("AA:BB", listOf(1000L)) }
        coVerify { intervalDao.markSynced("AA:BB", listOf(2000L), any()) }
    }

    @Test
    fun `exhausted quarantine budget aborts instead of quarantining the backlog`() = runTest {
        val guarded = SyncManager(
            intervalDao = intervalDao,
            accDao = accDao,
            syncStatusDao = syncStatusDao,
            intervalApi = intervalApi,
            accApi = accApi,
            environmentStore = environmentStore,
            diagnosticLog = DiagnosticLog(),
            config = SyncConfig(batchSize = 100, maxQuarantinePerRun = 1),
        )
        val batch1 = listOf(makeEntity(ts = 1000))
        val batch2 = listOf(makeEntity(ts = 2000))
        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns batch1 andThen batch2 andThen emptyList()
        coEvery { intervalDao.quarantine(any(), any()) } returns 1
        coEvery { intervalApi.syncBatch(any(), any()) } throws
            clientError(HttpStatusCode.UnprocessableEntity) andThenThrows
            clientError(HttpStatusCode.UnprocessableEntity)

        var thrown: ClientRequestException? = null
        try {
            guarded.sync()
        } catch (e: ClientRequestException) {
            thrown = e
        }

        assertEquals(true, thrown != null)
        // Budget of 1 consumed by the first row; the second rejection aborts
        coVerify(exactly = 1) { intervalDao.quarantine(any(), any()) }
        coVerify { intervalDao.quarantine("AA:BB", listOf(1000L)) }
    }

    @Test
    fun `non-validation 4xx aborts immediately without touching data`() = runTest {
        val entities = listOf(makeEntity(ts = 1000), makeEntity(ts = 2000))
        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns entities andThen emptyList()
        // 404: endpoint problem, not a row problem — must not bisect or quarantine
        coEvery { intervalApi.syncBatch(any(), any()) } throws clientError(HttpStatusCode.NotFound)

        var thrown: ClientRequestException? = null
        try {
            syncManager.sync()
        } catch (e: ClientRequestException) {
            thrown = e
        }

        assertEquals(true, thrown != null)
        coVerify(exactly = 1) { intervalApi.syncBatch(any(), any()) }
        coVerify(exactly = 0) { intervalDao.quarantine(any(), any()) }
    }

    @Test
    fun `systemic 422 with no successes trips the circuit breaker`() = runTest {
        val entities = (1..8).map { makeEntity(ts = it.toLong()) }
        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns entities andThen emptyList()
        coEvery { intervalDao.quarantine(any(), any()) } returns 1
        // EVERY request 422s — contract drift, nothing is row-specific
        coEvery { intervalApi.syncBatch(any(), any()) } throws
            clientError(HttpStatusCode.UnprocessableEntity)

        var thrown: ClientRequestException? = null
        try {
            syncManager.sync()
        } catch (e: ClientRequestException) {
            thrown = e
        }

        assertEquals(true, thrown != null)
        // Only maxQuarantineWithoutSuccess (3) rows sacrificed before aborting
        coVerify(exactly = 3) { intervalDao.quarantine(any(), any()) }
    }

    @Test
    fun `quarantine budget is shared across interval and accelerometer streams`() = runTest {
        val shared = SyncManager(
            intervalDao = intervalDao,
            accDao = accDao,
            syncStatusDao = syncStatusDao,
            intervalApi = intervalApi,
            accApi = accApi,
            environmentStore = environmentStore,
            diagnosticLog = DiagnosticLog(),
            config = SyncConfig(batchSize = 100, maxQuarantinePerRun = 1),
        )
        val good = listOf(makeEntity(ts = 100))
        val poison = listOf(makeEntity(ts = 200))
        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns good andThen poison andThen emptyList()
        coEvery { intervalDao.quarantine(any(), any()) } returns 1
        coEvery { intervalApi.syncBatch(any(), any()) } returns
            SyncResponseDto(accepted = 1, duplicates = 0, totalReceived = 1) andThenThrows
            clientError(HttpStatusCode.UnprocessableEntity)
        coEvery { accDao.getUnsyncedSummaries(any()) } returns listOf(makeAccEntity(windowStart = 60_000L))
        coEvery { accApi.syncBatch(any(), any()) } throws clientError(HttpStatusCode.UnprocessableEntity)

        var thrown: ClientRequestException? = null
        try {
            shared.sync()
        } catch (e: ClientRequestException) {
            thrown = e
        }

        assertEquals(true, thrown != null)
        // The single budget slot went to the interval row; the accelerometer
        // rejection found the shared budget empty and aborted
        coVerify(exactly = 1) { intervalDao.quarantine("AA:BB", listOf(200L)) }
        coVerify(exactly = 0) { accDao.quarantine(any(), any()) }
    }

    @Test
    fun `bisect quarantines only the poison row and syncs its batch-mates`() = runTest {
        val entities = listOf(makeEntity(ts = 665), makeEntity(ts = 666), makeEntity(ts = 667))
        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns entities andThen emptyList()
        coEvery { intervalDao.quarantine(any(), any()) } returns 1
        // Server rejects any request containing the poison row (ts=666)
        coEvery { intervalApi.syncBatch(any(), any()) } coAnswers {
            val batch = firstArg<IntervalBatchDto>()
            if (batch.intervals.any { it.timestampDevice == 666L }) {
                throw clientError(HttpStatusCode.UnprocessableEntity)
            }
            SyncResponseDto(
                accepted = batch.intervals.size,
                duplicates = 0,
                totalReceived = batch.intervals.size,
            )
        }

        val result = syncManager.sync()

        assertEquals(1, result.quarantined)
        assertEquals(2, result.totalAccepted)
        coVerify(exactly = 1) { intervalDao.quarantine("AA:BB", listOf(666L)) }
        coVerify { intervalDao.markSynced("AA:BB", listOf(665L), any()) }
        coVerify { intervalDao.markSynced("AA:BB", listOf(667L), any()) }
    }

    @Test
    fun `accelerometer summaries are synced and marked`() = runTest {
        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns emptyList()
        val summaries = listOf(makeAccEntity(windowStart = 60_000L))
        coEvery { accDao.getUnsyncedSummaries(any()) } returns summaries andThen emptyList()
        coEvery { accApi.syncBatch(any(), any()) } returns SyncResponseDto(
            accepted = 1, duplicates = 0, totalReceived = 1,
        )

        val result = syncManager.sync()

        assertEquals(1, result.accSent)
        assertEquals(1, result.accAccepted)
        assertEquals(1, result.accBatches)
        coVerify { accDao.markSynced("1A2B3C4D", listOf(60_000L), any()) }
    }

    @Test
    fun `422 accelerometer row is quarantined without blocking`() = runTest {
        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns emptyList()
        val poison = listOf(makeAccEntity(windowStart = 60_000L))
        coEvery { accDao.getUnsyncedSummaries(any()) } returns poison andThen emptyList()
        coEvery { accDao.quarantine(any(), any()) } returns 1
        coEvery { accApi.syncBatch(any(), any()) } throws
            clientError(HttpStatusCode.UnprocessableEntity)

        val result = syncManager.sync()

        assertEquals(1, result.accQuarantined)
        assertEquals(0, result.accSent)
        coVerify { accDao.quarantine("1A2B3C4D", listOf(60_000L)) }
    }

    @Test
    fun `sync processes multiple batches`() = runTest {
        val batch1 = listOf(makeEntity(ts = 1000))
        val batch2 = listOf(makeEntity(ts = 2000))

        coEvery { intervalDao.getUnsyncedIntervals(any()) } returns batch1 andThen batch2 andThen emptyList()
        coEvery { intervalApi.syncBatch(any(), any()) } returns SyncResponseDto(
            accepted = 1, duplicates = 0, totalReceived = 1,
        )

        val result = syncManager.sync()

        assertEquals(2, result.totalSent)
        assertEquals(2, result.batches)
    }
}
