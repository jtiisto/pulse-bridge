package dev.jtiisto.wellnesssync.core.ble.di

import dev.jtiisto.wellnesssync.core.ble.buffer.IntervalBuffer
import dev.jtiisto.wellnesssync.core.ble.connection.PriorityMultiplexer
import dev.jtiisto.wellnesssync.core.ble.device.KnownDeviceStore
import dev.jtiisto.wellnesssync.core.ble.scanner.BleScanner
import dev.jtiisto.wellnesssync.core.ble.service.BleCaptureServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.module

val bleModule = module {
    single { MutableStateFlow(BleCaptureServiceState()) }
    single { PriorityMultiplexer() }
    single { BleScanner(get()) }
    single { KnownDeviceStore(get()) }
    single {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        IntervalBuffer(
            intervalDao = get(),
            scope = scope,
        )
    }
}
