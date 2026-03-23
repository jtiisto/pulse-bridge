package dev.jtiisto.wellnesssync.feature.capture.domain.model

import dev.jtiisto.wellnesssync.core.ble.model.ConnectionState
import dev.jtiisto.wellnesssync.core.ble.device.KnownDevice
import dev.jtiisto.wellnesssync.core.ble.scanner.DiscoveredDevice

data class CaptureState(
    val isCapturing: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val currentHr: Int? = null,
    val deviceName: String? = null,
    val intervalCount: Int = 0,
    val unsyncedCount: Int = 0,
    val lastSyncTime: Long? = null,
    val error: String? = null,
    val permissionsGranted: Boolean = false,
    val isScanning: Boolean = false,
    val knownDevices: List<KnownDevice> = emptyList(),
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
)
