package dev.jtiisto.wellnesssync.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

object HttpClientProvider {

    fun create(config: ServerConfig = ServerConfig()): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(config.connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    readTimeout(config.requestTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    writeTimeout(config.requestTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                }
            }
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
    }
}
