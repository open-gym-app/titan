package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opengym.core.data.db.entity.WorkoutSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutSessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: WorkoutSessionEntity): Long

    @Update
    suspend fun update(session: WorkoutSessionEntity)

    @Query("DELETE FROM workout_session WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM workout_session ORDER BY created_at DESC")
    fun getAll(): Flow<List<WorkoutSessionEntity>>

    @Query("SELECT * FROM workout_session WHERE id = :id")
    fun getById(id: Long): Flow<WorkoutSessionEntity?>

    @Query(
        """
        SELECT * FROM workout_session
        WHERE is_in_progress = 1
        ORDER BY last_active_at DESC
        LIMIT 1
        """,
    )
    fun getActive(): Flow<WorkoutSessionEntity?>
}
