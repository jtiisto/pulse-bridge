package dev.jtiisto.wellnesssync.feature.capture.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellnesssync.core.ble.scanner.DiscoveredDevice
import dev.jtiisto.wellnesssync.feature.capture.data.CaptureRepository
import dev.jtiisto.wellnesssync.feature.capture.domain.model.CaptureEffect
import dev.jtiisto.wellnesssync.feature.capture.domain.model.CaptureEvent
import dev.jtiisto.wellnesssync.feature.capture.domain.model.CaptureState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CaptureViewModel(
    application: Application,
    private val repository: CaptureRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _effects = Channel<CaptureEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var scanJob: Job? = null
    private val discoveredSet = mutableSetOf<String>()

    init {
        observeServiceState()
        observeUnsyncedCount()
        observeSyncStatus()
        loadKnownDevices()
    }

    fun onEvent(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.StartCapture -> startCapture(event.deviceAddress, event.deviceName)
            is CaptureEvent.StopCapture -> stopCapture()
            is CaptureEvent.StartScan -> startScan()
            is CaptureEvent.StopScan -> stopScan()
            is CaptureEvent.SyncNow -> syncNow()
            is CaptureEvent.RemoveKnownDevice -> removeKnownDevice(event.address)
            is CaptureEvent.DismissError -> _state.update { it.copy(error = null) }
            is CaptureEvent.PermissionsGranted -> {
                _state.update { it.copy(permissionsGranted = true) }
                repository.startPeriodicSync(getApplication())
            }
        }
    }

    private fun startCapture(address: String, name: String?) {
        stopScan()
        repository.startCapture(getApplication(), address, name)
    }

    private fun syncNow() {
        repository.syncNow(getApplication())
    }

    private fun stopCapture() {
        repository.stopCapture(getApplication())
    }

    private fun startScan() {
        if (scanJob?.isActive == true) return

        discoveredSet.clear()
        _state.update { it.copy(isScanning = true, discoveredDevices = emptyList()) }

        scanJob = viewModelScope.launch {
            try {
                repository.scanForDevices().collect { device ->
                    handleDiscoveredDevice(device)
                }
            } catch (e: Exception) {
                _state.update { it.copy(isScanning = false) }
                _effects.send(CaptureEffect.ShowError("Scan failed: ${e.message}"))
            }
        }
    }

    private fun handleDiscoveredDevice(device: DiscoveredDevice) {
        // Deduplicate by address
        if (!discoveredSet.add(device.address)) return

        // Auto-connect if known device
        if (repository.isKnownDevice(device.address)) {
            startCapture(device.address, device.name)
            return
        }

        _state.update { current ->
            current.copy(
                discoveredDevices = current.discoveredDevices + device,
            )
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(isScanning = false) }
    }

    private fun removeKnownDevice(address: String) {
        repository.removeKnownDevice(address)
        loadKnownDevices()
    }

    private fun loadKnownDevices() {
        _state.update { it.copy(knownDevices = repository.getKnownDevices()) }
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            repository.serviceStateFlow.collect { serviceState ->
                _state.update { current ->
                    current.copy(
                        isCapturing = serviceState.isRunning,
                        connectionState = serviceState.connectionState,
                        currentHr = serviceState.currentHr,
                        deviceName = serviceState.deviceName,
                        intervalCount = serviceState.intervalCount,
                        error = serviceState.error,
                    )
                }
                // Refresh known devices when capture starts (new device may have been saved)
                if (serviceState.isRunning) {
                    loadKnownDevices()
                }
            }
        }
    }

    private fun observeUnsyncedCount() {
        viewModelScope.launch {
            repository.unsyncedCount.collect { count ->
                _state.update { it.copy(unsyncedCount = count) }
            }
        }
    }

    private fun observeSyncStatus() {
        viewModelScope.launch {
            repository.syncStatus.collect { status ->
                _state.update { it.copy(lastSyncTime = status?.lastSyncTime) }
            }
        }
    }
}
