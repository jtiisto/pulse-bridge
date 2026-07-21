package dev.jtiisto.pulsebridge.feature.capture.domain

import dev.jtiisto.pulsebridge.feature.capture.domain.model.BeatPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TachogramScaleTest {

    private fun points(vararg hrs: Float) =
        hrs.mapIndexed { i, hr -> BeatPoint(timeMs = i * 1000L, hrBpm = hr) }

    @Test
    fun `empty points return default range`() {
        assertEquals(TachogramScale.DEFAULT_RANGE, TachogramScale.computeYRange(emptyList(), null))
    }

    @Test
    fun `empty points keep the current range`() {
        val current = HrRange(80f, 160f)
        assertSame(current, TachogramScale.computeYRange(emptyList(), current))
    }

    @Test
    fun `range is padded, rounded to 20 bpm, and at least 60 bpm wide`() {
        val range = TachogramScale.computeYRange(points(70f, 75f), null)

        assertEquals(60f, range.minBpm)
        assertEquals(120f, range.maxBpm)
    }

    @Test
    fun `range never goes below zero`() {
        val range = TachogramScale.computeYRange(points(15f), null)

        assertEquals(0f, range.minBpm)
        assertTrue(range.spanBpm >= TachogramScale.MIN_SPAN_BPM)
    }

    @Test
    fun `current range is kept while data still fits (hysteresis)`() {
        val current = HrRange(60f, 120f)
        val range = TachogramScale.computeYRange(points(70f, 110f), current)

        assertSame(current, range)
    }

    @Test
    fun `range expands when data exceeds it`() {
        val current = HrRange(60f, 120f)
        val range = TachogramScale.computeYRange(points(70f, 130f), current)

        assertEquals(60f, range.minBpm)
        assertEquals(140f, range.maxBpm)
    }

    @Test
    fun `range shrinks when it is far larger than the data needs`() {
        val current = HrRange(40f, 200f)
        val range = TachogramScale.computeYRange(points(70f, 75f), current)

        assertEquals(60f, range.minBpm)
        assertEquals(120f, range.maxBpm)
    }
}
