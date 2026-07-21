package dev.jtiisto.pulsebridge.core.network

import dev.jtiisto.pulsebridge.core.network.dto.IntervalBatchDto
import dev.jtiisto.pulsebridge.core.network.dto.IntervalDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntervalApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun `health returns server status`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/v1/health", request.url.encodedPath)
            assertEquals("test", request.headers["X-Environment"])
            respond(
                content = """{"status":"ok","environment":"test","intervals_count":42}""",
                headers = jsonHeaders,
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val api = IntervalApi(client)
        val result = api.health("test")

        assertEquals("ok", result.status)
        assertEquals("test", result.environment)
        assertEquals(42, result.intervalsCount)
    }

    @Test
    fun `syncBatch sends intervals and returns response`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/v1/intervals/batch", request.url.encodedPath)
            assertEquals("production", request.headers["X-Environment"])
            respond(
                content = """{"accepted":5,"duplicates":0,"total_received":5}""",
                headers = jsonHeaders,
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
        }

        val api = IntervalApi(client)
        val batch = IntervalBatchDto(
            intervals = (1..5).map { i ->
                IntervalDto(
                    deviceId = "AA:BB",
                    timestampDevice = i.toLong() * 1000,
                    timestampPhone = i.toLong() * 1000 + 5,
                    heartRateBpm = 120,
                    rrIntervalMs = 800,
                    rrSequenceIndex = 0,
                    sensorType = "garmin_hrm",
                )
            },
        )

        val result = api.syncBatch(batch, "production")

        assertEquals(5, result.accepted)
        assertEquals(0, result.duplicates)
        assertEquals(5, result.totalReceived)
    }

    @Test
    fun `syncBatch handles partial duplicates`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"accepted":3,"duplicates":2,"total_received":5}""",
                headers = jsonHeaders,
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val api = IntervalApi(client)
        val batch = IntervalBatchDto(intervals = emptyList())
        val result = api.syncBatch(batch, "test")

        assertEquals(3, result.accepted)
        assertEquals(2, result.duplicates)
    }
}
