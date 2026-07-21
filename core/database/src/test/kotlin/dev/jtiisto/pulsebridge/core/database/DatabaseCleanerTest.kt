package dev.jtiisto.pulsebridge.core.database

import dev.jtiisto.pulsebridge.core.database.dao.AccelerometerSummaryDao
import dev.jtiisto.pulsebridge.core.database.dao.IntervalDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DatabaseCleanerTest {

    private val intervalDao: IntervalDao = mockk()
    private val accDao: AccelerometerSummaryDao = mockk()
    private val cleaner = DatabaseCleaner(intervalDao, accDao)

    @Test
    fun `clearSyncedData deletes only synced rows and returns total count`() = runTest {
        coEvery { intervalDao.deleteSynced() } returns 120
        coEvery { accDao.deleteSynced() } returns 30

        val deleted = cleaner.clearSyncedData()

        assertEquals(150, deleted)
        coVerify(exactly = 1) { intervalDao.deleteSynced() }
        coVerify(exactly = 1) { accDao.deleteSynced() }
    }

    @Test
    fun `clearSyncedData with nothing synced returns zero`() = runTest {
        coEvery { intervalDao.deleteSynced() } returns 0
        coEvery { accDao.deleteSynced() } returns 0

        assertEquals(0, cleaner.clearSyncedData())
    }
}
