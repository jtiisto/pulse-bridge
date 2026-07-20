package dev.jtiisto.wellnesssync.feature.capture.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellnesssync.core.ble.scanner.DiscoveredDevice
import dev.jtiisto.wellnesssync.core.network.ServerHealthMonitor
import dev.jtiisto.wellnesssync.feature.capture.data.CaptureRepository
import dev.jtiisto.wellnesssync.feature.capture.domain.SignalQualityTracker
import dev.jtiisto.wellnesssync.feature.capture.domain.TachogramBuffer
import dev.jtiisto.wellnesssync.feature.capture.domain.model.CaptureEffect
import dev.jtiisto.wellnesssync.feature.capture.domain.model.CaptureEvent
import dev.jtiisto.wellnesssync.feature.capture.domain.model.CaptureState
import dev.jtiisto.wellnesssync.feature.capture.domain.model.SignalQuality
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CaptureViewModel(
    application: Application,
    private val repository: CaptureRepository,
    private val serverHealthMonitor: ServerHealthMonitor,
    clock: () -> Long = { SystemClock.elapsedRealtime() },
    // Periodic signal-quality recompute so a silent sensor degrades the
    // indicator even with no new beats. 0 disables the loop (tests).
    private val qualityTickMs: Long = 2_000L,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _effects = Channel<CaptureEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var scanJob: Job? = null
    private var polarScanJob: Job? = null
    private var beatJob: Job? = null
    private var qualityTickJob: Job? = null
    private val tachogramBuffer = TachogramBuffer(clock)
    private val signalQualityTracker = SignalQualityTracker(clock)
    private val discoveredSet = mutableSetOf<String>()
    private val discoveredPolarSet = mutableSetOf<String>()

    init {
        observeServiceState()
        observeUnsyncedCount()
        observeQuarantinedCount()
        observeSyncStatus()
        observeServerStatus()
        observePolarSyncState()
        loadKnownDevices()
        loadPolarDevices()
        serverHealthMonitor.start()
    }

    fun onEvent(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.StartCapture -> startCapture(event.deviceAddress, event.deviceName)
            is CaptureEvent.StopCapture -> stopCapture()
            is CaptureEvent.StartScan -> startScan()
            is CaptureEvent.StopScan -> stopScan()
            is CaptureEvent.SyncNow -> syncNow()
            is CaptureEvent.RetryQuarantined -> retryQuarantined()
            is CaptureEvent.RemoveKnownDevice -> removeKnownDevice(event.address)
            is CaptureEvent.DismissError -> _state.update { it.copy(error = null) }
            is CaptureEvent.PermissionsGranted -> {
                _state.update { it.copy(permissionsGranted = true) }
                repository.startPeriodicSync(getApplication())
                // App-startup registration is a no-op before the first grant
                repository.registerAllPolarScans()
            }
            // Polar events
            is CaptureEvent.AddPolarDevice -> addPolarDevice(event.deviceId, event.name)
            is CaptureEvent.RemovePolarDevice -> removePolarDevice(event.deviceId)
            is CaptureEvent.SyncPolarNow -> syncPolarNow(event.deviceId)
            is CaptureEvent.StartPolarScan -> startPolarScan()
            is CaptureEvent.StopPolarScan -> stopPolarScan()
        }
    }

    private fun startCapture(address: String, name: String?) {
        if (!requirePermissions()) return
        // Stop ALL active scans — scanning during connectGatt causes flaky connections
        stopScan()
        stopPolarScan()
        repository.startCapture(getApplication(), address, name)
    }

    // BLE actions without BLUETOOTH_SCAN/CONNECT fail confusingly deep in the
    // stack (silent empty scans, failed service starts) — fail loudly here
    private fun requirePermissions(): Boolean {
        if (_state.value.permissionsGranted) return true
        viewModelScope.launch {
            _effects.send(CaptureEffect.ShowError("Bluetooth permissions are required"))
        }
        return false
    }

    private fun syncNow() {
        repository.syncNow(getApplication())
    }

    private fun retryQuarantined() {
        viewModelScope.launch {
            repository.retryQuarantined(getApplication())
        }
    }

    private fun stopCapture() {
        repository.stopCapture(getApplication())
    }

    private fun startScan() {
        if (!requirePermissions()) return
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

    // Polar methods
    private fun addPolarDevice(deviceId: String, name: String) {
        repository.addPolarDevice(deviceId, name)
        loadPolarDevices()
        stopPolarScan()
    }

    private fun removePolarDevice(deviceId: String) {
        repository.removePolarDevice(deviceId)
        loadPolarDevices()
    }

    private fun syncPolarNow(deviceId: String) {
        repository.syncPolarNow(getApplication(), deviceId)
    }

    private fun loadPolarDevices() {
        _state.update { it.copy(polarDevices = repository.getPolarDevices()) }
    }

    private fun startPolarScan() {
        if (!requirePermissions()) return
        if (polarScanJob?.isActive == true) return

        discoveredPolarSet.clear()
        _state.update { it.copy(isPolarScanning = true, discoveredPolarDevices = emptyList()) }

        polarScanJob = viewModelScope.launch {
            try {
                repository.scanForDevices().collect { device ->
                    handleDiscoveredPolarDevice(device)
                }
            } catch (e: Exception) {
                _state.update { it.copy(isPolarScanning = false) }
                _effects.send(CaptureEffect.ShowError("Polar scan failed: ${e.message}"))
            }
        }
    }

    private fun handleDiscoveredPolarDevice(device: DiscoveredDevice) {
        if (!discoveredPolarSet.add(device.address)) return

        // Only show devices with Polar-like names
        val name = device.name ?: return
        if (!name.contains("Polar", ignoreCase = true)) return

        _state.update { current ->
            current.copy(
                discoveredPolarDevices = current.discoveredPolarDevices + device,
            )
        }
    }

    private fun stopPolarScan() {
        polarScanJob?.cancel()
        polarScanJob = null
        _state.update { it.copy(isPolarScanning = false) }
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
                    startBeatCollection()
                } else {
                    stopBeatCollection()
                }
            }
        }
    }

    private fun startBeatCollection() {
        if (beatJob?.isActive == true) return
        beatJob = viewModelScope.launch {
            repository.beatStream.collect { sample ->
                tachogramBuffer.add(sample)
                signalQualityTracker.add(sample)
                _state.update {
                    it.copy(
                        chartPoints = tachogramBuffer.points(),
                        signalQuality = signalQualityTracker.quality(),
                    )
                }
            }
        }
        // Recompute quality on a timer so silence (sensor stops sending)
        // degrades the indicator instead of leaving it stale
        if (qualityTickMs > 0 && qualityTickJob?.isActive != true) {
            qualityTickJob = viewModelScope.launch {
                while (true) {
                    delay(qualityTickMs)
                    _state.update { it.copy(signalQuality = signalQualityTracker.quality()) }
                }
            }
        }
    }

    private fun stopBeatCollection() {
        if (beatJob == null) return
        beatJob?.cancel()
        beatJob = null
        qualityTickJob?.cancel()
        qualityTickJob = null
        tachogramBuffer.reset()
        signalQualityTracker.reset()
        _state.update { it.copy(chartPoints = emptyList(), signalQuality = SignalQuality()) }
    }

    private fun observePolarSyncState() {
        viewModelScope.launch {
            repository.polarSyncStateFlow.collect { polarState ->
                _state.update { it.copy(polarSyncState = polarState) }
                // Refresh devices after sync completes (last sync time updated)
                if (!polarState.isRunning && polarState.status != dev.jtiisto.wellnesssync.core.ble.polar.PolarSyncServiceState.Status.IDLE) {
                    loadPolarDevices()
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

    private fun observeQuarantinedCount() {
        viewModelScope.launch {
            repository.quarantinedCount.collect { count ->
                _state.update { it.copy(quarantinedCount = count) }
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

    private fun observeServerStatus() {
        viewModelScope.launch {
            serverHealthMonitor.status.collect { status ->
                _state.update { it.copy(serverStatus = status) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        serverHealthMonitor.stop()
    }
}
