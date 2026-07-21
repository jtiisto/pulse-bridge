package dev.jtiisto.pulsebridge.feature.capture.domain

import dev.jtiisto.pulsebridge.core.ble.model.HeartRateSample
import dev.jtiisto.pulsebridge.core.ble.model.SensorPriority
import dev.jtiisto.pulsebridge.feature.capture.domain.model.SignalQualityLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignalQualityTrackerTest {

    private var nowMs = 0L
    private val tracker = SignalQualityTracker(clock = { nowMs })

    private fun sample(
        rrs: List<Int>,
        hr: Int = 75,
        gap: Boolean = false,
    ) = HeartRateSample(
        deviceId = "AA:BB",
        timestampDevice = nowMs,
        heartRateBpm = hr,
        rrIntervalsMs = rrs,
        sensorPriority = SensorPriority.GARMIN_ECG,
        sensorType = "garmin_hrm",
        isGapBefore = gap,
    )

    /** Feed a clean beat every ~rr ms for the given wall-clock duration. */
    private fun feedClean(durationMs: Long, rr: Int = 800) {
        val end = nowMs + durationMs
        while (nowMs < end) {
            nowMs += rr
            tracker.add(sample(listOf(rr)))
        }
    }

    @Test
    fun `measuring before enough data`() {
        nowMs = 1000
        tracker.add(sample(listOf(800)))
        assertEquals(SignalQualityLevel.MEASURING, tracker.quality().level)
    }

    @Test
    fun `clean stream reads GOOD with high coverage`() {
        feedClean(60_000)
        val q = tracker.quality()
        assertEquals(SignalQualityLevel.GOOD, q.level)
        assertTrue(q.rrCoveragePercent >= 95)
    }

    @Test
    fun `heavy RR dropout degrades below GOOD`() {
        // Every other notification is HR-only (no RR) — ~50% coverage
        val end = 60_000L
        while (nowMs < end) {
            nowMs += 800
            tracker.add(sample(listOf(800)))
            nowMs += 800
            tracker.add(sample(emptyList())) // HR-only, no RR reported
        }
        val q = tracker.quality()
        assertTrue(q.level == SignalQualityLevel.POOR || q.level == SignalQualityLevel.FAIR)
        assertTrue(q.rrCoveragePercent < 95)
    }

    @Test
    fun `recent gap forces POOR`() {
        feedClean(60_000)
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
        nowMs += 800
        tracker.add(sample(listOf(800), gap = true))
        assertEquals(SignalQualityLevel.POOR, tracker.quality().level)
    }

    @Test
    fun `ectopic burst raises artifact fraction`() {
        feedClean(40_000)
        // Inject a run of wildly varying RRs (ectopics)
        repeat(60) {
            nowMs += 800
            val rr = if (it % 2 == 0) 400 else 1400
            tracker.add(sample(listOf(rr)))
        }
        assertTrue(tracker.quality().level != SignalQualityLevel.GOOD)
    }

    @Test
    fun `a break mid-window drops coverage below GOOD (continuity)`() {
        // Clean, one HR-only break, then clean again — offline splits this into
        // two runs and won't trust it; the longest run covers only ~half
        feedClean(40_000)
        nowMs += 800
        tracker.add(sample(emptyList())) // HR-only break in the middle
        feedClean(40_000)
        val q = tracker.quality()
        assertTrue(q.level != SignalQualityLevel.GOOD)
    }

    @Test
    fun `a gap keeps the window POOR well past 10 seconds`() {
        feedClean(30_000)
        nowMs += 800
        tracker.add(sample(listOf(800), gap = true))
        // 30 s later the gap is still inside the 120 s window -> still POOR,
        // matching the offline "any gap in window = untrusted" rule
        feedClean(30_000)
        assertEquals(SignalQualityLevel.POOR, tracker.quality().level)
    }

    @Test
    fun `compressed timeline above 105 percent coverage is not GOOD`() {
        // Timestamps advance at half the RR -> summed RR ~200% of wall clock
        val end = 60_000L
        val start = nowMs
        while (nowMs < start + end) {
            nowMs += 400 // half of the 800 ms RR
            tracker.add(sample(listOf(800)))
        }
        val q = tracker.quality()
        assertTrue(q.level != SignalQualityLevel.GOOD)
    }

    @Test
    fun `bundled multi-RR notifications are not misread as omissions`() {
        // One 1000 ms notification carrying two 500 ms RRs, repeatedly — a
        // naive receipt-time check would flag every packet as an omission
        val end = 60_000L
        val start = nowMs
        while (nowMs < start + end) {
            nowMs += 1000
            tracker.add(sample(listOf(500, 500), hr = 120))
        }
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
    }

    @Test
    fun `prolonged silence reads POOR not MEASURING`() {
        feedClean(60_000)
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
        // Sensor goes silent long enough to trim the whole window — we HAD a
        // signal, so this is a degraded outage, not initial collection
        nowMs += 200_000
        assertEquals(SignalQualityLevel.POOR, tracker.quality().level)
    }

    @Test
    fun `timestamp-detected omission breaks the run without a gap flag`() {
        feedClean(40_000)
        // Skip a beat: advance the clock ~2 RR before the next beat, no gap flag
        nowMs += 1700 // > 1.5 * 800
        tracker.add(sample(listOf(800)))
        feedClean(40_000)
        // Offline would split the run at the omission -> not a full clean window
        assertTrue(tracker.quality().level != SignalQualityLevel.GOOD)
    }

    @Test
    fun `isolated long ectopic is corrected and does not tank coverage`() {
        feedClean(40_000)
        // One spurious 8000 ms RR — offline substitutes a neighbour before
        // summing coverage, so live must not let it inflate coverage to POOR
        nowMs += 800
        tracker.add(sample(listOf(8000)))
        feedClean(40_000)
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
    }

    @Test
    fun `silence degrades a previously GOOD signal`() {
        feedClean(60_000)
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
        // No new beats; the clock advances (as the ViewModel tick would) —
        // the covered run shrinks relative to the growing window
        nowMs += 90_000
        assertTrue(tracker.quality().level != SignalQualityLevel.GOOD)
    }

    @Test
    fun `reset returns to MEASURING`() {
        feedClean(60_000)
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
        tracker.reset()
        assertEquals(SignalQualityLevel.MEASURING, tracker.quality().level)
    }

    @Test
    fun `old beats are trimmed out of the window`() {
        feedClean(60_000)
        // Jump forward well past the window with no new data, then add a few
        nowMs += 300_000
        repeat(25) {
            nowMs += 800
            tracker.add(sample(listOf(800)))
        }
        // Only the recent clean beats remain -> still GOOD, not skewed by old data
        assertEquals(SignalQualityLevel.GOOD, tracker.quality().level)
    }
}
