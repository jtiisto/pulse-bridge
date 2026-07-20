package dev.jtiisto.wellnesssync.feature.capture.domain.model

import dev.jtiisto.wellnesssync.core.ble.model.ConnectionState
import dev.jtiisto.wellnesssync.core.ble.device.KnownDevice
import dev.jtiisto.wellnesssync.core.ble.polar.PolarDevice
import dev.jtiisto.wellnesssync.core.ble.polar.PolarSyncServiceState
import dev.jtiisto.wellnesssync.core.ble.scanner.DiscoveredDevice
import dev.jtiisto.wellnesssync.core.network.ServerStatus

data class CaptureState(
    val isCapturing: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val serverStatus: ServerStatus = ServerStatus.CHECKING,
    val currentHr: Int? = null,
    val chartPoints: List<BeatPoint> = emptyList(),
    val signalQuality: SignalQuality = SignalQuality(),
    val deviceName: String? = null,
    val intervalCount: Int = 0,
    val unsyncedCount: Int = 0,
    val quarantinedCount: Int = 0,
    val lastSyncTime: Long? = null,
    val error: String? = null,
    val permissionsGranted: Boolean = false,
    val isScanning: Boolean = false,
    val knownDevices: List<KnownDevice> = emptyList(),
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    // Polar state
    val polarDevices: List<PolarDevice> = emptyList(),
    val polarSyncState: PolarSyncServiceState = PolarSyncServiceState(),
    val isPolarScanning: Boolean = false,
    val discoveredPolarDevices: List<DiscoveredDevice> = emptyList(),
)
