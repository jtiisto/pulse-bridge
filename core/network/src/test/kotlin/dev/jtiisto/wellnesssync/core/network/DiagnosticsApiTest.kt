package dev.jtiisto.wellnesssync.core.network

import dev.jtiisto.wellnesssync.core.network.dto.DiagnosticEntryDto
import dev.jtiisto.wellnesssync.core.network.dto.DiagnosticUploadDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticsApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun requestBody(request: HttpRequestData): String =
        (request.body as TextContent).text

    @Test
    fun `upload posts entries with environment header and parses response`() = runTest {
        var capturedBody = ""
        val engine = MockEngine { request ->
            assertEquals("/api/v1/diagnostics/upload", request.url.encodedPath)
            assertEquals("test", request.headers["X-Environment"])
            capturedBody = requestBody(request)
            respond(
                content = """{"stored":2,"file":"diag_test_123.jsonl"}""",
                headers = jsonHeaders,
            )
        }
        val api = DiagnosticsApi(HttpClientProvider.create(engine = engine))

        val result = api.upload(
            DiagnosticUploadDto(
                deviceInfo = "Pixel 9 / Android 15",
                entries = listOf(
                    DiagnosticEntryDto(1000L, "garmin", "connect() to AA:BB"),
                    DiagnosticEntryDto(2000L, "garmin", "onConnectionStateChange status=133 newState=0"),
                ),
            ),
            environment = "test",
        )

        assertEquals(2, result.stored)
        assertEquals("diag_test_123.jsonl", result.file)
        assertTrue(capturedBody.contains("\"timestamp_ms\":1000"))
        assertTrue(capturedBody.contains("status=133"))
        assertTrue(capturedBody.contains("\"device_info\":\"Pixel 9 / Android 15\""))
    }
}
