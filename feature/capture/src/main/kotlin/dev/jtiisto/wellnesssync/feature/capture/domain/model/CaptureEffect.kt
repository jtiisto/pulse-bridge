package dev.jtiisto.wellnesssync.feature.capture.domain.model

sealed interface CaptureEffect {
    data class ShowError(val message: String) : CaptureEffect
}
