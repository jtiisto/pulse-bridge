package dev.jtiisto.wellnesssync

import android.app.Application
import dev.jtiisto.wellnesssync.core.ble.polar.PolarDeviceDetector
import dev.jtiisto.wellnesssync.core.ble.polar.PolarDeviceStore
import dev.jtiisto.wellnesssync.core.sync.SyncWorker
import dev.jtiisto.wellnesssync.di.appModule
import org.koin.android.ext.android.get
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
        // PendingIntent scan registrations don't survive process death or reboot,
        // so re-register once per process — NOT per ViewModel, which would count
        // against Android's 5-scans-per-30s throttle on every screen recreation
        get<PolarDeviceDetector>().registerAllKnownDevices(get<PolarDeviceStore>().getDeviceIds())
    }
}
