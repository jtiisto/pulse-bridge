package dev.jtiisto.pulsebridge.feature.capture.domain

import dev.jtiisto.pulsebridge.feature.capture.domain.model.BeatPoint
import kotlin.math.ceil
import kotlin.math.floor

data class HrRange(val minBpm: Float, val maxBpm: Float) {
    val spanBpm: Float get() = maxBpm - minBpm
}

object TachogramScale {
    val DEFAULT_RANGE = HrRange(40f, 120f)
    const val STEP_BPM = 20f
    const val PADDING_BPM = 10f
    const val MIN_SPAN_BPM = 60f

    // How much larger than needed the current range may stay before shrinking —
    // prevents per-beat rescale jitter
    private const val SHRINK_SLACK_BPM = 40f

    fun computeYRange(points: List<BeatPoint>, current: HrRange?): HrRange {
        if (points.isEmpty()) return current ?: DEFAULT_RANGE

        val dataMin = points.minOf { it.hrBpm }
        val dataMax = points.maxOf { it.hrBpm }

        var lo = floor((dataMin - PADDING_BPM) / STEP_BPM) * STEP_BPM
        var hi = ceil((dataMax + PADDING_BPM) / STEP_BPM) * STEP_BPM
        if (lo < 0f) lo = 0f
        while (hi - lo < MIN_SPAN_BPM) {
            hi += STEP_BPM
            if (hi - lo < MIN_SPAN_BPM && lo >= STEP_BPM) lo -= STEP_BPM
        }
        val target = HrRange(lo, hi)

        val fitsCurrent = current != null &&
            dataMin >= current.minBpm && dataMax <= current.maxBpm &&
            current.spanBpm <= target.spanBpm + SHRINK_SLACK_BPM
        return if (fitsCurrent) current!! else target
    }
}
