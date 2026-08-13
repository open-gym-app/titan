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

    /**
     * Writes a session's whole layout in one transaction, returning the new ids in the
     * order the rows were given.
     *
     * A `@Insert` over a list rather than a loop at the call site, because this is how
     * a session is laid out at the moment it starts: a failure part way through would
     * otherwise leave the user in a workout holding some of the exercises they chose,
     * with the `(session_id, position)` index the only thing saying which are missing.
     *
     * `ABORT` on conflict is what makes that a failure rather than a silent
     * replacement — a second layout of the same session is a bug, not an update.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(sessionExercises: List<SessionExerciseEntity>): List<Long>

    @Update
    suspend fun update(sessionExercise: SessionExerciseEntity)

    @Query("DELETE FROM session_exercise WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM session_exercise WHERE session_id = :sessionId ORDER BY position ASC")
    fun getBySession(sessionId: Long): Flow<List<SessionExerciseEntity>>
}
