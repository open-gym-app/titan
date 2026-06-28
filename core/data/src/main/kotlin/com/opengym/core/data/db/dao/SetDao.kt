package com.opengym.core.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.opengym.core.data.db.entity.SetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SetDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(set: SetEntity): Long

    @Update
    suspend fun update(set: SetEntity)

    @Delete
    suspend fun delete(set: SetEntity)

    @Query("SELECT * FROM sets WHERE session_id = :sessionId ORDER BY set_order ASC")
    fun getBySession(sessionId: Long): Flow<List<SetEntity>>

    @Query("SELECT * FROM sets WHERE exercise_id = :exerciseId ORDER BY session_id DESC, set_order ASC")
    fun getByExercise(exerciseId: Long): Flow<List<SetEntity>>
}
