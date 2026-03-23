package dev.jtiisto.wellnesssync.core.ble.connection

import dev.jtiisto.wellnesssync.core.ble.model.ConnectionState
import dev.jtiisto.wellnesssync.core.ble.model.HeartRateSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BleDeviceConnection {
    val deviceId: String
    val connectionState: StateFlow<ConnectionState>
    val heartRateData: Flow<HeartRateSample>
    suspend fun connect()
    suspend fun disconnect()
}
