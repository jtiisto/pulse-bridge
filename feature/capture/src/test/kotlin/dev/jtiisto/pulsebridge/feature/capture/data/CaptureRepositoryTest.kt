package dev.jtiisto.pulsebridge.feature.capture.data

import android.content.Context
import android.content.Intent
import app.cash.turbine.test
import dev.jtiisto.pulsebridge.core.ble.connection.PriorityMultiplexer
import dev.jtiisto.pulsebridge.core.ble.device.KnownDevice
import dev.jtiisto.pulsebridge.core.ble.device.KnownDeviceStore
import dev.jtiisto.pulsebridge.core.ble.model.HeartRateSample
import dev.jtiisto.pulsebridge.core.ble.polar.PolarDevice
import dev.jtiisto.pulsebridge.core.ble.polar.PolarDeviceDetector
import dev.jtiisto.pulsebridge.core.ble.polar.PolarDeviceStore
import dev.jtiisto.pulsebridge.core.ble.polar.PolarSyncServiceState
import dev.jtiisto.pulsebridge.core.ble.service.BleCaptureService
import dev.jtiisto.pulsebridge.core.ble.service.BleCaptureServiceState
import dev.jtiisto.pulsebridge.core.database.dao.AccelerometerSummaryDao
import dev.jtiisto.pulsebridge.core.database.dao.IntervalDao
import dev.jtiisto.pulsebridge.core.database.dao.SyncStatusDao
import dev.jtiisto.pulsebridge.core.sync.SyncWorker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CaptureRepositoryTest {

    private lateinit var serviceState: MutableStateFlow<BleCaptureServiceState>
    private lateinit var polarSyncState: MutableStateFlow<PolarSyncServiceState>
    private lateinit var intervalDao: IntervalDao
    private lateinit var accelerometerSummaryDao: AccelerometerSummaryDao
    private lateinit var syncStatusDao: SyncStatusDao
    private lateinit var knownDeviceStore: KnownDeviceStore
    private lateinit var polarDeviceStore: PolarDeviceStore
    private lateinit var polarDeviceDetector: PolarDeviceDetector
    private lateinit var multiplexer: PriorityMultiplexer
    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        serviceState = MutableStateFlow(BleCaptureServiceState())
        polarSyncState = MutableStateFlow(PolarSyncServiceState())
        // The repository builds its flows in property initializers, so every
        // construction touches these getters — stub defaults up front and
        // re-stub per test before calling repository()
        intervalDao = mockk {
            every { getUnsyncedCount() } returns flowOf(0)
            every { getQuarantinedCount() } returns flowOf(0)
        }
        accelerometerSummaryDao = mockk {
            every { getUnsyncedCount() } returns flowOf(0)
            every { getQuarantinedCount() } returns flowOf(0)
        }
        syncStatusDao = mockk {
            every { observe() } returns flowOf(null)
        }
        knownDeviceStore = mockk(relaxed = true)
        polarDeviceStore = mockk(relaxed = true)
        polarDeviceDetector = mockk(relaxed = true)
        multiplexer = mockk(relaxed = true)
        context = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun repository() = CaptureRepository(
        serviceState = serviceState,
        polarSyncState = polarSyncState,
        intervalDao = intervalDao,
        accelerometerSummaryDao = accelerometerSummaryDao,
        syncStatusDao = syncStatusDao,
        bleScanner = mockk(relaxed = true),
        knownDeviceStore = knownDeviceStore,
        polarDeviceStore = polarDeviceStore,
        polarDeviceDetector = polarDeviceDetector,
        multiplexer = multiplexer,
    )

    @Test
    fun `unsyncedCount sums intervals and accelerometer summaries`() = runTest {
        every { intervalDao.getUnsyncedCount() } returns flowOf(12)
        every { accelerometerSummaryDao.getUnsyncedCount() } returns flowOf(5)

        repository().unsyncedCount.test {
            assertEquals(17, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `quarantinedCount sums both streams`() = runTest {
        every { intervalDao.getQuarantinedCount() } returns flowOf(3)
        every { accelerometerSummaryDao.getQuarantinedCount() } returns flowOf(2)

        repository().quarantinedCount.test {
            assertEquals(5, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `retryQuarantined clears both quarantines before enqueueing sync`() = runTest {
        mockkObject(SyncWorker.Companion)
        every { SyncWorker.enqueueSyncNow(any()) } just Runs
        coEvery { intervalDao.clearQuarantine() } returns 3
        coEvery { accelerometerSummaryDao.clearQuarantine() } returns 1

        repository().retryQuarantined(context)

        coVerify(exactly = 1) { intervalDao.clearQuarantine() }
        coVerify(exactly = 1) { accelerometerSummaryDao.clearQuarantine() }
        verify(exactly = 1) { SyncWorker.enqueueSyncNow(context) }
    }

    @Test
    fun `addPolarDevice persists before registering the scan filter`() {
        repository().addPolarDevice("AA:BB:CC:DD:EE:01", "Polar Sense 123")

        // The scan callback resolves the device from the store, so the save
        // must land first
        verifyOrder {
            polarDeviceStore.save("AA:BB:CC:DD:EE:01", "Polar Sense 123")
            polarDeviceDetector.registerScanFilter("AA:BB:CC:DD:EE:01")
        }
    }

    @Test
    fun `removePolarDevice unregisters the scan filter before forgetting the device`() {
        repository().removePolarDevice("AA:BB:CC:DD:EE:01")

        verifyOrder {
            polarDeviceDetector.unregisterScanFilter("AA:BB:CC:DD:EE:01")
            polarDeviceStore.remove("AA:BB:CC:DD:EE:01")
        }
    }

    @Test
    fun `registerAllPolarScans registers every stored device id`() {
        every { polarDeviceStore.getDeviceIds() } returns
            listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02")

        repository().registerAllPolarScans()

        verify(exactly = 1) {
            polarDeviceDetector.registerAllKnownDevices(
                listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"),
            )
        }
    }

    @Test
    fun `startCapture launches the capture service as a foreground service`() {
        mockkObject(BleCaptureService.Companion)
        val intent = mockk<Intent>()
        every { BleCaptureService.startIntent(context, "AA:BB:CC:DD:EE:01", "HRM 200") } returns intent

        repository().startCapture(context, "AA:BB:CC:DD:EE:01", "HRM 200")

        verify(exactly = 1) { context.startForegroundService(intent) }
    }

    @Test
    fun `stopCapture routes through a plain service intent`() {
        mockkObject(BleCaptureService.Companion)
        val intent = mockk<Intent>()
        every { BleCaptureService.stopIntent(context) } returns intent

        repository().stopCapture(context)

        verify(exactly = 1) { context.startService(intent) }
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `syncNow and startPeriodicSync delegate to SyncWorker`() {
        mockkObject(SyncWorker.Companion)
        every { SyncWorker.enqueueSyncNow(any()) } just Runs
        every { SyncWorker.enqueuePeriodicSync(any()) } just Runs

        val repo = repository()
        repo.syncNow(context)
        repo.startPeriodicSync(context)

        verify(exactly = 1) { SyncWorker.enqueueSyncNow(context) }
        verify(exactly = 1) { SyncWorker.enqueuePeriodicSync(context) }
    }

    @Test
    fun `known device queries delegate to the store`() {
        val known = listOf(KnownDevice(address = "AA:BB:CC:DD:EE:01", name = "HRM 200"))
        every { knownDeviceStore.getAll() } returns known
        every { knownDeviceStore.isKnown("AA:BB:CC:DD:EE:01") } returns true
        every { knownDeviceStore.isKnown("AA:BB:CC:DD:EE:99") } returns false

        val repo = repository()
        assertEquals(known, repo.getKnownDevices())
        assertTrue(repo.isKnownDevice("AA:BB:CC:DD:EE:01"))
        assertFalse(repo.isKnownDevice("AA:BB:CC:DD:EE:99"))

        repo.removeKnownDevice("AA:BB:CC:DD:EE:01")
        verify(exactly = 1) { knownDeviceStore.remove("AA:BB:CC:DD:EE:01") }
    }

    @Test
    fun `getPolarDevices delegates to the store`() {
        val devices = listOf(PolarDevice(deviceId = "AA:BB:CC:DD:EE:01", name = "Polar Sense 123"))
        every { polarDeviceStore.getAll() } returns devices

        assertEquals(devices, repository().getPolarDevices())
    }

    @Test
    fun `beatStream is the multiplexer's authoritative stream`() {
        val stream = mockk<kotlinx.coroutines.flow.Flow<HeartRateSample>>()
        every { multiplexer.authoritativeStream } returns stream

        assertSame(stream, repository().beatStream)
    }

    @Test
    fun `service state flows are exposed unchanged`() {
        val repo = repository()
        assertSame(serviceState, repo.serviceStateFlow)
        assertSame(polarSyncState, repo.polarSyncStateFlow)
    }
}
