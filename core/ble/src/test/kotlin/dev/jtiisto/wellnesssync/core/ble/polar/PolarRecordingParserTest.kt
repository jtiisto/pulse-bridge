package dev.jtiisto.wellnesssync.core.ble.polar

import com.polar.sdk.api.model.PolarAccelerometerData
import com.polar.sdk.api.model.PolarPpiData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.sqrt

class PolarRecordingParserTest {

    private val parser = PolarRecordingParser()
    private val startTime = LocalDateTime.of(2026, 3, 23, 10, 0, 0)
    private val baseEpochMs = startTime.toInstant(ZoneOffset.UTC).toEpochMilli()
    private val phoneTimestamp = System.currentTimeMillis()
    private val deviceId = "1A2B3C4D"
    private val sessionId = "rec_2026-03-23_001"

    // --- PPI parsing tests ---

    @Test
    fun `parsePpi - empty data returns empty result`() {
        val data = PolarPpiData(samples = emptyList())
        val result = parser.parsePpi(data, startTime, deviceId, sessionId, phoneTimestamp)

        assertEquals(0, result.intervals.size)
        assertEquals(0, result.gapCount)
    }

    @Test
    fun `parsePpi - single sample produces one interval`() {
        val data = PolarPpiData(
            samples = listOf(
                createPpiSample(ppi = 800, hr = 75)
            )
        )
        val result = parser.parsePpi(data, startTime, deviceId, sessionId, phoneTimestamp)

        assertEquals(1, result.intervals.size)
        val interval = result.intervals[0]
        assertEquals(deviceId, interval.deviceId)
        assertEquals(baseEpochMs, interval.timestampDevice)
        assertEquals(75, interval.heartRateBpm)
        assertEquals(800, interval.rrIntervalMs)
        assertEquals(0, interval.rrSequenceIndex)
        assertEquals("polar_pvs", interval.sensorType)
        assertEquals(sessionId, interval.sessionId)
        assertFalse(interval.isGap)
        assertFalse(interval.isSynced)
    }

    @Test
    fun `parsePpi - timestamps offset by cumulative PPI`() {
        val data = PolarPpiData(
            samples = listOf(
                createPpiSample(ppi = 800, hr = 75),
                createPpiSample(ppi = 850, hr = 71),
                createPpiSample(ppi = 780, hr = 77),
            )
        )
        val result = parser.parsePpi(data, startTime, deviceId, sessionId, phoneTimestamp)

        assertEquals(3, result.intervals.size)
        assertEquals(baseEpochMs, result.intervals[0].timestampDevice)
        assertEquals(baseEpochMs + 800, result.intervals[1].timestampDevice)
        assertEquals(baseEpochMs + 800 + 850, result.intervals[2].timestampDevice)
    }

    @Test
    fun `parsePpi - zero PPI samples do not repeat timestamps`() {
        val data = PolarPpiData(
            samples = listOf(
                createPpiSample(ppi = 800, hr = 75),
                createPpiSample(ppi = 0, hr = 75), // known zero-PPI artifact
                createPpiSample(ppi = 850, hr = 71),
            )
        )
        val result = parser.parsePpi(data, startTime, deviceId, sessionId, phoneTimestamp)

        assertEquals(3, result.intervals.size)
        assertEquals(baseEpochMs, result.intervals[0].timestampDevice)
        assertEquals(baseEpochMs + 800, result.intervals[1].timestampDevice)
        // Third sample's candidate (base + 800) collides with the zero-PPI row — bumped by 1
        assertEquals(baseEpochMs + 801, result.intervals[2].timestampDevice)

        val timestamps = result.intervals.map { it.timestampDevice }
        assertEquals(timestamps.size, timestamps.distinct().size)
    }

