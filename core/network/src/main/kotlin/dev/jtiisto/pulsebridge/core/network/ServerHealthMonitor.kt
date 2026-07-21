package dev.jtiisto.pulsebridge.core.network

import dev.jtiisto.pulsebridge.core.common.EnvironmentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ServerHealthMonitor(
    private val api: IntervalApi,
    private val environmentStore: EnvironmentStore,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 30_000L
    }

    private val _status = MutableStateFlow(ServerStatus.CHECKING)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private var pollJob: Job? = null

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            while (true) {
                check()
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun reportSuccess() {
        _status.value = ServerStatus.CONNECTED
    }

    fun reportFailure() {
        _status.value = ServerStatus.UNREACHABLE
    }

    private suspend fun check() {
        try {
            api.health(environmentStore.current.headerValue)
            _status.value = ServerStatus.CONNECTED
        } catch (_: Exception) {
            _status.value = ServerStatus.UNREACHABLE
        }
    }
}
