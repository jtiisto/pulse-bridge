package dev.jtiisto.wellnesssync.core.database

class DatabaseCleaner(private val database: WellnessSyncDatabase) {
    suspend fun clearAll() {
        database.clearAllTables()
    }
}
