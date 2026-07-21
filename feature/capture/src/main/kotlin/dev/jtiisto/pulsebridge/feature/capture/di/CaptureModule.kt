package dev.jtiisto.pulsebridge.feature.capture.di

import dev.jtiisto.pulsebridge.core.ble.di.bleCaptureStateQualifier
import dev.jtiisto.pulsebridge.core.ble.di.polarSyncStateQualifier
import dev.jtiisto.pulsebridge.feature.capture.data.CaptureRepository
import dev.jtiisto.pulsebridge.feature.capture.ui.CaptureViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val captureModule = module {
    single {
        CaptureRepository(
            serviceState = get(bleCaptureStateQualifier),
            polarSyncState = get(polarSyncStateQualifier),
            intervalDao = get(),
            accelerometerSummaryDao = get(),
            syncStatusDao = get(),
            bleScanner = get(),
            knownDeviceStore = get(),
            polarDeviceStore = get(),
            polarDeviceDetector = get(),
            multiplexer = get(),
        )
    }
    viewModel { CaptureViewModel(get(), get(), get()) }
}
