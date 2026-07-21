package dev.jtiisto.pulsebridge.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.jtiisto.pulsebridge.core.database.PulseBridgeDatabase
import dev.jtiisto.pulsebridge.core.database.entity.AccelerometerSummaryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class AccelerometerSummaryDaoTest {

    private lateinit var database: PulseBridgeDatabase
    private lateinit var dao: AccelerometerSummaryDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PulseBridgeDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.accelerometerSummaryDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createSummary(
        deviceId: String = "1A2B3C4D",
        windowStart: Long = 1000L,
        magnitudeMean: Double = 1.02,
        magnitudeStd: Double = 0.15,
        magnitudeMax: Double = 2.81,
        sampleCount: Int = 3120,
        sensorType: String = "polar_pvs",
        sessionId: String? = "rec_2026-03-23_001",
        isSynced: Boolean = false,
    ) = AccelerometerSummaryEntity(
        deviceId = deviceId,
        windowStart = windowStart,
        magnitudeMean = magnitudeMean,
        magnitudeStd = magnitudeStd,
        magnitudeMax = magnitudeMax,
        sampleCount = sampleCount,
        sensorType = sensorType,
        sessionId = sessionId,
        isSynced = isSynced,
    )

    @Test
    fun insertAll_insertsSummaries() = runTest {
        val summaries = listOf(
            createSummary(windowStart = 1000L),
            createSummary(windowStart = 2000L),
        )
        dao.insertAll(summaries)
        assertEquals(2, dao.getTotalCount())
    }

    @Test
    fun insertAll_ignoresDuplicateCompositePk() = runTest {
        val summary = createSummary(windowStart = 1000L)
        dao.insertAll(listOf(summary))
        dao.insertAll(listOf(summary.copy(magnitudeMean = 9.99)))

        val result = dao.getSummariesBySession("rec_2026-03-23_001")
        assertEquals(1, result.size)
        assertEquals(1.02, result[0].magnitudeMean, 0.001)
    }

    @Test
    fun getUnsyncedSummaries_returnsOnlyUnsynced() = runTest {
        dao.insertAll(
            listOf(
                createSummary(windowStart = 1000L, isSynced = false),
                createSummary(windowStart = 2000L, isSynced = true),
                createSummary(windowStart = 3000L, isSynced = false),
            )
        )

        val unsynced = dao.getUnsyncedSummaries(limit = 10)
        assertEquals(2, unsynced.size)
        assertTrue(unsynced.all { !it.isSynced })
    }

    @Test
    fun getUnsyncedSummaries_respectsLimit() = runTest {
        dao.insertAll(
            (1L..5L).map { createSummary(windowStart = it * 1000L) }
        )

        val unsynced = dao.getUnsyncedSummaries(limit = 3)
        assertEquals(3, unsynced.size)
    }

    @Test
    fun getUnsyncedSummaries_orderedByWindowStartAsc() = runTest {
        dao.insertAll(
            listOf(
                createSummary(windowStart = 3000L),
                createSummary(windowStart = 1000L),
                createSummary(windowStart = 2000L),
            )
        )

        val unsynced = dao.getUnsyncedSummaries(limit = 10)
        assertEquals(listOf(1000L, 2000L, 3000L), unsynced.map { it.windowStart })
    }

    @Test
    fun markSynced_updatesMatchingRows() = runTest {
        dao.insertAll(
            listOf(
                createSummary(windowStart = 1000L),
                createSummary(windowStart = 2000L),
                createSummary(windowStart = 3000L),
            )
        )

        dao.markSynced("1A2B3C4D", listOf(1000L, 2000L), syncedAt = 99999L)

        val unsynced = dao.getUnsyncedSummaries(limit = 10)
        assertEquals(1, unsynced.size)
        assertEquals(3000L, unsynced[0].windowStart)
    }

    @Test
    fun getUnsyncedCount_emitsAsFlow() = runTest {
        assertEquals(0, dao.getUnsyncedCount().first())

        dao.insertAll(listOf(createSummary(windowStart = 1000L)))
        assertEquals(1, dao.getUnsyncedCount().first())
    }

    @Test
    fun compositePk_allowsSameWindowStartDifferentDevices() = runTest {
        dao.insertAll(
            listOf(
                createSummary(deviceId = "1A2B3C4D", windowStart = 1000L),
                createSummary(deviceId = "5E6F7G8H", windowStart = 1000L),
            )
        )
        assertEquals(2, dao.getTotalCount())
    }

    @Test
    fun getSummariesBySession_filtersCorrectly() = runTest {
        dao.insertAll(
            listOf(
                createSummary(windowStart = 1000L, sessionId = "rec_001"),
                createSummary(windowStart = 2000L, sessionId = "rec_002"),
                createSummary(windowStart = 3000L, sessionId = "rec_001"),
            )
        )

        val result = dao.getSummariesBySession("rec_001")
        assertEquals(2, result.size)
        assertTrue(result.all { it.sessionId == "rec_001" })
    }
}
