package dev.jtiisto.wellnesssync.core.ble.polar

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import dev.jtiisto.wellnesssync.core.ble.di.polarSyncStateQualifier
import dev.jtiisto.wellnesssync.core.common.DiagnosticLog
import dev.jtiisto.wellnesssync.core.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class PolarSyncService : Service() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
        private const val WAKE_LOCK_TAG = "WellnessSync:PolarSync"

        fun startIntent(context: Context, deviceId: String): Intent {
            return Intent(context, PolarSyncService::class.java).apply {
                putExtra(EXTRA_DEVICE_ID, deviceId)
            }
        }
    }

    private val syncServiceState: MutableStateFlow<PolarSyncServiceState> by inject(polarSyncStateQualifier)
    private val polarOfflineSync: PolarOfflineSync by inject()
    private val polarDeviceStore: PolarDeviceStore by inject()
    private val diagnosticLog: DiagnosticLog by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private val notification by lazy { PolarSyncNotification(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notification.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
        if (deviceId == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Don't start a new sync if one is already running
        if (syncServiceState.value.isRunning) {
            return START_NOT_STICKY
        }

        startSync(deviceId)
        return START_NOT_STICKY
    }

    private fun startSync(deviceId: String) {
        diagnosticLog.log("polar", "PolarSyncService starting for $deviceId")
        val initialState = PolarSyncServiceState(
            isRunning = true,
            deviceId = deviceId,
            status = PolarSyncServiceState.Status.CONNECTING,
        )
        syncServiceState.value = initialState

        startForeground(PolarSyncNotification.NOTIFICATION_ID, notification.build(initialState))
        acquireWakeLock()

        serviceScope.launch(Dispatchers.IO) {
            try {
                syncServiceState.value = syncServiceState.value.copy(
                    status = PolarSyncServiceState.Status.FETCHING,
                )
                updateNotification()

                val result = polarOfflineSync.syncDevice(deviceId)

                val finalStatus = if (result.hasErrors && result.recordingsProcessed == 0) {
                    PolarSyncServiceState.Status.ERROR
                } else {
                    // Partial success counts: recordings were fetched and stored
                    polarDeviceStore.updateLastSync(deviceId, System.currentTimeMillis())
                    PolarSyncServiceState.Status.COMPLETE
                }

                syncServiceState.value = syncServiceState.value.copy(
                    status = finalStatus,
                    recordingsFound = result.recordingsProcessed + result.errors.size,
                    recordingsProcessed = result.recordingsProcessed,
                    intervalsFetched = result.intervalsFetched,
                    summariesFetched = result.summariesFetched,
                    error = result.errors.firstOrNull(),
                )
                updateNotification()

                // Trigger server sync if we fetched any data
                if (result.intervalsFetched > 0 || result.summariesFetched > 0) {
                    SyncWorker.enqueueSyncNow(this@PolarSyncService)
                }
            } catch (e: Exception) {
                syncServiceState.value = syncServiceState.value.copy(
                    status = PolarSyncServiceState.Status.ERROR,
                    error = e.message,
                )
                updateNotification()
            } finally {
                releaseWakeLock()
                syncServiceState.value = syncServiceState.value.copy(isRunning = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun updateNotification() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(PolarSyncNotification.NOTIFICATION_ID, notification.build(syncServiceState.value))
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
