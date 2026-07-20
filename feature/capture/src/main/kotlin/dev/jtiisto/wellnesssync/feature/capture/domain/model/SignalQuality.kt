package dev.jtiisto.wellnesssync.feature.capture.domain.model

enum class SignalQualityLevel {
    MEASURING,  // not enough data yet
    POOR,       // not usable for DFA
    FAIR,       // marginal
    GOOD,       // DFA-ready
}

/**
 * Live read of whether the incoming RR stream is clean enough for DFA α1.
 * Thresholds mirror the offline analysis module, so GOOD here means the
 * offline analysis will actually trust the data.
 */
data class SignalQuality(
    val level: SignalQualityLevel = SignalQualityLevel.MEASURING,
    val rrCoveragePercent: Int = 0,
)
