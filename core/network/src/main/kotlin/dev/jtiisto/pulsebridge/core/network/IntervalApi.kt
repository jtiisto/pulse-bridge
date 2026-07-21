package dev.jtiisto.pulsebridge.core.network

import dev.jtiisto.pulsebridge.core.network.dto.HealthResponseDto
import dev.jtiisto.pulsebridge.core.network.dto.IntervalBatchDto
import dev.jtiisto.pulsebridge.core.network.dto.SyncResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class IntervalApi(private val client: HttpClient) {

    suspend fun health(environment: String): HealthResponseDto {
        return client.get("/api/v1/health") {
            header("X-Environment", environment)
        }.body()
    }

    suspend fun syncBatch(batch: IntervalBatchDto, environment: String): SyncResponseDto {
        return client.post("/api/v1/intervals/batch") {
            contentType(ContentType.Application.Json)
            header("X-Environment", environment)
            setBody(batch)
        }.body()
    }
}
