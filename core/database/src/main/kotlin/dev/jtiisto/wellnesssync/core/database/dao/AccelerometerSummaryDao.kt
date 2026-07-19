package dev.jtiisto.wellnesssync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.jtiisto.wellnesssync.core.database.entity.AccelerometerSummaryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccelerometerSummaryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(summaries: List<AccelerometerSummaryEntity>)

    @Query(
        """
        SELECT * FROM accelerometer_summaries
        WHERE isSynced = 0 AND isQuarantined = 0
        ORDER BY windowStart ASC
        LIMIT :limit
        """
    )
    suspend fun getUnsyncedSummaries(limit: Int): List<AccelerometerSummaryEntity>

    @Query(
        """
        UPDATE accelerometer_summaries
        SET isSynced = 1, syncedAt = :syncedAt
        WHERE deviceId = :deviceId
        AND windowStart IN (:windowStarts)
        """
    )
    suspend fun markSynced(deviceId: String, windowStarts: List<Long>, syncedAt: Long)

    @Query("SELECT COUNT(*) FROM accelerometer_summaries WHERE isSynced = 0 AND isQuarantined = 0")
    fun getUnsyncedCount(): Flow<Int>

    @Query(
        """
        UPDATE accelerometer_summaries
        SET isQuarantined = 1
        WHERE deviceId = :deviceId
        AND windowStart IN (:windowStarts)
        """
    )
    suspend fun quarantine(deviceId: String, windowStarts: List<Long>): Int

    @Query("SELECT COUNT(*) FROM accelerometer_summaries WHERE isQuarantined = 1")
    fun getQuarantinedCount(): Flow<Int>

    @Query("UPDATE accelerometer_summaries SET isQuarantined = 0 WHERE isQuarantined = 1")
    suspend fun clearQuarantine(): Int

    @Query("SELECT COUNT(*) FROM accelerometer_summaries")
    suspend fun getTotalCount(): Int

    @Query("DELETE FROM accelerometer_summaries WHERE isSynced = 1")
    suspend fun deleteSynced(): Int

    @Query(
        """
        SELECT * FROM accelerometer_summaries
        WHERE sessionId = :sessionId
        ORDER BY windowStart ASC
        """
    )
    suspend fun getSummariesBySession(sessionId: String): List<AccelerometerSummaryEntity>
}
