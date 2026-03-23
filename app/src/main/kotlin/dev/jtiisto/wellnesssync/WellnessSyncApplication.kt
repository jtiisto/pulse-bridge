package dev.jtiisto.wellnesssync

import android.app.Application
import dev.jtiisto.wellnesssync.core.sync.SyncWorker
import dev.jtiisto.wellnesssync.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class WellnessSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@WellnessSyncApplication)
            modules(appModule)
        }
        SyncWorker.enqueuePeriodicSync(this)
    }
}
