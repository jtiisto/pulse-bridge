package dev.jtiisto.pulsebridge.core.network

import dev.jtiisto.pulsebridge.core.network.dto.AccelerometerBatchDto
import dev.jtiisto.pulsebridge.core.network.dto.SyncResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class AccelerometerApi(private val client: HttpClient) {

    suspend fun syncBatch(batch: AccelerometerBatchDto, environment: String): SyncResponseDto {
        return client.post("/api/v1/accelerometer/batch") {
            contentType(ContentType.Application.Json)
            header("X-Environment", environment)
            setBody(batch)
        }.body()
    }
}
