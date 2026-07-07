package dev.jtiisto.wellnesssync.core.ble.polar

import android.content.Context
import android.content.SharedPreferences

data class PolarDevice(
    val deviceId: String,
    val name: String,
    val lastSyncTime: Long? = null,
)

class PolarDeviceStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "known_polar_devices"
        private const val KEY_PREFIX = "polar_"
        private const val SYNC_KEY_PREFIX = "polar_sync_"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<PolarDevice> {
        return prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) && !it.key.startsWith(SYNC_KEY_PREFIX) }
            .mapNotNull { (key, value) ->
                val deviceId = key.removePrefix(KEY_PREFIX)
                val name = value as? String ?: return@mapNotNull null
                val lastSync = prefs.getLong("$SYNC_KEY_PREFIX$deviceId", 0L)
                    .takeIf { it > 0 }
                PolarDevice(deviceId, name, lastSync)
            }
    }

    fun save(deviceId: String, name: String) {
        prefs.edit().putString("$KEY_PREFIX$deviceId", name).apply()
    }

    fun remove(deviceId: String) {
        prefs.edit()
            .remove("$KEY_PREFIX$deviceId")
            .remove("$SYNC_KEY_PREFIX$deviceId")
            .apply()
    }

    fun isKnown(deviceId: String): Boolean {
        return prefs.contains("$KEY_PREFIX$deviceId")
    }

    fun updateLastSync(deviceId: String, timestamp: Long) {
        prefs.edit().putLong("$SYNC_KEY_PREFIX$deviceId", timestamp).apply()
    }

    fun getDeviceIds(): List<String> {
        return getAll().map { it.deviceId }
    }
}
