package dev.jtiisto.wellnesssync.core.ble.buffer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class BufferConfig(
    val flushInterval: Duration = DEFAULT_FLUSH_INTERVAL,
    val maxBufferSize: Int = DEFAULT_MAX_BUFFER_SIZE,
) {
    companion object {
        val DEFAULT_FLUSH_INTERVAL: Duration = 10.seconds
        const val DEFAULT_MAX_BUFFER_SIZE: Int = 200
    }
}
