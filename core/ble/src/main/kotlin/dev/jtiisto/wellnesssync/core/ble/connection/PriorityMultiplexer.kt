package dev.jtiisto.wellnesssync.core.ble.connection

import dev.jtiisto.wellnesssync.core.ble.model.HeartRateSample
import dev.jtiisto.wellnesssync.core.ble.model.SensorPriority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Multiplexes multiple sensor data flows, emitting from the highest-priority
 * active source. When a higher-priority sensor connects, it becomes the
 * authoritative source. When it disconnects, the next-highest resumes.
 *
 * Phase 1: single-source passthrough (Garmin only).
 * Phase 2: Garmin strap connecting = exercise started → auto-priority switch.
 *
 * All register/unregister operations are synchronized to prevent race conditions.
 */
class PriorityMultiplexer {

    private data class RegisteredSource(
        val priority: SensorPriority,
        val flow: Flow<HeartRateSample>,
    )

    private val lock = Any()
    private val sources = mutableMapOf<String, RegisteredSource>()
    private val _activeSensorId = MutableStateFlow<String?>(null)
    val activeSensorId: StateFlow<String?> = _activeSensorId.asStateFlow()

    private val _activeFlow = MutableStateFlow<Flow<HeartRateSample>>(emptyFlow())

    /**
     * The authoritative stream — emits from the highest-priority active source.
     * Switches automatically when sources are registered/unregistered.
     */
    val authoritativeStream: Flow<HeartRateSample> = _activeFlow.flatMapLatest { it }

    fun register(deviceId: String, priority: SensorPriority, flow: Flow<HeartRateSample>) {
        synchronized(lock) {
            sources[deviceId] = RegisteredSource(priority, flow)
            resolveActiveSource()
        }
    }

    fun unregister(deviceId: String) {
        synchronized(lock) {
            sources.remove(deviceId)
            resolveActiveSource()
        }
    }

    private fun resolveActiveSource() {
        val best = sources.entries
            .minByOrNull { it.value.priority.rank }

        _activeSensorId.value = best?.key
        _activeFlow.value = best?.value?.flow ?: emptyFlow()
    }
}
