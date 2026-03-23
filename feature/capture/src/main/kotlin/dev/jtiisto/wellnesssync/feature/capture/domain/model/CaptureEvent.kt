package dev.jtiisto.wellnesssync.feature.capture.domain.model

sealed interface CaptureEvent {
    data class StartCapture(val deviceAddress: String, val deviceName: String?) : CaptureEvent
    data object StopCapture : CaptureEvent
    data object StartScan : CaptureEvent
    data object StopScan : CaptureEvent
    data object SyncNow : CaptureEvent
    data class RemoveKnownDevice(val address: String) : CaptureEvent
    data object DismissError : CaptureEvent
    data object PermissionsGranted : CaptureEvent
}
