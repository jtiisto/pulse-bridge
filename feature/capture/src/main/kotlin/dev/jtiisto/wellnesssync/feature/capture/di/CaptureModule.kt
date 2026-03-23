package dev.jtiisto.wellnesssync.feature.capture.di

import dev.jtiisto.wellnesssync.feature.capture.data.CaptureRepository
import dev.jtiisto.wellnesssync.feature.capture.ui.CaptureViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val captureModule = module {
    single {
        CaptureRepository(
            serviceState = get(),
            intervalDao = get(),
            syncStatusDao = get(),
            bleScanner = get(),
            knownDeviceStore = get(),
        )
    }
    viewModel { CaptureViewModel(get(), get()) }
}
