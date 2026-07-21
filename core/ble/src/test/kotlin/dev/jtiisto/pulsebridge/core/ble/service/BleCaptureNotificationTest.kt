package dev.jtiisto.pulsebridge.core.ble.service

import dev.jtiisto.pulsebridge.core.ble.model.ConnectionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BleCaptureNotificationTest {

    @Test
    fun `notification channel ID is correct`() {
        assertEquals("ble_capture", BleCaptureNotification.CHANNEL_ID)
    }

    @Test
    fun `notification ID is stable`() {
        assertEquals(1, BleCaptureNotification.NOTIFICATION_ID)
    }
}
