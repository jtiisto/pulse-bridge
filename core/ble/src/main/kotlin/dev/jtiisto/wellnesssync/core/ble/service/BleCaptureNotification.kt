package dev.jtiisto.wellnesssync.core.ble.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.jtiisto.wellnesssync.core.ble.model.ConnectionState

class BleCaptureNotification(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "ble_capture"
        const val NOTIFICATION_ID = 1
        private const val CHANNEL_NAME = "BLE Capture"
    }

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing notification during BLE heart rate capture"
            setShowBadge(false)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun build(state: BleCaptureServiceState): Notification {
        val contentText = when (state.connectionState) {
            ConnectionState.CONNECTED -> {
                val hr = state.currentHr
                if (hr != null) "Connected — $hr bpm" else "Connected"
            }
            ConnectionState.CONNECTING -> "Connecting..."
            ConnectionState.SCANNING -> "Scanning..."
            ConnectionState.RECONNECTING -> "Reconnecting..."
            ConnectionState.DISCONNECTED -> "Disconnected"
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
            .setContentTitle("Wellness Sync")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
