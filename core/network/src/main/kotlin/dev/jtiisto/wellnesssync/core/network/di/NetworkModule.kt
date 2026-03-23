package dev.jtiisto.wellnesssync.core.network.di

import dev.jtiisto.wellnesssync.core.network.HttpClientProvider
import dev.jtiisto.wellnesssync.core.network.IntervalApi
import dev.jtiisto.wellnesssync.core.network.ServerConfig
import org.koin.dsl.module

val networkModule = module {
    single { ServerConfig() }
    single { HttpClientProvider.create(get()) }
    single { IntervalApi(get()) }
}
