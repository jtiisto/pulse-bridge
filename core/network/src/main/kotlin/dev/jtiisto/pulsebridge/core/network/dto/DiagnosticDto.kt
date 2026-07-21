package dev.jtiisto.pulsebridge.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticEntryDto(
    @SerialName("timestamp_ms") val timestampMs: Long,
    val tag: String,
    val message: String,
)

@Serializable
data class DiagnosticUploadDto(
    @SerialName("device_info") val deviceInfo: String? = null,
    val entries: List<DiagnosticEntryDto>,
)

@Serializable
data class DiagnosticUploadResponseDto(
    val stored: Int,
    val file: String,
)
