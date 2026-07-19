package dev.jtiisto.wellnesssync.core.network

import dev.jtiisto.wellnesssync.core.network.dto.AccelerometerBatchDto
import dev.jtiisto.wellnesssync.core.network.dto.AccelerometerSummaryDto
import dev.jtiisto.wellnesssync.core.network.dto.DiagnosticEntryDto
import dev.jtiisto.wellnesssync.core.network.dto.DiagnosticUploadDto
import dev.jtiisto.wellnesssync.core.network.dto.IntervalBatchDto
import dev.jtiisto.wellnesssync.core.network.dto.IntervalDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Contract tests against the shared golden payloads in testdata/golden/.
 * The server's pytest suite feeds the SAME files into the real endpoints, so
 * any drift in the wire format breaks a test on whichever side changed
 * instead of surfacing as a runtime 422 on the phone.
 */
class GoldenPayloadTest {

    private fun goldenFile(name: String): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "testdata/golden").isDirectory) {
            dir = dir.parentFile
                ?: error("testdata/golden not found above ${System.getProperty("user.dir")}")
        }
        return File(dir, "testdata/golden/$name")
    }

    private fun assertMatchesGolden(name: String, actual: JsonElement) {
        val golden = ApiJson.parseToJsonElement(goldenFile(name).readText())
        assertEquals(golden, actual)
    }

    @Test
    fun `interval batch serializes exactly as the golden contract`() {
        val batch = IntervalBatchDto(
            intervals = listOf(
                IntervalDto(
                    deviceId = "GOLDEN:AA",
                    timestampDevice = 1_700_000_000_000,
                    timestampPhone = 1_700_000_000_050,
                    heartRateBpm = 72,
                    rrIntervalMs = 833,
                    rrSequenceIndex = 0,
                    isGap = false,
                    windowLabel = null,
                    sensorType = "garmin_hrm",
                    sessionId = "golden-session-1",
                ),
                // Zero-RR sensor artifact — must survive the wire untouched
                IntervalDto(
                    deviceId = "GOLDEN:AA",
                    timestampDevice = 1_700_000_000_833,
                    timestampPhone = 1_700_000_000_900,
                    heartRateBpm = 72,
                    rrIntervalMs = 0,
                    rrSequenceIndex = 1,
                    isGap = false,
                    windowLabel = null,
                    sensorType = "garmin_hrm",
                    sessionId = "golden-session-1",
                ),
                IntervalDto(
                    deviceId = "GOLDEN:AA",
                    timestampDevice = 1_700_000_001_600,
                    timestampPhone = 1_700_000_001_700,
                    heartRateBpm = 68,
                    rrIntervalMs = 900,
                    rrSequenceIndex = 0,
                    isGap = true,
                    windowLabel = "w1",
                    sensorType = "polar_pvs",
                    sessionId = null,
                ),
            ),
        )

        assertMatchesGolden("interval_batch.json", ApiJson.encodeToJsonElement(batch))
    }

    @Test
    fun `accelerometer batch serializes exactly as the golden contract`() {
        val batch = AccelerometerBatchDto(
            summaries = listOf(
                AccelerometerSummaryDto(
                    deviceId = "GOLDEN:PVS",
                    windowStart = 1_700_000_000_000,
                    magnitudeMean = 1.012,
                    magnitudeStd = 0.05,
                    magnitudeMax = 1.4,
                    sampleCount = 120,
                    sensorType = "polar_pvs",
                    sessionId = "golden-rec-1",
                ),
                AccelerometerSummaryDto(
                    deviceId = "GOLDEN:PVS",
                    windowStart = 1_700_000_060_000,
                    magnitudeMean = 0.98,
                    magnitudeStd = 0.02,
                    magnitudeMax = 1.1,
                    sampleCount = 118,
                    sensorType = "polar_pvs",
                    sessionId = null,
                ),
            ),
        )

        assertMatchesGolden("accelerometer_batch.json", ApiJson.encodeToJsonElement(batch))
    }

    @Test
    fun `diagnostics upload serializes exactly as the golden contract`() {
        val upload = DiagnosticUploadDto(
            deviceInfo = "Golden Test Device / Android 15",
            entries = listOf(
                DiagnosticEntryDto(
                    timestampMs = 1_700_000_000_000,
                    tag = "garmin",
                    message = "onConnectionStateChange status=133 newState=0",
                ),
                DiagnosticEntryDto(
                    timestampMs = 1_700_000_000_500,
                    tag = "scan",
                    message = "found HRM-Pro:123456 AA:BB:CC:DD:EE:FF rssi=-60 hrmService=true",
                ),
            ),
        )

        assertMatchesGolden("diagnostics_upload.json", ApiJson.encodeToJsonElement(upload))
    }
}
