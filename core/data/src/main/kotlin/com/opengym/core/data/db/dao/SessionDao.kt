package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opengym.core.data.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY started_at DESC")
    fun getAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun getById(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE finished_at IS NULL ORDER BY started_at DESC LIMIT 1")
    fun getActive(): Flow<SessionEntity?>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)
}
