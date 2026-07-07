package dev.jtiisto.wellnesssync.core.ble.buffer

import dev.jtiisto.wellnesssync.core.ble.model.HeartRateSample
import dev.jtiisto.wellnesssync.core.ble.model.SensorPriority
import dev.jtiisto.wellnesssync.core.database.dao.IntervalDao
import dev.jtiisto.wellnesssync.core.database.entity.IntervalEntity
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
    ) = IntervalBuffer(dao, config, scope)

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
    fun `maps multiple RR intervals to multiple entities with offset timestamps`() = runTest {
        testScope = this
        val buffer = createBuffer()

        buffer.add(
            createSample(timestampDevice = 1000L, rrIntervalsMs = listOf(800, 850, 820)),
            sessionId = "s1",
        )
        buffer.flush()

        val entities = insertedBatches[0]
        assertEquals(3, entities.size)

        // First entity at base timestamp
        assertEquals(1000L, entities[0].timestampDevice)
        assertEquals(800, entities[0].rrIntervalMs)
        assertEquals(0, entities[0].rrSequenceIndex)

        // Second entity offset by first RR
        assertEquals(1800L, entities[1].timestampDevice) // 1000 + 800
        assertEquals(850, entities[1].rrIntervalMs)
        assertEquals(1, entities[1].rrSequenceIndex)

        // Third entity offset by first + second RR
        assertEquals(2650L, entities[2].timestampDevice) // 1000 + 800 + 850
        assertEquals(820, entities[2].rrIntervalMs)
        assertEquals(2, entities[2].rrSequenceIndex)
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

        val timestamps = insertedBatches[0].map { it.timestampDevice }
        assertEquals(listOf(1000L, 1800L, 1801L), timestamps)
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
