package dev.jtiisto.pulsebridge.core.ble.polar

data class PolarSyncServiceState(
    val isRunning: Boolean = false,
    val deviceId: String? = null,
    val status: Status = Status.IDLE,
    val recordingsFound: Int = 0,
    val recordingsProcessed: Int = 0,
    val intervalsFetched: Int = 0,
    val summariesFetched: Int = 0,
    val error: String? = null,
) {
    enum class Status {
        IDLE,
        CONNECTING,
        LISTING_RECORDINGS,
        FETCHING,
        COMPLETE,
        ERROR,
    }
}
