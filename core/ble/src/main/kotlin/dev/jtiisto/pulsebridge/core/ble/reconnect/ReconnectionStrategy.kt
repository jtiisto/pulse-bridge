package dev.jtiisto.pulsebridge.core.ble.reconnect

import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ReconnectionStrategy(
    private val config: ReconnectionConfig = ReconnectionConfig(),
) {
    private var attemptCount = 0

    val currentAttempt: Int get() = attemptCount

    val hasAttemptsRemaining: Boolean
        get() = attemptCount < config.maxAttempts

    fun nextDelay(): Duration {
        val delayMs = config.initialDelay.inWholeMilliseconds *
            Math.pow(config.multiplier, attemptCount.toDouble())
        val cappedMs = min(delayMs.toLong(), config.maxDelay.inWholeMilliseconds)
        attemptCount++
        return cappedMs.milliseconds
    }

    fun reset() {
        attemptCount = 0
    }
}
