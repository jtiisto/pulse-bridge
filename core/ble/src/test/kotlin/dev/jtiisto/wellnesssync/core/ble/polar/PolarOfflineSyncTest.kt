package dev.jtiisto.wellnesssync.core.ble.polar

import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.PolarPpiData
import dev.jtiisto.wellnesssync.core.common.DiagnosticLog
import dev.jtiisto.wellnesssync.core.database.dao.AccelerometerSummaryDao
import dev.jtiisto.wellnesssync.core.database.dao.IntervalDao
import dev.jtiisto.wellnesssync.core.database.entity.AccelerometerSummaryEntity
import dev.jtiisto.wellnesssync.core.database.entity.IntervalEntity
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class PolarOfflineSyncTest {

    private val polarApi: PolarBleApi = mockk(relaxed = true)
    private val intervalDao: IntervalDao = mockk()
    private val accDao: AccelerometerSummaryDao = mockk()
    private val parser: PolarRecordingParser = PolarRecordingParser()

    private val deviceId = "1A2B3C4D"
    private val startTime = LocalDateTime.of(2026, 3, 23, 10, 0, 0)

    private lateinit var sync: PolarOfflineSync

    @BeforeEach
    fun setup() {
        coEvery { intervalDao.insertAll(any()) } just Runs
        coEvery { accDao.insertAll(any()) } just Runs

        sync = PolarOfflineSync(
            polarApi = polarApi,
            intervalDao = intervalDao,
            accDao = accDao,
            parser = parser,
            diagnosticLog = DiagnosticLog(),
            featureReadyTimeoutMs = 5000L,
        )
    }

    private fun stubConnectAndFeatureReady() {
        val callbackSlot = slot<PolarBleApiCallback>()
        every { polarApi.setApiCallback(capture(callbackSlot)) } answers {
            // Simulate feature ready on connect
            every { polarApi.connectToDevice(deviceId) } answers {
                callbackSlot.captured.bleSdkFeatureReady(
                    deviceId,
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING,
                )
            }
        }
    }

    private fun createPpiEntry(date: LocalDateTime = startTime) = PolarOfflineRecordingEntry(
        path = "/U/0/PPI/20260323/",
        size = 1024,
        date = date,
        type = PolarBleApi.PolarDeviceDataType.PPI,
    )

    private fun createAccEntry(date: LocalDateTime = startTime) = PolarOfflineRecordingEntry(
        path = "/U/0/ACC/20260323/",
        size = 4096,
        date = date,
        type = PolarBleApi.PolarDeviceDataType.ACC,
    )

    private fun createPpiRecordingData() = PolarOfflineRecordingData.PpiOfflineRecording(
        data = PolarPpiData(
            samples = listOf(
                PolarPpiData.PolarPpiSample(
                    ppi = 800, hr = 75, errorEstimate = 5,
                    blockerBit = false, skinContactStatus = true,
                    skinContactSupported = true, timeStamp = 0UL,
                ),
                PolarPpiData.PolarPpiSample(
                    ppi = 850, hr = 71, errorEstimate = 5,
                    blockerBit = false, skinContactStatus = true,
                    skinContactSupported = true, timeStamp = 800UL,
                ),
            )
        ),
        startTime = startTime,
    )

    private fun createAccRecordingData() = PolarOfflineRecordingData.AccOfflineRecording(
        data = PolarAccelerometerData(
            samples = listOf(
                PolarAccelerometerData.PolarAccelerometerDataSample(
                    timeStamp = 0L, x = 0, y = 0, z = 1000,
                ),
                // 61 seconds later → different 1-minute window
                PolarAccelerometerData.PolarAccelerometerDataSample(
                    timeStamp = 61_000_000_000L, x = 0, y = 0, z = 1000,
                ),
            )
        ),
        startTime = startTime,
        settings = mockk(),
    )

    @Test
    fun `syncDevice - no recordings returns empty result`() = runTest {
        stubConnectAndFeatureReady()
        every { polarApi.listOfflineRecordings(deviceId) } returns Flowable.empty()

        val result = sync.syncDevice(deviceId)

        assertEquals(0, result.intervalsFetched)
        assertEquals(0, result.summariesFetched)
        assertEquals(0, result.recordingsProcessed)
        assertFalse(result.hasErrors)
    }

    @Test
    fun `syncDevice - fetches and stores PPI recording`() = runTest {
        stubConnectAndFeatureReady()

        val ppiEntry = createPpiEntry()
        every { polarApi.listOfflineRecordings(deviceId) } returns Flowable.just(ppiEntry)
        every { polarApi.getOfflineRecord(deviceId, ppiEntry, null) } returns
            Single.just(createPpiRecordingData())
        every { polarApi.removeOfflineRecord(deviceId, ppiEntry) } returns Completable.complete()

        val result = sync.syncDevice(deviceId)

        assertEquals(2, result.intervalsFetched)
        assertEquals(1, result.recordingsProcessed)
        assertEquals(1, result.recordingsDeleted)
        assertFalse(result.hasErrors)
        coVerify { intervalDao.insertAll(match { it.size == 2 }) }
    }

    @Test
    fun `syncDevice - fetches and stores ACC recording`() = runTest {
        stubConnectAndFeatureReady()

        val accEntry = createAccEntry()
        every { polarApi.listOfflineRecordings(deviceId) } returns Flowable.just(accEntry)
        every { polarApi.getOfflineRecord(deviceId, accEntry, null) } returns
            Single.just(createAccRecordingData())
        every { polarApi.removeOfflineRecord(deviceId, accEntry) } returns Completable.complete()

        val result = sync.syncDevice(deviceId)

        assertEquals(2, result.summariesFetched)
        assertEquals(1, result.recordingsProcessed)
        coVerify { accDao.insertAll(match { it.size == 2 }) }
    }

    @Test
    fun `syncDevice - processes both PPI and ACC recordings`() = runTest {
        stubConnectAndFeatureReady()

        val ppiEntry = createPpiEntry()
        val accEntry = createAccEntry()
        every { polarApi.listOfflineRecordings(deviceId) } returns
            Flowable.fromIterable(listOf(ppiEntry, accEntry))
        every { polarApi.getOfflineRecord(deviceId, ppiEntry, null) } returns
            Single.just(createPpiRecordingData())
        every { polarApi.getOfflineRecord(deviceId, accEntry, null) } returns
            Single.just(createAccRecordingData())
        every { polarApi.removeOfflineRecord(deviceId, any()) } returns Completable.complete()

        val result = sync.syncDevice(deviceId)

        assertEquals(2, result.intervalsFetched)
        assertEquals(2, result.summariesFetched)
        assertEquals(2, result.recordingsProcessed)
        assertEquals(2, result.recordingsDeleted)
    }

    @Test
    fun `syncDevice - does not delete recording on fetch failure`() = runTest {
        stubConnectAndFeatureReady()

        val ppiEntry = createPpiEntry()
        every { polarApi.listOfflineRecordings(deviceId) } returns Flowable.just(ppiEntry)
        every { polarApi.getOfflineRecord(deviceId, ppiEntry, null) } returns
            Single.error(RuntimeException("BLE disconnect"))

        val result = sync.syncDevice(deviceId)

        assertEquals(0, result.intervalsFetched)
        assertEquals(0, result.recordingsDeleted)
        assertTrue(result.hasErrors)
        verify(exactly = 0) { polarApi.removeOfflineRecord(deviceId, ppiEntry) }
    }

    @Test
    fun `syncDevice - continues to next recording on failure`() = runTest {
        stubConnectAndFeatureReady()

        val failEntry = createPpiEntry()
        val successEntry = createAccEntry()
        every { polarApi.listOfflineRecordings(deviceId) } returns
            Flowable.fromIterable(listOf(failEntry, successEntry))
        every { polarApi.getOfflineRecord(deviceId, failEntry, null) } returns
            Single.error(RuntimeException("BLE disconnect"))
        every { polarApi.getOfflineRecord(deviceId, successEntry, null) } returns
            Single.just(createAccRecordingData())
        every { polarApi.removeOfflineRecord(deviceId, successEntry) } returns
            Completable.complete()

        val result = sync.syncDevice(deviceId)

        assertEquals(1, result.recordingsProcessed)
        assertEquals(2, result.summariesFetched)
        assertTrue(result.hasErrors)
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `syncDevice - disconnect failure does not propagate`() = runTest {
        stubConnectAndFeatureReady()
        every { polarApi.listOfflineRecordings(deviceId) } returns Flowable.empty()
        every { polarApi.disconnectFromDevice(deviceId) } throws RuntimeException("Already disconnected")

        val result = sync.syncDevice(deviceId)

        assertFalse(result.hasErrors)
    }

    @Test
    fun `syncDevice - delete failure logged but does not stop sync`() = runTest {
        stubConnectAndFeatureReady()

        val ppiEntry = createPpiEntry()
        every { polarApi.listOfflineRecordings(deviceId) } returns Flowable.just(ppiEntry)
        every { polarApi.getOfflineRecord(deviceId, ppiEntry, null) } returns
            Single.just(createPpiRecordingData())
        every { polarApi.removeOfflineRecord(deviceId, ppiEntry) } returns
            Completable.error(RuntimeException("Delete failed"))

        val result = sync.syncDevice(deviceId)

        assertEquals(1, result.recordingsProcessed)
        assertEquals(0, result.recordingsDeleted)
        assertTrue(result.hasErrors) // delete error logged
        assertEquals(2, result.intervalsFetched) // data still stored
    }

    @Test
    fun `deriveSessionId - formats correctly`() {
        val entry = PolarOfflineRecordingEntry(
            path = "/test",
            size = 100,
            date = LocalDateTime.of(2026, 3, 23, 14, 30, 45),
            type = PolarBleApi.PolarDeviceDataType.PPI,
        )

        val sessionId = PolarOfflineSync.deriveSessionId(entry)
        assertEquals("polar_ppi_2026-03-23_143045", sessionId)
    }
}
