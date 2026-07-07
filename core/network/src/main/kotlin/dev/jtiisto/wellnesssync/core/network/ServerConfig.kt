package dev.jtiisto.wellnesssync.core.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ServerConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val connectTimeout: Duration = 10.seconds,
    val requestTimeout: Duration = 30.seconds,
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://pop-os:8000"
    }
}
