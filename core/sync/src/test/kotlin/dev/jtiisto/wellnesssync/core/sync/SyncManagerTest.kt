package dev.jtiisto.wellnesssync.core.sync

import dev.jtiisto.wellnesssync.core.common.EnvironmentStore
import dev.jtiisto.wellnesssync.core.common.SyncEnvironment
import dev.jtiisto.wellnesssync.core.database.dao.IntervalDao
import dev.jtiisto.wellnesssync.core.database.dao.SyncStatusDao
import dev.jtiisto.wellnesssync.core.database.entity.IntervalEntity
import dev.jtiisto.wellnesssync.core.database.entity.SyncStatusEntity
import dev.jtiisto.wellnesssync.core.network.IntervalApi
import dev.jtiisto.wellnesssync.core.network.dto.HealthResponseDto
import dev.jtiisto.wellnesssync.core.network.dto.IntervalBatchDto
import dev.jtiisto.wellnesssync.core.network.dto.SyncResponseDto
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
    private lateinit var syncStatusDao: SyncStatusDao
    private lateinit var intervalApi: IntervalApi
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
        syncStatusDao = mockk(relaxed = true)
        intervalApi = mockk(relaxed = true)
        environmentStore = mockk {
            every { current } returns SyncEnvironment.TEST
        }

        coEvery { intervalApi.health(any()) } returns HealthResponseDto(
            status = "ok", environment = "test", intervalsCount = 0,
        )

        syncManager = SyncManager(
            intervalDao = intervalDao,
            syncStatusDao = syncStatusDao,
            intervalApi = intervalApi,
            environmentStore = environmentStore,
            config = SyncConfig(batchSize = 100),
        )
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
