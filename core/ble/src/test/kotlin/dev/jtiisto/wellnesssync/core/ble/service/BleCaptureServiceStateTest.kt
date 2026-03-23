package dev.jtiisto.wellnesssync.core.ble.service

import dev.jtiisto.wellnesssync.core.ble.model.ConnectionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BleCaptureServiceStateTest {

    @Test
    fun `default state is not running and disconnected`() {
        val state = BleCaptureServiceState()

        assertFalse(state.isRunning)
        assertEquals(ConnectionState.DISCONNECTED, state.connectionState)
        assertNull(state.currentHr)
        assertNull(state.deviceName)
        assertNull(state.deviceAddress)
        assertNull(state.sessionId)
        assertEquals(0, state.intervalCount)
        assertNull(state.error)
    }

    @Test
    fun `state reflects active capture`() {
        val state = BleCaptureServiceState(
            isRunning = true,
            connectionState = ConnectionState.CONNECTED,
            currentHr = 142,
            deviceName = "Garmin HRM-Pro",
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            sessionId = "session-123",
            intervalCount = 350,
        )

        assertEquals(true, state.isRunning)
        assertEquals(ConnectionState.CONNECTED, state.connectionState)
        assertEquals(142, state.currentHr)
        assertEquals("Garmin HRM-Pro", state.deviceName)
        assertEquals("AA:BB:CC:DD:EE:FF", state.deviceAddress)
        assertEquals("session-123", state.sessionId)
        assertEquals(350, state.intervalCount)
        assertNull(state.error)
    }

    @Test
    fun `state reflects error`() {
        val state = BleCaptureServiceState(
            isRunning = false,
            connectionState = ConnectionState.DISCONNECTED,
            error = "Bluetooth adapter is off",
        )

        assertFalse(state.isRunning)
        assertEquals("Bluetooth adapter is off", state.error)
    }
}
