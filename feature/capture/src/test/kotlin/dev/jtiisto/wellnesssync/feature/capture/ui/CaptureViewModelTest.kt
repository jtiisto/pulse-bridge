package dev.jtiisto.wellnesssync.feature.capture.ui

import android.app.Application
import app.cash.turbine.test
import dev.jtiisto.wellnesssync.core.ble.device.KnownDevice
import dev.jtiisto.wellnesssync.core.ble.model.ConnectionState
import dev.jtiisto.wellnesssync.core.ble.model.HeartRateSample
import dev.jtiisto.wellnesssync.core.ble.model.SensorPriority
import dev.jtiisto.wellnesssync.core.ble.polar.PolarSyncServiceState
import dev.jtiisto.wellnesssync.core.ble.service.BleCaptureServiceState
import dev.jtiisto.wellnesssync.core.database.entity.SyncStatusEntity
import dev.jtiisto.wellnesssync.core.network.ServerHealthMonitor
import dev.jtiisto.wellnesssync.core.network.ServerStatus
import dev.jtiisto.wellnesssync.feature.capture.data.CaptureRepository
import dev.jtiisto.wellnesssync.feature.capture.domain.model.CaptureEvent
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var serviceStateFlow: MutableStateFlow<BleCaptureServiceState>
    private lateinit var beatFlow: MutableSharedFlow<HeartRateSample>
    private lateinit var repository: CaptureRepository
    private lateinit var serverHealthMonitor: ServerHealthMonitor
    private lateinit var application: Application
    private lateinit var viewModel: CaptureViewModel
    private var clockMs = 100_000L

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        serviceStateFlow = MutableStateFlow(BleCaptureServiceState())
        beatFlow = MutableSharedFlow()
        application = mockk(relaxed = true)
        repository = mockk(relaxed = true) {
            every { serviceStateFlow } returns this@CaptureViewModelTest.serviceStateFlow
            every { polarSyncStateFlow } returns MutableStateFlow(PolarSyncServiceState())
            every { unsyncedCount } returns flowOf(0)
            every { quarantinedCount } returns flowOf(0)
            every { syncStatus } returns flowOf(null)
            every { getKnownDevices() } returns emptyList()
            every { getPolarDevices() } returns emptyList()
            every { beatStream } returns beatFlow
        }
        serverHealthMonitor = mockk(relaxed = true) {
            every { status } returns MutableStateFlow(ServerStatus.CHECKING)
        }
        viewModel = CaptureViewModel(application, repository, serverHealthMonitor, clock = { clockMs })
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is not capturing and disconnected`() = runTest {
        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isCapturing)
            assertEquals(ConnectionState.DISCONNECTED, state.connectionState)
        }
    }

    @Test
    fun `state reflects service state changes`() = runTest {
        viewModel.state.test {
            awaitItem() // initial

            serviceStateFlow.value = BleCaptureServiceState(
                isRunning = true,
                connectionState = ConnectionState.CONNECTED,
                currentHr = 142,
                deviceName = "Garmin HRM-Pro",
                intervalCount = 10,
            )

            val state = awaitItem()
            assertTrue(state.isCapturing)
            assertEquals(ConnectionState.CONNECTED, state.connectionState)
            assertEquals(142, state.currentHr)
            assertEquals("Garmin HRM-Pro", state.deviceName)
            assertEquals(10, state.intervalCount)
        }
    }

    @Test
    fun `stop capture delegates to repository`() = runTest {
        viewModel.onEvent(CaptureEvent.StopCapture)
        testDispatcher.scheduler.advanceUntilIdle()

        verify { repository.stopCapture(application) }
    }

    @Test
    fun `dismiss error clears error from state`() = runTest {
        viewModel.state.test {
            awaitItem() // initial

            serviceStateFlow.value = BleCaptureServiceState(error = "test error")
            val errorState = awaitItem()
            assertEquals("test error", errorState.error)

            viewModel.onEvent(CaptureEvent.DismissError)
            val clearedState = awaitItem()
            assertEquals(null, clearedState.error)
        }
    }

    @Test
    fun `permissions granted updates state`() = runTest {
        viewModel.state.test {
            awaitItem() // initial

            viewModel.onEvent(CaptureEvent.PermissionsGranted)
            val state = awaitItem()
            assertTrue(state.permissionsGranted)
        }
    }

    @Test
    fun `remove known device delegates and refreshes list`() = runTest {
        val devices = listOf(KnownDevice("AA:BB:CC:DD:EE:FF", "Garmin"))
        every { repository.getKnownDevices() } returns devices andThen emptyList()

        viewModel.onEvent(CaptureEvent.RemoveKnownDevice("AA:BB:CC:DD:EE:FF"))
        testDispatcher.scheduler.advanceUntilIdle()

        verify { repository.removeKnownDevice("AA:BB:CC:DD:EE:FF") }
    }

    @Test
    fun `start capture delegates to repository`() = runTest {
        viewModel.onEvent(CaptureEvent.PermissionsGranted)
        viewModel.onEvent(CaptureEvent.StartCapture("AA:BB:CC:DD:EE:FF", "Garmin"))
        testDispatcher.scheduler.advanceUntilIdle()

        verify { repository.startCapture(application, "AA:BB:CC:DD:EE:FF", "Garmin") }
    }

    @Test
    fun `BLE actions are blocked until permissions are granted`() = runTest {
        viewModel.onEvent(CaptureEvent.StartScan)
        viewModel.onEvent(CaptureEvent.StartPolarScan)
        viewModel.onEvent(CaptureEvent.StartCapture("AA:BB:CC:DD:EE:FF", "Garmin"))
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 0) { repository.scanForDevices() }
        verify(exactly = 0) { repository.startCapture(any(), any(), any()) }
        assertFalse(viewModel.state.value.isScanning)
        assertFalse(viewModel.state.value.isPolarScanning)
    }

    @Test
    fun `granting permissions registers known Polar scans`() = runTest {
        viewModel.onEvent(CaptureEvent.PermissionsGranted)
        testDispatcher.scheduler.advanceUntilIdle()

        verify { repository.registerAllPolarScans() }
        verify { repository.startPeriodicSync(application) }
    }

    @Test
    fun `start capture stops an active polar scan`() = runTest {
        every { repository.scanForDevices() } returns MutableSharedFlow()

        viewModel.onEvent(CaptureEvent.PermissionsGranted)
        viewModel.onEvent(CaptureEvent.StartPolarScan)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.isPolarScanning)

        viewModel.onEvent(CaptureEvent.StartCapture("AA:BB:CC:DD:EE:FF", "Garmin"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.state.value.isPolarScanning)
        verify { repository.startCapture(application, "AA:BB:CC:DD:EE:FF", "Garmin") }
    }

    @Test
    fun `start capture stops an active device scan`() = runTest {
        every { repository.scanForDevices() } returns MutableSharedFlow()

        viewModel.onEvent(CaptureEvent.PermissionsGranted)
        viewModel.onEvent(CaptureEvent.StartScan)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.isScanning)

        viewModel.onEvent(CaptureEvent.StartCapture("AA:BB:CC:DD:EE:FF", "Garmin"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.state.value.isScanning)
    }

    @Test
    fun `unsynced count updates state`() = runTest {
        val unsyncedFlow = MutableStateFlow(0)
        every { repository.unsyncedCount } returns unsyncedFlow

        val vm = CaptureViewModel(application, repository, serverHealthMonitor)
        vm.state.test {
            awaitItem() // initial

            unsyncedFlow.value = 42
            val state = awaitItem()
            assertEquals(42, state.unsyncedCount)
        }
    }

    private fun beatSample(rrs: List<Int>) = HeartRateSample(
        deviceId = "AA:BB:CC:DD:EE:FF",
        timestampDevice = clockMs,
        heartRateBpm = 70,
        rrIntervalsMs = rrs,
        sensorPriority = SensorPriority.GARMIN_ECG,
        sensorType = "garmin_hrm",
    )

    @Test
    fun `beats update chart points while capturing`() = runTest {
        serviceStateFlow.value = BleCaptureServiceState(isRunning = true)
        testDispatcher.scheduler.advanceUntilIdle()

        beatFlow.emit(beatSample(rrs = listOf(800)))
        testDispatcher.scheduler.advanceUntilIdle()

        val points = viewModel.state.value.chartPoints
        assertEquals(1, points.size)
        assertEquals(75f, points.first().hrBpm)
    }

    @Test
    fun `chart points are cleared when capture stops`() = runTest {
        serviceStateFlow.value = BleCaptureServiceState(isRunning = true)
        testDispatcher.scheduler.advanceUntilIdle()
        beatFlow.emit(beatSample(rrs = listOf(800)))
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.state.value.chartPoints.size)

        serviceStateFlow.value = BleCaptureServiceState(isRunning = false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value.chartPoints.isEmpty())
        assertEquals(0, beatFlow.subscriptionCount.value)
    }

    @Test
    fun `beats are not collected when not capturing`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, beatFlow.subscriptionCount.value)
        assertTrue(viewModel.state.value.chartPoints.isEmpty())
    }

    @Test
    fun `quarantined count updates state`() = runTest {
        val quarantinedFlow = MutableStateFlow(0)
        every { repository.quarantinedCount } returns quarantinedFlow

        val vm = CaptureViewModel(application, repository, serverHealthMonitor, clock = { clockMs })
        vm.state.test {
            awaitItem() // initial

            quarantinedFlow.value = 7
            val state = awaitItem()
            assertEquals(7, state.quarantinedCount)
        }
    }

    @Test
    fun `retry quarantined delegates to repository`() = runTest {
        viewModel.onEvent(CaptureEvent.RetryQuarantined)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.retryQuarantined(application) }
    }

    @Test
    fun `sync status updates last sync time`() = runTest {
        val syncFlow = MutableStateFlow<SyncStatusEntity?>(null)
        every { repository.syncStatus } returns syncFlow

        val vm = CaptureViewModel(application, repository, serverHealthMonitor)
        vm.state.test {
            awaitItem() // initial

            syncFlow.value = SyncStatusEntity(lastSyncTime = 1234567890L)
            val state = awaitItem()
            assertEquals(1234567890L, state.lastSyncTime)
        }
    }
}
