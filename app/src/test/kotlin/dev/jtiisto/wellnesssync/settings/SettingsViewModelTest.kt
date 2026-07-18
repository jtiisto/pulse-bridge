package dev.jtiisto.wellnesssync.settings

import dev.jtiisto.wellnesssync.core.common.DiagnosticLog
import dev.jtiisto.wellnesssync.core.common.EnvironmentStore
import dev.jtiisto.wellnesssync.core.common.SyncEnvironment
import dev.jtiisto.wellnesssync.core.database.DatabaseCleaner
import dev.jtiisto.wellnesssync.core.network.DiagnosticsApi
import dev.jtiisto.wellnesssync.core.network.dto.DiagnosticUploadDto
import dev.jtiisto.wellnesssync.core.network.dto.DiagnosticUploadResponseDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var environmentStore: EnvironmentStore
    private lateinit var databaseCleaner: DatabaseCleaner
    private lateinit var diagnosticLog: DiagnosticLog
    private lateinit var diagnosticsApi: DiagnosticsApi
    private lateinit var viewModel: SettingsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        environmentStore = mockk {
            every { environment } returns MutableStateFlow(SyncEnvironment.TEST)
            every { current } returns SyncEnvironment.TEST
        }
        databaseCleaner = mockk(relaxed = true)
        diagnosticLog = DiagnosticLog()
        diagnosticsApi = mockk()
        viewModel = SettingsViewModel(environmentStore, databaseCleaner, diagnosticLog, diagnosticsApi)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `upload sends buffered entries with current environment`() = runTest {
        diagnosticLog.log("garmin", "connect() to AA:BB")
        diagnosticLog.log("garmin", "onConnectionStateChange status=133 newState=0")

        val payloadSlot = slot<DiagnosticUploadDto>()
        coEvery { diagnosticsApi.upload(capture(payloadSlot), "test") } returns
            DiagnosticUploadResponseDto(stored = 2, file = "diag_test_1.jsonl")

        viewModel.onEvent(SettingsEvent.UploadDiagnostics)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, payloadSlot.captured.entries.size)
        assertEquals("Uploaded 2 entries", viewModel.state.value.diagnosticUploadResult)
        assertFalse(viewModel.state.value.diagnosticUploadInProgress)
        assertEquals(2, viewModel.state.value.diagnosticCount)
    }

    @Test
    fun `upload failure surfaces the error`() = runTest {
        coEvery { diagnosticsApi.upload(any(), any()) } throws RuntimeException("server unreachable")

        viewModel.onEvent(SettingsEvent.UploadDiagnostics)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Upload failed: server unreachable", viewModel.state.value.diagnosticUploadResult)
        assertFalse(viewModel.state.value.diagnosticUploadInProgress)
    }

    @Test
    fun `dismiss clears the upload result`() = runTest {
        coEvery { diagnosticsApi.upload(any(), any()) } returns
            DiagnosticUploadResponseDto(stored = 0, file = "diag_test_2.jsonl")

        viewModel.onEvent(SettingsEvent.UploadDiagnostics)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onEvent(SettingsEvent.DismissDiagnosticResult)

        assertNull(viewModel.state.value.diagnosticUploadResult)
    }
}
