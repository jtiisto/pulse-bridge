package dev.jtiisto.wellnesssync.core.sync.di

import dev.jtiisto.wellnesssync.core.sync.SyncConfig
import dev.jtiisto.wellnesssync.core.sync.SyncManager
import org.koin.dsl.module

val syncModule = module {
    single { SyncConfig() }
    single { SyncManager(get(), get(), get(), get(), get(), get(), get()) }
}
