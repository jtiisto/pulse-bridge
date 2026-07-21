package dev.jtiisto.pulsebridge.feature.capture.domain

import dev.jtiisto.pulsebridge.core.ble.model.HeartRateSample
import dev.jtiisto.pulsebridge.core.ble.model.SensorPriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TachogramBufferTest {

    private var nowMs = 0L
    private val buffer = TachogramBuffer(clock = { nowMs })

    private fun sample(
        rrs: List<Int>,
        hr: Int = 70,
    ) = HeartRateSample(
        deviceId = "AA:BB",
        timestampDevice = nowMs,
        heartRateBpm = hr,
        rrIntervalsMs = rrs,
        sensorPriority = SensorPriority.GARMIN_ECG,
        sensorType = "garmin_hrm",
    )

    @Test
    fun `each RR interval becomes one point with instantaneous HR`() {
        nowMs = 10_000
        buffer.add(sample(rrs = listOf(800, 850)))

        val points = buffer.points()
        assertEquals(2, points.size)
        // Anchor: 10000 - 1650, beats at +800 and +850
        assertEquals(9_150L, points[0].timeMs)
        assertEquals(75f, points[0].hrBpm)
        assertEquals(10_000L, points[1].timeMs)
        assertEquals(60_000f / 850, points[1].hrBpm)
    }

    @Test
    fun `zero RR artifacts are skipped`() {
        nowMs = 10_000
        buffer.add(sample(rrs = listOf(800, 0, 850)))

        assertEquals(2, buffer.points().size)
        assertTrue(buffer.points().none { it.hrBpm.isInfinite() })
    }

    @Test
    fun `all-zero RR list produces no points`() {
        nowMs = 10_000
        buffer.add(sample(rrs = listOf(0, 0)))

        assertEquals(0, buffer.points().size)
    }

    @Test
    fun `HR-only notification falls back to reported bpm at arrival time`() {
        nowMs = 10_000
        buffer.add(sample(rrs = emptyList(), hr = 82))

        val points = buffer.points()
        assertEquals(1, points.size)
        assertEquals(10_000L, points[0].timeMs)
        assertEquals(82f, points[0].hrBpm)
    }

    @Test
    fun `beat clock accumulates across notifications without re-anchoring`() {
        nowMs = 1_000
        buffer.add(sample(rrs = listOf(1000)))
        nowMs = 2_000
        buffer.add(sample(rrs = listOf(1000)))

        val points = buffer.points()
        assertEquals(listOf(1_000L, 2_000L), points.map { it.timeMs })
    }

    @Test
    fun `beat clock re-anchors after a gap`() {
        nowMs = 1_000
        buffer.add(sample(rrs = listOf(1000)))
        nowMs = 10_000
        buffer.add(sample(rrs = listOf(800)))

        val points = buffer.points()
        // Gap > 2 s: second notification anchored to its own arrival time
        assertEquals(10_000L, points.last().timeMs)
    }

    @Test
    fun `points older than retention window are trimmed`() {
        nowMs = 1_000
        buffer.add(sample(rrs = listOf(1000)))
        nowMs = 20_000
        buffer.add(sample(rrs = listOf(800)))

        val points = buffer.points()
        assertEquals(1, points.size)
        assertEquals(20_000L, points[0].timeMs)
    }

    @Test
    fun `timestamps stay strictly increasing even when the clock jumps backwards`() {
        nowMs = 10_000
        buffer.add(sample(rrs = listOf(1000)))
        nowMs = 8_000 // clock jitter — beat clock is now ahead of "now"
        buffer.add(sample(rrs = listOf(100)))

        val times = buffer.points().map { it.timeMs }
        assertTrue(times.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `reset clears points and beat clock`() {
        nowMs = 10_000
        buffer.add(sample(rrs = listOf(800)))
        buffer.reset()

        assertEquals(0, buffer.points().size)

        // After reset the next sample re-anchors like a first sample
        nowMs = 20_000
        buffer.add(sample(rrs = listOf(800)))
        assertEquals(20_000L, buffer.points().single().timeMs)
    }
}
