package dev.jtiisto.pulsebridge.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.pulsebridge.core.common.DiagnosticLog
import dev.jtiisto.pulsebridge.core.common.EnvironmentStore
import dev.jtiisto.pulsebridge.core.common.SyncEnvironment
import dev.jtiisto.pulsebridge.core.database.DatabaseCleaner
import dev.jtiisto.pulsebridge.core.network.DiagnosticsApi
import dev.jtiisto.pulsebridge.core.network.dto.DiagnosticEntryDto
import dev.jtiisto.pulsebridge.core.network.dto.DiagnosticUploadDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
    val environment: SyncEnvironment = SyncEnvironment.PRODUCTION,
    val showClearDataDialog: Boolean = false,
    val clearDataSuccess: Boolean = false,
    val diagnosticCount: Int = 0,
    val diagnosticUploadInProgress: Boolean = false,
    val diagnosticUploadResult: String? = null,
)

sealed interface SettingsEvent {
    data class SetEnvironment(val environment: SyncEnvironment) : SettingsEvent
    data object RequestClearData : SettingsEvent
    data object ConfirmClearData : SettingsEvent
    data object DismissClearDataDialog : SettingsEvent
    data object DismissClearDataSuccess : SettingsEvent
    data object UploadDiagnostics : SettingsEvent
    data object DismissDiagnosticResult : SettingsEvent
}

class SettingsViewModel(
    private val environmentStore: EnvironmentStore,
    private val databaseCleaner: DatabaseCleaner,
    private val diagnosticLog: DiagnosticLog,
    private val diagnosticsApi: DiagnosticsApi,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState(diagnosticCount = diagnosticLog.size))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            environmentStore.environment.collect { env ->
                _state.update { it.copy(environment = env) }
            }
        }
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SetEnvironment -> {
                environmentStore.set(event.environment)
            }
            is SettingsEvent.RequestClearData -> {
                _state.update { it.copy(showClearDataDialog = true) }
            }
            is SettingsEvent.ConfirmClearData -> {
                _state.update { it.copy(showClearDataDialog = false) }
                clearLocalData()
            }
            is SettingsEvent.DismissClearDataDialog -> {
                _state.update { it.copy(showClearDataDialog = false) }
            }
            is SettingsEvent.DismissClearDataSuccess -> {
                _state.update { it.copy(clearDataSuccess = false) }
            }
            is SettingsEvent.UploadDiagnostics -> uploadDiagnostics()
            is SettingsEvent.DismissDiagnosticResult -> {
                _state.update { it.copy(diagnosticUploadResult = null) }
            }
        }
    }

    private fun clearLocalData() {
        viewModelScope.launch {
            databaseCleaner.clearSyncedData()
            _state.update { it.copy(clearDataSuccess = true) }
        }
    }

    private fun uploadDiagnostics() {
        if (_state.value.diagnosticUploadInProgress) return
        _state.update { it.copy(diagnosticUploadInProgress = true) }

        viewModelScope.launch {
            val result = try {
                val response = diagnosticsApi.upload(
                    DiagnosticUploadDto(
                        deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}",
                        entries = diagnosticLog.snapshot().map {
                            DiagnosticEntryDto(it.timestampMs, it.tag, it.message)
                        },
                    ),
                    environmentStore.current.headerValue,
                )
                "Uploaded ${response.stored} entries"
            } catch (e: Exception) {
                "Upload failed: ${e.message}"
            }
            _state.update {
                it.copy(
                    diagnosticUploadInProgress = false,
                    diagnosticUploadResult = result,
                    diagnosticCount = diagnosticLog.size,
                )
            }
        }
    }
}
