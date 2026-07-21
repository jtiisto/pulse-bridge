package dev.jtiisto.pulsebridge.core.ble.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HrmCharacteristicParserTest {

    @Test
    fun `parse empty array returns null`() {
        assertNull(HrmCharacteristicParser.parse(byteArrayOf()))
    }

    @Test
    fun `parse 8-bit HR without RR intervals`() {
        // Flags: 0x00 (8-bit HR, no RR, no energy expended)
        // HR: 72
        val data = byteArrayOf(0x00, 72)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(72, result.heartRateBpm)
        assertTrue(result.rrIntervalsMs.isEmpty())
        assertNull(result.energyExpendedKj)
    }

    @Test
    fun `parse 16-bit HR without RR intervals`() {
        // Flags: 0x01 (16-bit HR)
        // HR: 300 = 0x012C (little-endian: 0x2C, 0x01)
        val data = byteArrayOf(0x01, 0x2C, 0x01)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(300, result.heartRateBpm)
        assertTrue(result.rrIntervalsMs.isEmpty())
    }

    @Test
    fun `parse 8-bit HR with single RR interval`() {
        // Flags: 0x10 (8-bit HR, RR present)
        // HR: 72
        // RR: 853 in 1/1024s = 0x0355 → little-endian: 0x55, 0x03
        // Expected ms: (853 * 1000) / 1024 = 833
        val data = byteArrayOf(0x10, 72, 0x55, 0x03)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(72, result.heartRateBpm)
        assertEquals(1, result.rrIntervalsMs.size)
        assertEquals(833, result.rrIntervalsMs[0])
    }

    @Test
    fun `parse 8-bit HR with multiple RR intervals`() {
        // Flags: 0x10 (8-bit HR, RR present)
        // HR: 65
        // RR1: 1024 in 1/1024s = 0x0400 → 1000ms
        // RR2: 512 in 1/1024s = 0x0200 → 500ms
        val data = byteArrayOf(0x10, 65, 0x00, 0x04, 0x00, 0x02)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(65, result.heartRateBpm)
        assertEquals(2, result.rrIntervalsMs.size)
        assertEquals(1000, result.rrIntervalsMs[0])
        assertEquals(500, result.rrIntervalsMs[1])
    }

    @Test
    fun `parse 16-bit HR with RR intervals`() {
        // Flags: 0x11 (16-bit HR, RR present)
        // HR: 150 = 0x0096 → little-endian: 0x96, 0x00
        // RR: 512 in 1/1024s = 0x0200 → 500ms
        val data = byteArrayOf(0x11, 0x96.toByte(), 0x00, 0x00, 0x02)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(150, result.heartRateBpm)
        assertEquals(1, result.rrIntervalsMs.size)
        assertEquals(500, result.rrIntervalsMs[0])
    }

    @Test
    fun `parse with energy expended present and RR intervals`() {
        // Flags: 0x18 (8-bit HR, energy expended present, RR present)
        // HR: 80
        // Energy: 42 = 0x002A → little-endian: 0x2A, 0x00
        // RR: 1024 in 1/1024s = 0x0400 → 1000ms
        val data = byteArrayOf(0x18, 80, 0x2A, 0x00, 0x00, 0x04)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(80, result.heartRateBpm)
        assertEquals(42, result.energyExpendedKj)
        assertEquals(1, result.rrIntervalsMs.size)
        assertEquals(1000, result.rrIntervalsMs[0])
    }

    @Test
    fun `parse with energy expended present but no RR intervals`() {
        // Flags: 0x08 (8-bit HR, energy expended present, no RR)
        // HR: 90
        // Energy: 100 = 0x0064 → little-endian: 0x64, 0x00
        val data = byteArrayOf(0x08, 90, 0x64, 0x00)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(90, result.heartRateBpm)
        assertEquals(100, result.energyExpendedKj)
        assertTrue(result.rrIntervalsMs.isEmpty())
    }

    @Test
    fun `parse with sensor contact supported and detected`() {
        // Flags: 0x06 (8-bit HR, sensor contact supported + detected)
        // HR: 70
        val data = byteArrayOf(0x06, 70)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(70, result.heartRateBpm)
        assertEquals(true, result.sensorContactDetected)
    }

    @Test
    fun `parse with sensor contact supported but not detected`() {
        // Flags: 0x04 (8-bit HR, sensor contact supported, not detected)
        // HR: 0
        val data = byteArrayOf(0x04, 0)
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(0, result.heartRateBpm)
        assertEquals(false, result.sensorContactDetected)
    }

    @Test
    fun `parse without sensor contact feature returns null contact`() {
        val data = byteArrayOf(0x00, 72)
        val result = HrmCharacteristicParser.parse(data)!!

        assertNull(result.sensorContactDetected)
    }

    @Test
    fun `parse truncated 16-bit HR returns null`() {
        // Flags: 0x01 (16-bit HR) but only one byte of HR data
        val data = byteArrayOf(0x01, 72)
        assertNull(HrmCharacteristicParser.parse(data))
    }

    @Test
    fun `parse three RR intervals in one notification`() {
        // Flags: 0x10 (8-bit HR, RR present)
        // HR: 140
        // RR1: 512 → 500ms, RR2: 480 → 468ms, RR3: 496 → 484ms
        val data = byteArrayOf(
            0x10, 140.toByte(),
            0x00, 0x02, // 512
            0xE0.toByte(), 0x01, // 480
            0xF0.toByte(), 0x01, // 496
        )
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(140, result.heartRateBpm)
        assertEquals(3, result.rrIntervalsMs.size)
        assertEquals(500, result.rrIntervalsMs[0])
        assertEquals(468, result.rrIntervalsMs[1])
        assertEquals(484, result.rrIntervalsMs[2])
    }

    @Test
    fun `parse handles odd trailing byte gracefully`() {
        // Flags: 0x10 (8-bit HR, RR present)
        // HR: 72
        // One complete RR + one trailing byte (incomplete RR)
        val data = byteArrayOf(0x10, 72, 0x00, 0x04, 0xFF.toByte())
        val result = HrmCharacteristicParser.parse(data)!!

        assertEquals(1, result.rrIntervalsMs.size) // only complete RR pairs
        assertEquals(1000, result.rrIntervalsMs[0])
    }
}
