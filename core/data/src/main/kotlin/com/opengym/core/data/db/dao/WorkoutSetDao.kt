package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opengym.core.data.db.entity.WorkoutSetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutSetDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(set: WorkoutSetEntity): Long

    @Update
    suspend fun update(set: WorkoutSetEntity)

    @Delete
    suspend fun delete(set: WorkoutSetEntity)

    @Query("SELECT * FROM workout_set WHERE session_exercise_id = :sessionExerciseId ORDER BY set_number ASC")
    fun getBySessionExercise(sessionExerciseId: Long): Flow<List<WorkoutSetEntity>>

    @Query(
        """
        SELECT ws.* FROM workout_set ws
        INNER JOIN session_exercise se ON ws.session_exercise_id = se.id
        WHERE se.exercise_id = :exerciseId AND ws.is_completed = 1
        ORDER BY ws.completed_at DESC
        """,
    )
    fun getHistoryByExercise(exerciseId: Long): Flow<List<WorkoutSetEntity>>
}
