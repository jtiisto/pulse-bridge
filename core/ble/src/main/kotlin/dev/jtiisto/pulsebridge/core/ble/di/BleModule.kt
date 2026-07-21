package dev.jtiisto.pulsebridge.core.ble.di

import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiDefaultImpl
import dev.jtiisto.pulsebridge.core.ble.buffer.IntervalBuffer
import dev.jtiisto.pulsebridge.core.ble.connection.PriorityMultiplexer
import dev.jtiisto.pulsebridge.core.ble.device.KnownDeviceStore
import dev.jtiisto.pulsebridge.core.ble.polar.PolarDeviceDetector
import dev.jtiisto.pulsebridge.core.ble.polar.PolarDeviceStore
import dev.jtiisto.pulsebridge.core.ble.polar.PolarOfflineSync
import dev.jtiisto.pulsebridge.core.ble.polar.PolarRecordingParser
import dev.jtiisto.pulsebridge.core.ble.polar.PolarSyncServiceState
import dev.jtiisto.pulsebridge.core.ble.scanner.BleScanner
import dev.jtiisto.pulsebridge.core.ble.service.BleCaptureServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.module

// Koin keys definitions by raw class — generics are erased — so the two
// MutableStateFlow singles MUST carry distinct qualifiers or they collide.
val bleCaptureStateQualifier: Qualifier = named("bleCaptureServiceState")
val polarSyncStateQualifier: Qualifier = named("polarSyncServiceState")

val bleModule = module {
    // Garmin BLE capture
    single(bleCaptureStateQualifier) { MutableStateFlow(BleCaptureServiceState()) }
    single { PriorityMultiplexer() }
    single { BleScanner(get(), get()) }
    single { KnownDeviceStore(get()) }
    single {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        IntervalBuffer(
            intervalDao = get(),
            diagnosticLog = get(),
            scope = scope,
        )
    }

    // Polar offline sync
    single(polarSyncStateQualifier) { MutableStateFlow(PolarSyncServiceState()) }
    single {
        PolarBleApiDefaultImpl.defaultImplementation(
            get(),
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
            ),
        )
    }
    single { PolarRecordingParser() }
    single {
        PolarOfflineSync(
            polarApi = get(),
            intervalDao = get(),
            accDao = get(),
            parser = get(),
            diagnosticLog = get(),
        )
    }
    single { PolarDeviceStore(get()) }
    single { PolarDeviceDetector(get()) }
}
