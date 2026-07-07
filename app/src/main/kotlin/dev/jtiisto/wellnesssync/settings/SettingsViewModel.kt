package dev.jtiisto.wellnesssync.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellnesssync.core.common.EnvironmentStore
import dev.jtiisto.wellnesssync.core.common.SyncEnvironment
import dev.jtiisto.wellnesssync.core.database.DatabaseCleaner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
    val environment: SyncEnvironment = SyncEnvironment.PRODUCTION,
    val showClearDataDialog: Boolean = false,
    val clearDataSuccess: Boolean = false,
)

sealed interface SettingsEvent {
    data class SetEnvironment(val environment: SyncEnvironment) : SettingsEvent
    data object RequestClearData : SettingsEvent
    data object ConfirmClearData : SettingsEvent
    data object DismissClearDataDialog : SettingsEvent
    data object DismissClearDataSuccess : SettingsEvent
}

class SettingsViewModel(
    private val environmentStore: EnvironmentStore,
    private val databaseCleaner: DatabaseCleaner,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
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
        }
    }

    private fun clearLocalData() {
        viewModelScope.launch {
            databaseCleaner.clearSyncedData()
            _state.update { it.copy(clearDataSuccess = true) }
        }
    }
}
