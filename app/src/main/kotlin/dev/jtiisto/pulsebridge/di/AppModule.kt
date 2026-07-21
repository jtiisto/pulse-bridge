package dev.jtiisto.pulsebridge.di

import dev.jtiisto.pulsebridge.core.ble.di.bleModule
import dev.jtiisto.pulsebridge.core.common.DiagnosticLog
import dev.jtiisto.pulsebridge.core.common.EnvironmentStore
import dev.jtiisto.pulsebridge.core.database.di.databaseModule
import dev.jtiisto.pulsebridge.core.network.di.networkModule
import dev.jtiisto.pulsebridge.core.sync.di.syncModule
import dev.jtiisto.pulsebridge.feature.capture.di.captureModule
import dev.jtiisto.pulsebridge.settings.SettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    includes(databaseModule, bleModule, networkModule, syncModule, captureModule)
    single { EnvironmentStore(get()) }
    single { DiagnosticLog() }
    viewModel { SettingsViewModel(get(), get(), get(), get()) }
}
