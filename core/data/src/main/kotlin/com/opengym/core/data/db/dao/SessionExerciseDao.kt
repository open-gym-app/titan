package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opengym.core.data.db.entity.SessionExerciseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionExerciseDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(sessionExercise: SessionExerciseEntity): Long

    @Update
    suspend fun update(sessionExercise: SessionExerciseEntity)

    @Query("DELETE FROM session_exercise WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM session_exercise WHERE session_id = :sessionId ORDER BY position ASC")
    fun getBySession(sessionId: Long): Flow<List<SessionExerciseEntity>>
}
