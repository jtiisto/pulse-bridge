package dev.jtiisto.wellnesssync.feature.capture.domain.model

sealed interface CaptureEvent {
    data class StartCapture(val deviceAddress: String, val deviceName: String?) : CaptureEvent
    data object StopCapture : CaptureEvent
    data object StartScan : CaptureEvent
    data object StopScan : CaptureEvent
    data object SyncNow : CaptureEvent
    data object RetryQuarantined : CaptureEvent
    data class RemoveKnownDevice(val address: String) : CaptureEvent
    data object DismissError : CaptureEvent
    data object PermissionsGranted : CaptureEvent

    // Polar events
    data class AddPolarDevice(val deviceId: String, val name: String) : CaptureEvent
    data class RemovePolarDevice(val deviceId: String) : CaptureEvent
    data class SyncPolarNow(val deviceId: String) : CaptureEvent
    data object StartPolarScan : CaptureEvent
    data object StopPolarScan : CaptureEvent
}
