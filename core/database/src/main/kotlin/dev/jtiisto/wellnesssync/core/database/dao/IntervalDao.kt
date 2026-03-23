package dev.jtiisto.wellnesssync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.jtiisto.wellnesssync.core.database.entity.IntervalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IntervalDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(intervals: List<IntervalEntity>)

    @Query(
        """
        SELECT * FROM intervals
        WHERE isSynced = 0
        ORDER BY timestampDevice ASC
        LIMIT :limit
        """
    )
    suspend fun getUnsyncedIntervals(limit: Int): List<IntervalEntity>

    @Query(
        """
        UPDATE intervals
        SET isSynced = 1, syncedAt = :syncedAt
        WHERE deviceId = :deviceId
        AND timestampDevice IN (:timestamps)
        """
    )
    suspend fun markSynced(deviceId: String, timestamps: List<Long>, syncedAt: Long)

    @Query("SELECT COUNT(*) FROM intervals WHERE isSynced = 0")
    fun getUnsyncedCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM intervals
        WHERE timestampDevice BETWEEN :start AND :end
        ORDER BY timestampDevice ASC
        """
    )
    suspend fun getIntervalsInRange(start: Long, end: Long): List<IntervalEntity>

    @Query(
        """
        SELECT * FROM intervals
        WHERE sessionId = :sessionId
        ORDER BY timestampDevice ASC
        """
    )
    suspend fun getIntervalsBySession(sessionId: String): List<IntervalEntity>

    @Query("SELECT COUNT(*) FROM intervals")
    suspend fun getTotalCount(): Int
}
