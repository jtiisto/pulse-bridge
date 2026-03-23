package dev.jtiisto.wellnesssync.feature.capture.domain.model

import dev.jtiisto.wellnesssync.core.ble.model.ConnectionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CaptureStateTest {

    @Test
    fun `default state has sensible defaults`() {
        val state = CaptureState()

        assertFalse(state.isCapturing)
        assertEquals(ConnectionState.DISCONNECTED, state.connectionState)
        assertNull(state.currentHr)
        assertNull(state.deviceName)
        assertEquals(0, state.intervalCount)
        assertEquals(0, state.unsyncedCount)
        assertNull(state.lastSyncTime)
        assertNull(state.error)
        assertFalse(state.permissionsGranted)
        assertFalse(state.isScanning)
        assertTrue(state.knownDevices.isEmpty())
        assertTrue(state.discoveredDevices.isEmpty())
    }

    @Test
    fun `copy preserves unmodified fields`() {
        val state = CaptureState(
            isCapturing = true,
            currentHr = 120,
            intervalCount = 50,
        )
        val updated = state.copy(currentHr = 130)

        assertTrue(updated.isCapturing)
        assertEquals(130, updated.currentHr)
        assertEquals(50, updated.intervalCount)
    }
}
