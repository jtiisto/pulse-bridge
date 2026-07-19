package dev.jtiisto.wellnesssync.feature.capture.di

import dev.jtiisto.wellnesssync.core.ble.di.bleCaptureStateQualifier
import dev.jtiisto.wellnesssync.core.ble.di.polarSyncStateQualifier
import dev.jtiisto.wellnesssync.feature.capture.data.CaptureRepository
import dev.jtiisto.wellnesssync.feature.capture.ui.CaptureViewModel
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
