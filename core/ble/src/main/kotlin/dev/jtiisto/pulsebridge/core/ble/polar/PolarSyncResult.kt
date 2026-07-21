package dev.jtiisto.pulsebridge.core.ble.polar

data class PolarSyncResult(
    val intervalsFetched: Int = 0,
    val summariesFetched: Int = 0,
    val recordingsProcessed: Int = 0,
    val recordingsDeleted: Int = 0,
    val errors: List<String> = emptyList(),
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}
