package dev.jtiisto.wellnesssync.core.database

import dev.jtiisto.wellnesssync.core.database.dao.AccelerometerSummaryDao
import dev.jtiisto.wellnesssync.core.database.dao.IntervalDao

class DatabaseCleaner(
    private val intervalDao: IntervalDao,
    private val accelerometerSummaryDao: AccelerometerSummaryDao,
) {
    /**
     * Deletes only rows already synced to the server. Unsynced captures are
     * never deleted — local storage is the only copy until the server acks.
     *
     * @return total number of rows deleted
     */
    suspend fun clearSyncedData(): Int {
        return intervalDao.deleteSynced() + accelerometerSummaryDao.deleteSynced()
    }
}
