package dev.jtiisto.wellnesssync.feature.capture.domain.model

/**
 * One beat on the live tachogram: instantaneous HR derived from a single
 * RR interval, positioned on the elapsed-realtime clock.
 */
data class BeatPoint(
    val timeMs: Long,
    val hrBpm: Float,
)
