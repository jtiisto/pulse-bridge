package dev.jtiisto.pulsebridge.core.sync.di

import dev.jtiisto.pulsebridge.core.sync.SyncConfig
import dev.jtiisto.pulsebridge.core.sync.SyncManager
import org.koin.dsl.module

val syncModule = module {
    single { SyncConfig() }
    single { SyncManager(get(), get(), get(), get(), get(), get(), get(), get()) }
}
