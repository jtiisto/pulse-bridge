package dev.jtiisto.wellnesssync.core.network

import dev.jtiisto.wellnesssync.core.network.dto.DiagnosticUploadDto
import dev.jtiisto.wellnesssync.core.network.dto.DiagnosticUploadResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class DiagnosticsApi(private val client: HttpClient) {

    suspend fun upload(payload: DiagnosticUploadDto, environment: String): DiagnosticUploadResponseDto {
        return client.post("/api/v1/diagnostics/upload") {
            contentType(ContentType.Application.Json)
            header("X-Environment", environment)
            setBody(payload)
        }.body()
    }
}
