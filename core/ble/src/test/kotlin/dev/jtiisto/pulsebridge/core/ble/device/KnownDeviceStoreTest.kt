package dev.jtiisto.pulsebridge.core.ble.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KnownDeviceStoreTest {

    @Test
    fun `KnownDevice data class holds address and name`() {
        val device = KnownDevice("AA:BB:CC:DD:EE:FF", "Garmin HRM-Pro")

        assertEquals("AA:BB:CC:DD:EE:FF", device.address)
        assertEquals("Garmin HRM-Pro", device.name)
    }
}
