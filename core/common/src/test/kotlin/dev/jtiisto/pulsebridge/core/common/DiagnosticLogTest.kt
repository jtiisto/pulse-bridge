package dev.jtiisto.pulsebridge.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticLogTest {

    @Test
    fun `entries are kept in insertion order`() {
        val log = DiagnosticLog()
        log.log("a", "first")
        log.log("b", "second")

        val snapshot = log.snapshot()
        assertEquals(listOf("first", "second"), snapshot.map { it.message })
        assertEquals(listOf("a", "b"), snapshot.map { it.tag })
    }

    @Test
    fun `oldest entries are evicted beyond capacity`() {
        val log = DiagnosticLog(capacity = 3)
        repeat(5) { log.log("t", "msg$it") }

        assertEquals(3, log.size)
        assertEquals(listOf("msg2", "msg3", "msg4"), log.snapshot().map { it.message })
    }

    @Test
    fun `snapshot is isolated from later writes`() {
        val log = DiagnosticLog()
        log.log("t", "before")
        val snapshot = log.snapshot()
        log.log("t", "after")

        assertEquals(1, snapshot.size)
        assertEquals(2, log.size)
    }

    @Test
    fun `clear empties the buffer`() {
        val log = DiagnosticLog()
        log.log("t", "msg")
        log.clear()

        assertEquals(0, log.size)
        assertTrue(log.snapshot().isEmpty())
    }
}
