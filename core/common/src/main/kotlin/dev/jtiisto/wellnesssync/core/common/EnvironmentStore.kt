package dev.jtiisto.wellnesssync.core.common

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SyncEnvironment(val headerValue: String) {
    PRODUCTION("production"),
    TEST("test"),
}

class EnvironmentStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "environment_prefs"
        private const val KEY_ENVIRONMENT = "sync_environment"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _environment = MutableStateFlow(load())
    val environment: StateFlow<SyncEnvironment> = _environment.asStateFlow()

    val current: SyncEnvironment get() = _environment.value

    fun set(env: SyncEnvironment) {
        prefs.edit().putString(KEY_ENVIRONMENT, env.headerValue).apply()
        _environment.value = env
    }

    private fun load(): SyncEnvironment {
        val stored = prefs.getString(KEY_ENVIRONMENT, null)
        return SyncEnvironment.entries.firstOrNull { it.headerValue == stored }
            ?: SyncEnvironment.PRODUCTION
    }
}
