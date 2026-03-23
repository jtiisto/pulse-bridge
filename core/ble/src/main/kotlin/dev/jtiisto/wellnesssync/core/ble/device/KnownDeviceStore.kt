package dev.jtiisto.wellnesssync.core.ble.device

import android.content.Context
import android.content.SharedPreferences

data class KnownDevice(
    val address: String,
    val name: String,
)

class KnownDeviceStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "known_ble_devices"
        private const val KEY_PREFIX = "device_"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<KnownDevice> {
        return prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) }
            .mapNotNull { (key, value) ->
                val address = key.removePrefix(KEY_PREFIX)
                val name = value as? String ?: return@mapNotNull null
                KnownDevice(address, name)
            }
    }

    fun save(address: String, name: String) {
        prefs.edit().putString("$KEY_PREFIX$address", name).apply()
    }

    fun remove(address: String) {
        prefs.edit().remove("$KEY_PREFIX$address").apply()
    }

    fun isKnown(address: String): Boolean {
        return prefs.contains("$KEY_PREFIX$address")
    }

    fun getName(address: String): String? {
        return prefs.getString("$KEY_PREFIX$address", null)
    }
}
