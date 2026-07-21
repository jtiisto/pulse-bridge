package dev.jtiisto.pulsebridge.feature.capture.domain

import dev.jtiisto.pulsebridge.core.ble.model.HeartRateSample
import dev.jtiisto.pulsebridge.feature.capture.domain.model.BeatPoint

/**
 * Turns incoming [HeartRateSample]s into drawable tachogram points.
 *
 * Beat times come from a cumulative beat clock: each RR interval advances the
 * clock by its own duration, so rhythm spacing on screen is faithful to the
 * data rather than to BLE notification arrival. The clock re-anchors to
 * arrival time when it drifts more than [REANCHOR_THRESHOLD_MS] (first
 * sample, BLE stall, connection gap).
 *
 * @param clock elapsed-realtime source in ms, injected for testability
 */
class TachogramBuffer(
    private val clock: () -> Long,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
) {
    companion object {
        // 10 s visible window + margin so the entering segment is drawable
        const val DEFAULT_RETENTION_MS = 12_000L
        const val REANCHOR_THRESHOLD_MS = 2_000L
    }

    private val points = ArrayDeque<BeatPoint>()
    private var beatClockMs: Long? = null

    fun add(sample: HeartRateSample) {
        val now = clock()
        val rrs = sample.rrIntervalsMs.filter { it > 0 }

        if (sample.rrIntervalsMs.isEmpty()) {
            // HR-only notification — no rhythm info, plot the reported bpm at arrival
            if (sample.heartRateBpm > 0) {
                append(now, sample.heartRateBpm.toFloat())
                beatClockMs = now
            }
        } else if (rrs.isNotEmpty()) {
            // rr <= 0 entries are known sensor artifacts and carry no beat
            val durationMs = rrs.sum().toLong()
            var beatClock = beatClockMs ?: (now - durationMs)
            if (now - beatClock > REANCHOR_THRESHOLD_MS || beatClock > now) {
                beatClock = now - durationMs
            }
            for (rr in rrs) {
                beatClock += rr
                append(beatClock, 60_000f / rr)
            }
            beatClockMs = beatClock
        }

        trim(now)
    }

    fun reset() {
        points.clear()
        beatClockMs = null
    }

    /** Immutable snapshot, oldest first, strictly increasing timeMs. */
    fun points(): List<BeatPoint> = points.toList()

    private fun append(timeMs: Long, hrBpm: Float) {
        // Keep x strictly increasing so the step path never folds back on itself
        val t = points.lastOrNull()?.let { maxOf(timeMs, it.timeMs + 1) } ?: timeMs
        points.addLast(BeatPoint(t, hrBpm))
    }

    private fun trim(now: Long) {
        while (points.isNotEmpty() && points.first().timeMs < now - retentionMs) {
            points.removeFirst()
        }
    }
}
