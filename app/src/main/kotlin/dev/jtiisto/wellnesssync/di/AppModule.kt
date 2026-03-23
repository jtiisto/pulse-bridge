package dev.jtiisto.wellnesssync.di

import dev.jtiisto.wellnesssync.core.ble.di.bleModule
import dev.jtiisto.wellnesssync.core.common.EnvironmentStore
import dev.jtiisto.wellnesssync.core.database.di.databaseModule
import dev.jtiisto.wellnesssync.core.network.di.networkModule
import dev.jtiisto.wellnesssync.core.sync.di.syncModule
import dev.jtiisto.wellnesssync.feature.capture.di.captureModule
import dev.jtiisto.wellnesssync.settings.SettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    includes(databaseModule, bleModule, networkModule, syncModule, captureModule)
    single { EnvironmentStore(get()) }
    viewModel { SettingsViewModel(get(), get()) }
}
