package dev.jtiisto.wellnesssync.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.jtiisto.wellnesssync.core.database.entity.SyncStatusEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStatusDao {

    @Query("SELECT * FROM sync_status WHERE id = 1")
    fun observe(): Flow<SyncStatusEntity?>

    @Query("SELECT * FROM sync_status WHERE id = 1")
    suspend fun get(): SyncStatusEntity?

    @Upsert
    suspend fun upsert(status: SyncStatusEntity)
}