    @Test
    fun `parsePpi - consecutive zero PPI samples all get unique timestamps`() {
        val data = PolarPpiData(
            samples = listOf(
                createPpiSample(ppi = 800, hr = 75),
                createPpiSample(ppi = 0, hr = 75),
                createPpiSample(ppi = 0, hr = 75),
                createPpiSample(ppi = 0, hr = 75),
            )
        )
        val result = parser.parsePpi(data, startTime, deviceId, sessionId, phoneTimestamp)

        val timestamps = result.intervals.map { it.timestampDevice }
        assertEquals(timestamps.size, timestamps.distinct().size)
        assertTrue(timestamps.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `parsePpi - sequence index increments`() {
        val data = PolarPpiData(
            samples = listOf(
                createPpiSample(ppi = 800, hr = 75),
                createPpiSample(ppi = 850, hr = 71),
            )
        )
        val result = parser.parsePpi(data, startTime, deviceId, sessionId, phoneTimestamp)

        assertEquals(0, result.intervals[0].rrSequenceIndex)
        assertEquals(1, result.intervals[1].rrSequenceIndex)
    }

    @Test
    fun `parsePpi - detects gap when PPI exceeds threshold`() {
        val data = PolarPpiData(
            samples = listOf(
                createPpiSample(ppi = 800, hr = 75),
                createPpiSample(ppi = 5000, hr = 60),  // gap: 5s > 3s threshold
                createPpiSample(ppi = 850, hr = 71),
            )
        )
        val result = parser.parsePpi(data, startTime, deviceId, sessionId, phoneTimestamp)

        assertEquals(1, result.gapCount)
        assertFalse(result.intervals[0].isGap) // first sample is never a gap
        assertTrue(result.intervals[1].isGap)   // 5000ms > 3000ms threshold
        assertFalse(result.intervals[2].isGap) // 850ms < threshold
    }

    @Test
    fun `parsePpi - first sample is never a gap`() {
        val data = PolarPpiData(
            samples = listOf(
                createPpiSample(ppi = 10000, hr = 60), // would be a gap if not first
            )
        )
        val result = parser.parsePpi(data, startTime, deviceId, sessionId, phoneTimestamp)

        assertFalse(result.intervals[0].isGap)
        assertEquals(0, result.gapCount)
    }

    @Test
    fun `parsePpi - custom gap threshold`() {
        val strictParser = PolarRecordingParser(gapThresholdMs = 1000)
        val data = PolarPpiData(
            samples = listOf(
                createPpiSample(ppi = 800, hr = 75),
                createPpiSample(ppi = 1500, hr = 60), // gap with strict threshold
            )
        )
        val result = strictParser.parsePpi(data, startTime, deviceId, sessionId, phoneTimestamp)

        assertTrue(result.intervals[1].isGap)
        assertEquals(1, result.gapCount)
    }

    @Test
    fun `parsePpi - phone timestamp set on all intervals`() {
        val data = PolarPpiData(
            samples = listOf(
                createPpiSample(ppi = 800, hr = 75),
                createPpiSample(ppi = 850, hr = 71),
            )
        )
        val result = parser.parsePpi(data, startTime, deviceId, sessionId, phoneTimestamp)

        assertTrue(result.intervals.all { it.timestampPhone == phoneTimestamp })
    }

    // --- ACC parsing tests ---

    @Test
    fun `parseAccSummaries - empty data returns empty list`() {
        val data = PolarAccelerometerData(samples = emptyList())
        val result = parser.parseAccSummaries(data, startTime, deviceId, sessionId)

        assertEquals(0, result.size)
    }

    @Test
    fun `parseAccSummaries - samples within one minute produce one summary`() {
        // Create 100 samples all within the same minute
        val baseNs = 0L
        val samples = (0 until 100).map { i ->
            PolarAccelerometerData.PolarAccelerometerDataSample(
                timeStamp = baseNs + i * 19_230_769L, // ~52Hz spacing in ns
                x = 0,
                y = 0,
                z = 1000, // 1g downward
            )
        }
        val data = PolarAccelerometerData(samples = samples)
        val result = parser.parseAccSummaries(data, startTime, deviceId, sessionId)

        assertEquals(1, result.size)
        assertEquals(100, result[0].sampleCount)
        assertEquals(1.0, result[0].magnitudeMean, 0.001) // sqrt(0+0+1000²)/1000 = 1.0
        assertEquals(0.0, result[0].magnitudeStd, 0.001)  // all same → std ≈ 0
        assertEquals(1.0, result[0].magnitudeMax, 0.001)
        assertEquals("polar_pvs", result[0].sensorType)
        assertEquals(sessionId, result[0].sessionId)
    }

    @Test
    fun `parseAccSummaries - samples spanning two minutes produce two summaries`() {
        val baseNs = 0L
        val nsPerMinute = 60_000_000_000L
        val samples = listOf(
            // First minute: 2 samples
            PolarAccelerometerData.PolarAccelerometerDataSample(
                timeStamp = baseNs,
                x = 0, y = 0, z = 1000,
            ),
            PolarAccelerometerData.PolarAccelerometerDataSample(
                timeStamp = baseNs + 500_000_000L, // 0.5s
                x = 0, y = 0, z = 1000,
            ),
            // Second minute: 1 sample
            PolarAccelerometerData.PolarAccelerometerDataSample(
                timeStamp = baseNs + nsPerMinute, // exactly at minute boundary
                x = 0, y = 0, z = 2000, // different magnitude
            ),
        )
        val data = PolarAccelerometerData(samples = samples)
        val result = parser.parseAccSummaries(data, startTime, deviceId, sessionId)

        assertEquals(2, result.size)
        assertEquals(2, result[0].sampleCount)
        assertEquals(1, result[1].sampleCount)
        assertEquals(2.0, result[1].magnitudeMean, 0.001) // sqrt(0+0+2000²)/1000
    }

    @Test
    fun `parseAccSummaries - magnitude computed correctly for 3-axis data`() {
        val samples = listOf(
            PolarAccelerometerData.PolarAccelerometerDataSample(
                timeStamp = 0L,
                x = 500, y = 500, z = 707, // ~1g total
            ),
        )
        val data = PolarAccelerometerData(samples = samples)
        val result = parser.parseAccSummaries(data, startTime, deviceId, sessionId)

        // sqrt(500² + 500² + 707²) / 1000 ≈ sqrt(250000 + 250000 + 499849) / 1000 ≈ sqrt(999849) / 1000 ≈ 0.9999
        val expectedMag = sqrt(500.0 * 500 + 500.0 * 500 + 707.0 * 707) / 1000.0
        assertEquals(expectedMag, result[0].magnitudeMean, 0.001)
    }

    @Test
    fun `parseAccSummaries - std deviation computed correctly`() {
        val samples = listOf(
            PolarAccelerometerData.PolarAccelerometerDataSample(
                timeStamp = 0L,
                x = 0, y = 0, z = 1000, // magnitude = 1.0
            ),
            PolarAccelerometerData.PolarAccelerometerDataSample(
                timeStamp = 1_000_000L, // 1ms later, same window
                x = 0, y = 0, z = 3000, // magnitude = 3.0
            ),
        )
        val data = PolarAccelerometerData(samples = samples)
        val result = parser.parseAccSummaries(data, startTime, deviceId, sessionId)

        val mean = (1.0 + 3.0) / 2 // 2.0
        val variance = ((1.0 - mean) * (1.0 - mean) + (3.0 - mean) * (3.0 - mean)) / 1 // sample variance with n-1
        val expectedStd = sqrt(variance) // sqrt(2.0) ≈ 1.414
        assertEquals(mean, result[0].magnitudeMean, 0.001)
        assertEquals(expectedStd, result[0].magnitudeStd, 0.001)
        assertEquals(3.0, result[0].magnitudeMax, 0.001)
    }

    @Test
    fun `parseAccSummaries - single sample in window has zero std`() {
        val samples = listOf(
            PolarAccelerometerData.PolarAccelerometerDataSample(
                timeStamp = 0L,
                x = 0, y = 0, z = 1000,
            ),
        )
        val data = PolarAccelerometerData(samples = samples)
        val result = parser.parseAccSummaries(data, startTime, deviceId, sessionId)

        assertEquals(0.0, result[0].magnitudeStd, 0.001)
    }

    @Test
    fun `parseAccSummaries - window starts are aligned to minute boundaries`() {
        val baseNs = 0L
        val samples = listOf(
            PolarAccelerometerData.PolarAccelerometerDataSample(
                timeStamp = baseNs + 30_000_000_000L, // 30s into the minute
                x = 0, y = 0, z = 1000,
            ),
        )
        val data = PolarAccelerometerData(samples = samples)
        val result = parser.parseAccSummaries(data, startTime, deviceId, sessionId)

        // Window start should be aligned to start of the minute
        val expectedWindowStart = baseEpochMs + 30_000L
        val alignedWindowStart = expectedWindowStart - (expectedWindowStart % 60_000L)
        assertEquals(alignedWindowStart, result[0].windowStart)
    }

    @Test
    fun `parseAccSummaries - results sorted by window start`() {
        val baseNs = 0L
        val nsPerMinute = 60_000_000_000L
        val samples = listOf(
            // Third minute
            PolarAccelerometerData.PolarAccelerometerDataSample(
                timeStamp = baseNs + 2 * nsPerMinute,
                x = 0, y = 0, z = 1000,
            ),
            // First minute
            PolarAccelerometerData.PolarAccelerometerDataSample(
                timeStamp = baseNs,
                x = 0, y = 0, z = 1000,
            ),
        )
        val data = PolarAccelerometerData(samples = samples)
        val result = parser.parseAccSummaries(data, startTime, deviceId, sessionId)

        assertTrue(result.size >= 2)
        assertTrue(result.zipWithNext().all { (a, b) -> a.windowStart <= b.windowStart })
    }

    @Test
    fun `parseAccSummaries - not synced by default`() {
        val samples = listOf(
            PolarAccelerometerData.PolarAccelerometerDataSample(
                timeStamp = 0L,
                x = 0, y = 0, z = 1000,
            ),
        )
        val data = PolarAccelerometerData(samples = samples)
        val result = parser.parseAccSummaries(data, startTime, deviceId, sessionId)

        assertFalse(result[0].isSynced)
    }

    // --- Helpers ---

    private var sampleTimestamp = 0UL

    private fun createPpiSample(
        ppi: Int,
        hr: Int,
        errorEstimate: Int = 5,
        blockerBit: Boolean = false,
        skinContactStatus: Boolean = true,
        skinContactSupported: Boolean = true,
    ): PolarPpiData.PolarPpiSample {
        val sample = PolarPpiData.PolarPpiSample(
            ppi = ppi,
            errorEstimate = errorEstimate,
            hr = hr,
            blockerBit = blockerBit,
            skinContactStatus = skinContactStatus,
            skinContactSupported = skinContactSupported,
            timeStamp = sampleTimestamp,
        )
        sampleTimestamp += ppi.toULong()
        return sample
    }
}
