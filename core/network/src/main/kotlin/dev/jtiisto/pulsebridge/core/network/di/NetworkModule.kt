package dev.jtiisto.pulsebridge.core.network.di

import dev.jtiisto.pulsebridge.core.network.AccelerometerApi
import dev.jtiisto.pulsebridge.core.network.DiagnosticsApi
import dev.jtiisto.pulsebridge.core.network.HttpClientProvider
import dev.jtiisto.pulsebridge.core.network.IntervalApi
import dev.jtiisto.pulsebridge.core.network.ServerConfig
import dev.jtiisto.pulsebridge.core.network.ServerHealthMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

val networkModule = module {
    single { ServerConfig() }
    single { HttpClientProvider.create(get()) }
    single { IntervalApi(get()) }
    single { AccelerometerApi(get()) }
    single { DiagnosticsApi(get()) }
    single {
        ServerHealthMonitor(
            api = get(),
            environmentStore = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}
