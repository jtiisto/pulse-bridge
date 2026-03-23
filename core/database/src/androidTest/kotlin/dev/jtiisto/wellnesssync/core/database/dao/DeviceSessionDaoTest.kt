package dev.jtiisto.wellnesssync.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.jtiisto.wellnesssync.core.database.WellnessSyncDatabase
import dev.jtiisto.wellnesssync.core.database.entity.DeviceSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull

class DeviceSessionDaoTest {

    private lateinit var database: WellnessSyncDatabase
    private lateinit var dao: DeviceSessionDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WellnessSyncDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.deviceSessionDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun createSession(
        sessionId: String = "session-1",
        deviceId: String = "garmin-001",
        sensorType: String = "garmin_hrm",
        startTime: Long = 1000L,
        endTime: Long? = null,
        totalIntervals: Int = 0,
        averageHr: Int? = null,
    ) = DeviceSessionEntity(
        sessionId = sessionId,
        deviceId = deviceId,
        sensorType = sensorType,
        startTime = startTime,
        endTime = endTime,
        totalIntervals = totalIntervals,
        averageHr = averageHr,
    )

    @Test
    fun insert_andGetById() = runTest {
        val session = createSession()
        dao.insert(session)

        val result = dao.getById("session-1")
        assertNotNull(result)
        assertEquals("garmin-001", result!!.deviceId)
    }

    @Test
    fun getById_returnsNullForMissing() = runTest {
        assertNull(dao.getById("nonexistent"))
    }

    @Test
    fun update_modifiesSession() = runTest {
        dao.insert(createSession())
        dao.update(createSession(endTime = 5000L, totalIntervals = 100, averageHr = 145))

        val result = dao.getById("session-1")!!
        assertEquals(5000L, result.endTime)
        assertEquals(100, result.totalIntervals)
        assertEquals(145, result.averageHr)
    }

    @Test
    fun getActiveSession_returnsSessionWithNullEndTime() = runTest {
        dao.insert(createSession(sessionId = "s1", endTime = null))
        dao.insert(createSession(sessionId = "s2", endTime = 5000L))

        val active = dao.getActiveSession("garmin-001")
        assertNotNull(active)
        assertEquals("s1", active!!.sessionId)
    }

    @Test
    fun getActiveSession_returnsNullWhenAllClosed() = runTest {
        dao.insert(createSession(sessionId = "s1", endTime = 5000L))

        assertNull(dao.getActiveSession("garmin-001"))
    }

    @Test
    fun getRecentSessions_orderedByStartTimeDesc() = runTest {
        dao.insert(createSession(sessionId = "s1", startTime = 1000L))
        dao.insert(createSession(sessionId = "s2", startTime = 3000L))
        dao.insert(createSession(sessionId = "s3", startTime = 2000L))

        val sessions = dao.getRecentSessions(limit = 10).first()
        assertEquals(listOf("s2", "s3", "s1"), sessions.map { it.sessionId })
    }

    @Test
    fun getRecentSessions_respectsLimit() = runTest {
        dao.insert(createSession(sessionId = "s1", startTime = 1000L))
        dao.insert(createSession(sessionId = "s2", startTime = 2000L))
        dao.insert(createSession(sessionId = "s3", startTime = 3000L))

        val sessions = dao.getRecentSessions(limit = 2).first()
        assertEquals(2, sessions.size)
    }
}
