package dev.jtiisto.wellnesssync.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.jtiisto.wellnesssync.core.database.WellnessSyncDatabase
import dev.jtiisto.wellnesssync.core.database.entity.SyncStatusEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class SyncStatusDaoTest {

    private lateinit var database: WellnessSyncDatabase
    private lateinit var dao: SyncStatusDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WellnessSyncDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.syncStatusDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun get_returnsNullInitially() = runTest {
        assertNull(dao.get())
    }

    @Test
    fun upsert_insertsNewRecord() = runTest {
        dao.upsert(SyncStatusEntity(lastSyncTime = 1000L, pendingCount = 5))

        val result = dao.get()!!
        assertEquals(1000L, result.lastSyncTime)
        assertEquals(5, result.pendingCount)
    }

    @Test
    fun upsert_updatesExistingRecord() = runTest {
        dao.upsert(SyncStatusEntity(lastSyncTime = 1000L, pendingCount = 5))
        dao.upsert(SyncStatusEntity(lastSyncTime = 2000L, pendingCount = 0, lastError = null))

        val result = dao.get()!!
        assertEquals(2000L, result.lastSyncTime)
        assertEquals(0, result.pendingCount)
    }

    @Test
    fun observe_emitsUpdates() = runTest {
        assertNull(dao.observe().first())

        dao.upsert(SyncStatusEntity(lastSyncTime = 1000L, pendingCount = 3))
        val result = dao.observe().first()!!
        assertEquals(3, result.pendingCount)
    }
}
