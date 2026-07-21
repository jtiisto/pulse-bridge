package dev.jtiisto.pulsebridge.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.jtiisto.pulsebridge.core.database.PulseBridgeDatabase
import dev.jtiisto.pulsebridge.core.database.entity.IntervalEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class IntervalDaoTest {

    private lateinit var database: PulseBridgeDatabase
    private lateinit var dao: IntervalDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PulseBridgeDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.intervalDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createInterval(
        deviceId: String = "garmin-001",
        timestampDevice: Long = 1000L,
        heartRateBpm: Int = 72,
        rrIntervalMs: Int = 833,
        rrSequenceIndex: Int = 0,
        sensorType: String = "garmin_hrm",
        sessionId: String? = "session-1",
        isSynced: Boolean = false,
    ) = IntervalEntity(
        deviceId = deviceId,
        timestampDevice = timestampDevice,
        timestampPhone = timestampDevice + 10,
        heartRateBpm = heartRateBpm,
        rrIntervalMs = rrIntervalMs,
        rrSequenceIndex = rrSequenceIndex,
        sensorType = sensorType,
        sessionId = sessionId,
        isSynced = isSynced,
    )

    @Test
    fun insertAll_insertsIntervals() = runTest {
        val intervals = listOf(
            createInterval(timestampDevice = 1000L),
            createInterval(timestampDevice = 2000L),
        )
        dao.insertAll(intervals)
        assertEquals(2, dao.getTotalCount())
    }

    @Test
    fun insertAll_ignoresDuplicateCompositePk() = runTest {
        val interval = createInterval(timestampDevice = 1000L)
        dao.insertAll(listOf(interval))
        dao.insertAll(listOf(interval.copy(heartRateBpm = 99)))

        val result = dao.getIntervalsInRange(0, 5000)
        assertEquals(1, result.size)
        assertEquals(72, result[0].heartRateBpm) // original value kept
    }

    @Test
    fun getUnsyncedIntervals_returnsOnlyUnsynced() = runTest {
        dao.insertAll(
            listOf(
                createInterval(timestampDevice = 1000L, isSynced = false),
                createInterval(timestampDevice = 2000L, isSynced = true),
                createInterval(timestampDevice = 3000L, isSynced = false),
            )
        )

        val unsynced = dao.getUnsyncedIntervals(limit = 10)
        assertEquals(2, unsynced.size)
        assertTrue(unsynced.all { !it.isSynced })
    }

    @Test
    fun getUnsyncedIntervals_respectsLimit() = runTest {
        dao.insertAll(
            (1L..5L).map { createInterval(timestampDevice = it * 1000L) }
        )

        val unsynced = dao.getUnsyncedIntervals(limit = 3)
        assertEquals(3, unsynced.size)
    }

    @Test
    fun getUnsyncedIntervals_orderedByTimestampAsc() = runTest {
        dao.insertAll(
            listOf(
                createInterval(timestampDevice = 3000L),
                createInterval(timestampDevice = 1000L),
                createInterval(timestampDevice = 2000L),
            )
        )

        val unsynced = dao.getUnsyncedIntervals(limit = 10)
        assertEquals(listOf(1000L, 2000L, 3000L), unsynced.map { it.timestampDevice })
    }

    @Test
    fun markSynced_updatesMatchingRows() = runTest {
        dao.insertAll(
            listOf(
                createInterval(timestampDevice = 1000L),
                createInterval(timestampDevice = 2000L),
                createInterval(timestampDevice = 3000L),
            )
        )

        dao.markSynced("garmin-001", listOf(1000L, 2000L), syncedAt = 99999L)

        val unsynced = dao.getUnsyncedIntervals(limit = 10)
        assertEquals(1, unsynced.size)
        assertEquals(3000L, unsynced[0].timestampDevice)
    }

    @Test
    fun getUnsyncedCount_emitsAsFlow() = runTest {
        assertEquals(0, dao.getUnsyncedCount().first())

        dao.insertAll(listOf(createInterval(timestampDevice = 1000L)))
        assertEquals(1, dao.getUnsyncedCount().first())
    }

    @Test
    fun getIntervalsInRange_filtersCorrectly() = runTest {
        dao.insertAll(
            listOf(
                createInterval(timestampDevice = 500L),
                createInterval(timestampDevice = 1000L),
                createInterval(timestampDevice = 1500L),
                createInterval(timestampDevice = 2500L),
            )
        )

        val result = dao.getIntervalsInRange(start = 900, end = 2000)
        assertEquals(2, result.size)
        assertEquals(listOf(1000L, 1500L), result.map { it.timestampDevice })
    }

    @Test
    fun getIntervalsBySession_filtersCorrectly() = runTest {
        dao.insertAll(
            listOf(
                createInterval(timestampDevice = 1000L, sessionId = "session-1"),
                createInterval(timestampDevice = 2000L, sessionId = "session-2"),
                createInterval(timestampDevice = 3000L, sessionId = "session-1"),
            )
        )

        val result = dao.getIntervalsBySession("session-1")
        assertEquals(2, result.size)
        assertTrue(result.all { it.sessionId == "session-1" })
    }

    @Test
    fun compositePk_allowsSameTimestampDifferentDevices() = runTest {
        dao.insertAll(
            listOf(
                createInterval(deviceId = "garmin-001", timestampDevice = 1000L),
                createInterval(deviceId = "polar-001", timestampDevice = 1000L, sensorType = "polar_pvs"),
            )
        )
        assertEquals(2, dao.getTotalCount())
    }
}
