package dev.jtiisto.pulsebridge.core.ble.polar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

class PolarSyncNotification(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "polar_sync"
        const val NOTIFICATION_ID = 2
        private const val CHANNEL_NAME = "Polar Sync"
    }

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Notification during Polar offline recording sync"
            setShowBadge(false)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun build(state: PolarSyncServiceState): Notification {
        val contentText = when (state.status) {
            PolarSyncServiceState.Status.IDLE -> "Preparing..."
            PolarSyncServiceState.Status.CONNECTING -> "Connecting to Polar device..."
            PolarSyncServiceState.Status.LISTING_RECORDINGS -> "Checking for recordings..."
            PolarSyncServiceState.Status.FETCHING ->
                "Syncing ${state.recordingsProcessed}/${state.recordingsFound} recordings"
            PolarSyncServiceState.Status.COMPLETE -> {
                val total = state.intervalsFetched + state.summariesFetched
                "Sync complete — $total data points fetched"
            }
            PolarSyncServiceState.Status.ERROR -> "Sync error: ${state.error}"
        }

        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }

        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context, 0, it, PendingIntent.FLAG_IMMUTABLE,
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Polar Sync")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(state.status != PolarSyncServiceState.Status.COMPLETE)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
