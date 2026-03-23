package dev.jtiisto.wellnesssync.core.ble.service

import dev.jtiisto.wellnesssync.core.ble.model.ConnectionState

data class BleCaptureServiceState(
    val isRunning: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val currentHr: Int? = null,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val sessionId: String? = null,
    val intervalCount: Int = 0,
    val error: String? = null,
)
