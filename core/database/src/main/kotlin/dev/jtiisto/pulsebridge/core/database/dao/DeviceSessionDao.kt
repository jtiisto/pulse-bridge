package dev.jtiisto.pulsebridge.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.jtiisto.pulsebridge.core.database.entity.DeviceSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: DeviceSessionEntity)

    @Update
    suspend fun update(session: DeviceSessionEntity)

    @Query("SELECT * FROM device_sessions WHERE sessionId = :sessionId")
    suspend fun getById(sessionId: String): DeviceSessionEntity?

    @Query(
        """
        SELECT * FROM device_sessions
        WHERE endTime IS NULL AND deviceId = :deviceId
        LIMIT 1
        """
    )
    suspend fun getActiveSession(deviceId: String): DeviceSessionEntity?

    @Query(
        """
        SELECT * FROM device_sessions
        WHERE endTime IS NULL
        ORDER BY startTime DESC
        LIMIT 1
        """
    )
    suspend fun getMostRecentActiveSession(): DeviceSessionEntity?

    @Query(
        """
        SELECT * FROM device_sessions
        ORDER BY startTime DESC
        LIMIT :limit
        """
    )
    fun getRecentSessions(limit: Int): Flow<List<DeviceSessionEntity>>
}
