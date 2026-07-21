package dev.jtiisto.pulsebridge.core.database.di

import androidx.room.Room
import dev.jtiisto.pulsebridge.core.database.DatabaseCleaner
import dev.jtiisto.pulsebridge.core.database.PulseBridgeDatabase
import org.koin.dsl.module

val databaseModule = module {
    single {
        Room.databaseBuilder(
            get(),
            PulseBridgeDatabase::class.java,
            "pulse_bridge.db",
        )
            .addMigrations(
                PulseBridgeDatabase.MIGRATION_1_2,
                PulseBridgeDatabase.MIGRATION_2_3,
            )
            .build()
    }
    single { get<PulseBridgeDatabase>().intervalDao() }
    single { get<PulseBridgeDatabase>().deviceSessionDao() }
    single { get<PulseBridgeDatabase>().syncStatusDao() }
    single { get<PulseBridgeDatabase>().accelerometerSummaryDao() }
    single { DatabaseCleaner(get(), get()) }
}
