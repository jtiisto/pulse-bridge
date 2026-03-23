package dev.jtiisto.wellnesssync.core.database.di

import androidx.room.Room
import dev.jtiisto.wellnesssync.core.database.DatabaseCleaner
import dev.jtiisto.wellnesssync.core.database.WellnessSyncDatabase
import org.koin.dsl.module

val databaseModule = module {
    single {
        Room.databaseBuilder(
            get(),
            WellnessSyncDatabase::class.java,
            "wellness_sync.db",
        ).build()
    }
    single { get<WellnessSyncDatabase>().intervalDao() }
    single { get<WellnessSyncDatabase>().deviceSessionDao() }
    single { get<WellnessSyncDatabase>().syncStatusDao() }
    single { DatabaseCleaner(get()) }
}
