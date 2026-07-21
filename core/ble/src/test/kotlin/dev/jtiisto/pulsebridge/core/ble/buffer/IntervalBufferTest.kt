package dev.jtiisto.pulsebridge.core.ble.buffer

import dev.jtiisto.pulsebridge.core.ble.model.HeartRateSample
import dev.jtiisto.pulsebridge.core.ble.model.SensorPriority
import dev.jtiisto.pulsebridge.core.common.DiagnosticLog
import dev.jtiisto.pulsebridge.core.database.dao.IntervalDao
import dev.jtiisto.pulsebridge.core.database.entity.IntervalEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class IntervalBufferTest {

    private lateinit var dao: IntervalDao
    private lateinit var testScope: TestScope
    private val insertedBatches = mutableListOf<List<IntervalEntity>>()

    @BeforeEach
    fun setup() {
        dao = mockk()
        insertedBatches.clear()
        coEvery { dao.insertAll(capture(slot<List<IntervalEntity>>().also { _ ->
            // Using a different approach for capturing multiple calls
        })) } returns Unit
        coEvery { dao.insertAll(any()) } coAnswers {
            insertedBatches.add(firstArg())
        }
    }

    private fun createBuffer(
        config: BufferConfig = BufferConfig(flushInterval = 10.seconds, maxBufferSize = 5),
        scope: TestScope = testScope,
    ) = IntervalBuffer(
        intervalDao = dao,
        diagnosticLog = DiagnosticLog(),
        config = config,
        scope = scope,
    )

    private fun createSample(
        deviceId: String = "garmin-001",
        timestampDevice: Long = 1000L,
        heartRateBpm: Int = 72,
        rrIntervalsMs: List<Int> = listOf(833),
    ) = HeartRateSample(
        deviceId = deviceId,
        timestampDevice = timestampDevice,
        heartRateBpm = heartRateBpm,
        rrIntervalsMs = rrIntervalsMs,
        sensorPriority = SensorPriority.GARMIN_ECG,
        sensorType = "garmin_hrm",
    )

    @Test
    fun `add accumulates samples in buffer`() = runTest {
        testScope = this
        val buffer = createBuffer()

        buffer.add(createSample(timestampDevice = 1000L), sessionId = "s1")
        buffer.add(createSample(timestampDevice = 2000L), sessionId = "s1")

        assertEquals(2, buffer.size)
        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    @Test
    fun `flush writes buffered entities to DAO`() = runTest {
        testScope = this
        val buffer = createBuffer()

        buffer.add(createSample(timestampDevice = 1000L), sessionId = "s1")
        buffer.add(createSample(timestampDevice = 2000L), sessionId = "s1")
        buffer.flush()

        assertEquals(0, buffer.size)
        coVerify(exactly = 1) { dao.insertAll(any()) }
        assertEquals(2, insertedBatches[0].size)
    }

    @Test
    fun `flush is no-op when buffer is empty`() = runTest {
        testScope = this
        val buffer = createBuffer()
        buffer.flush()

        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    @Test
    fun `auto-flushes when maxBufferSize reached`() = runTest {
        testScope = this
        val buffer = createBuffer(config = BufferConfig(maxBufferSize = 3, flushInterval = 60.seconds))

        buffer.add(createSample(timestampDevice = 1000L), sessionId = "s1")
        buffer.add(createSample(timestampDevice = 2000L), sessionId = "s1")
        coVerify(exactly = 0) { dao.insertAll(any()) }

        buffer.add(createSample(timestampDevice = 3000L), sessionId = "s1")
        coVerify(exactly = 1) { dao.insertAll(any()) }
        assertEquals(0, buffer.size)
    }

    @Test
    fun `timer flush triggers at configured interval`() = runTest {
        testScope = this
        val buffer = createBuffer(config = BufferConfig(flushInterval = 5.seconds, maxBufferSize = 100))
        buffer.start()

        buffer.add(createSample(timestampDevice = 1000L), sessionId = "s1")
        coVerify(exactly = 0) { dao.insertAll(any()) }

        advanceTimeBy(5001)
        coVerify(exactly = 1) { dao.insertAll(any()) }

        buffer.stop()
    }

    @Test
    fun `maps single RR interval to single entity`() = runTest {
        testScope = this
        val buffer = createBuffer()

        buffer.add(createSample(rrIntervalsMs = listOf(833)), sessionId = "s1")
        buffer.flush()

        assertEquals(1, insertedBatches[0].size)
        val entity = insertedBatches[0][0]
        assertEquals(833, entity.rrIntervalMs)
        assertEquals(0, entity.rrSequenceIndex)
    }

    @Test
    fun `maps multiple RR intervals with the last beat anchored at receipt time`() = runTest {
        testScope = this
        val buffer = createBuffer()

        buffer.add(
            createSample(timestampDevice = 10_000L, rrIntervalsMs = listOf(800, 850, 820)),
            sessionId = "s1",
        )
        buffer.flush()

        val entities = insertedBatches[0]
        assertEquals(3, entities.size)

        // Beats precede the notification: each is receipt time minus the RRs after it
        assertEquals(8_330L, entities[0].timestampDevice) // 10000 - (850 + 820)
        assertEquals(800, entities[0].rrIntervalMs)
        assertEquals(0, entities[0].rrSequenceIndex)

        assertEquals(9_180L, entities[1].timestampDevice) // 10000 - 820
        assertEquals(850, entities[1].rrIntervalMs)
        assertEquals(1, entities[1].rrSequenceIndex)

        assertEquals(10_000L, entities[2].timestampDevice) // last beat = receipt time
        assertEquals(820, entities[2].rrIntervalMs)
        assertEquals(2, entities[2].rrSequenceIndex)
    }

    @Test
    fun `no generated timestamp exceeds the notification receipt time`() = runTest {
        testScope = this
        val buffer = createBuffer()

        buffer.add(
            createSample(timestampDevice = 10_000L, rrIntervalsMs = listOf(700, 750, 800, 720)),
            sessionId = "s1",
        )
        // Trailing zero-RR sentinels are the collision case that used to leak
        // into the future — they must also stay at or before receipt time
        buffer.add(
            createSample(timestampDevice = 20_000L, rrIntervalsMs = listOf(700, 750, 0, 0)),
            sessionId = "s1",
        )
        buffer.flush()

        val entities = insertedBatches[0]
        assertTrue(entities.take(4).all { it.timestampDevice <= 10_000L })
        assertTrue(entities.drop(4).all { it.timestampDevice <= 20_000L })
    }

    @Test
    fun `add returns the number of rows generated`() = runTest {
        testScope = this
        val buffer = createBuffer()

        assertEquals(2, buffer.add(createSample(rrIntervalsMs = listOf(800, 850)), sessionId = "s1"))
        assertEquals(1, buffer.add(createSample(timestampDevice = 5000L, rrIntervalsMs = emptyList()), sessionId = "s1"))
    }

    @Test
    fun `failed flush keeps the batch for a later retry`() = runTest {
        testScope = this
        var calls = 0
        coEvery { dao.insertAll(any()) } coAnswers {
            calls++
            if (calls == 1) throw RuntimeException("database or disk is full")
            insertedBatches.add(firstArg())
        }
        val buffer = createBuffer()

        buffer.add(createSample(timestampDevice = 1000L, rrIntervalsMs = listOf(800, 850)), sessionId = "s1")
        val firstFlush = buffer.flush()

        // First flush failed — nothing stored, rows retained, failure reported
        assertEquals(false, firstFlush)
        assertEquals(0, insertedBatches.size)
        assertEquals(2, buffer.size)

        val secondFlush = buffer.flush()

        assertEquals(true, secondFlush)
        assertEquals(1, insertedBatches.size)
        assertEquals(2, insertedBatches[0].size)
        assertEquals(0, buffer.size)
    }

    @Test
    fun `maps sample with empty RR list to gap entity`() = runTest {
        testScope = this
        val buffer = createBuffer()

        buffer.add(
            createSample(rrIntervalsMs = emptyList()).copy(isGapBefore = true),
            sessionId = "s1",
        )
        buffer.flush()

        val entity = insertedBatches[0][0]
        assertEquals(0, entity.rrIntervalMs)
        assertTrue(entity.isGap)
    }

    @Test
    fun `gap flag only set on first entity of multi-RR sample`() = runTest {
        testScope = this
        val buffer = createBuffer()

        buffer.add(
            createSample(rrIntervalsMs = listOf(800, 850)).copy(isGapBefore = true),
            sessionId = "s1",
        )
        buffer.flush()

        val entities = insertedBatches[0]
        assertTrue(entities[0].isGap)
        assertTrue(!entities[1].isGap)
    }

    @Test
    fun `colliding timestamps across notifications are bumped to stay unique`() = runTest {
        testScope = this
        val buffer = createBuffer()

        // Two notifications with the same receipt timestamp — the second would
        // collide on the (deviceId, timestampDevice) PK and be silently dropped
        buffer.add(createSample(timestampDevice = 1000L, rrIntervalsMs = listOf(800)), sessionId = "s1")
        buffer.add(createSample(timestampDevice = 1000L, rrIntervalsMs = listOf(810)), sessionId = "s1")
        buffer.flush()

        val entities = insertedBatches[0]
        assertEquals(2, entities.size)
        assertEquals(1000L, entities[0].timestampDevice)
        assertEquals(1001L, entities[1].timestampDevice)
    }

    @Test
    fun `zero RR intervals within a notification get unique timestamps`() = runTest {
        testScope = this
        val buffer = createBuffer()

        buffer.add(createSample(timestampDevice = 1000L, rrIntervalsMs = listOf(800, 0, 0)), sessionId = "s1")
        buffer.flush()

        // Zero RRs collapse onto the anchor and are spread BACKWARD — the
        // last beat stays at receipt time and nothing lands in the future
        val timestamps = insertedBatches[0].map { it.timestampDevice }
        assertEquals(listOf(998L, 999L, 1000L), timestamps)
    }

    @Test
    fun `timestamps are tracked independently per device`() = runTest {
        testScope = this
        val buffer = createBuffer()

        buffer.add(createSample(deviceId = "garmin-001", timestampDevice = 1000L), sessionId = "s1")
        buffer.add(createSample(deviceId = "garmin-002", timestampDevice = 1000L), sessionId = "s1")
        buffer.flush()

        val entities = insertedBatches[0]
        // Different devices may share timestamps — the PK includes deviceId
        assertEquals(1000L, entities[0].timestampDevice)
        assertEquals(1000L, entities[1].timestampDevice)
    }

    @Test
    fun `session ID is propagated to entities`() = runTest {
        testScope = this
        val buffer = createBuffer()

        buffer.add(createSample(), sessionId = "workout-42")
        buffer.flush()

        assertEquals("workout-42", insertedBatches[0][0].sessionId)
    }

    @Test
    fun `null session ID is propagated`() = runTest {
        testScope = this
        val buffer = createBuffer()

        buffer.add(createSample(), sessionId = null)
        buffer.flush()

        assertEquals(null, insertedBatches[0][0].sessionId)
    }
}
