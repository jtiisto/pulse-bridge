package dev.jtiisto.wellnesssync.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Exercises the REAL client configuration from HttpClientProvider — the
 * ad-hoc clients in IntervalApiTest don't cover it, which is how a missing
 * expectSuccess shipped unnoticed.
 */
class HttpClientProviderTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun `4xx response throws typed ClientRequestException`() = runTest {
        val engine = MockEngine {
            respondError(HttpStatusCode.UnprocessableEntity, """{"detail":"validation error"}""")
        }
        val api = IntervalApi(HttpClientProvider.create(engine = engine))

        val thrown = try {
            api.health("test")
            null
        } catch (e: ClientRequestException) {
            e
        }

        assertNotNull(thrown)
        assertEquals(422, thrown!!.response.status.value)
    }

    @Test
    fun `5xx response throws typed ServerResponseException`() = runTest {
        val engine = MockEngine {
            respondError(HttpStatusCode.InternalServerError)
        }
        val api = IntervalApi(HttpClientProvider.create(engine = engine))

        val thrown = try {
            api.health("test")
            null
        } catch (e: ServerResponseException) {
            e
        }

        assertNotNull(thrown)
        assertEquals(500, thrown!!.response.status.value)
    }

    @Test
    fun `2xx response parses normally`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"status":"ok","environment":"test","intervals_count":7}""",
                headers = jsonHeaders,
            )
        }
        val api = IntervalApi(HttpClientProvider.create(engine = engine))

        val result = api.health("test")

        assertEquals("ok", result.status)
        assertEquals(7, result.intervalsCount)
    }
}
