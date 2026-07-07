package dev.jtiisto.wellnesssync.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

object HttpClientProvider {

    /**
     * @param engine test override; production callers omit it and get OkHttp
     */
    fun create(config: ServerConfig = ServerConfig(), engine: HttpClientEngine? = null): HttpClient {
        val commonConfig: HttpClientConfig<*>.() -> Unit = {
            // Surface non-2xx as typed ResponseExceptions instead of leaving
            // callers to trip over deserialization failures of the error body
            expectSuccess = true
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    }
                )
            }
            defaultRequest {
                url(config.baseUrl)
            }
        }

        if (engine != null) {
            return HttpClient(engine) { commonConfig() }
        }

        return HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(config.connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    readTimeout(config.requestTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    writeTimeout(config.requestTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                }
            }
            commonConfig()
        }
    }
}
