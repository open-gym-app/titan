package com.opengym.core.domain.repository

import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    fun getAll(): Flow<List<WorkoutSession>>
    fun getById(id: Long): Flow<WorkoutSession?>
    fun getActive(): Flow<WorkoutSession?>
    fun getExercises(sessionId: Long): Flow<List<SessionExercise>>
    fun getSets(sessionExerciseId: Long): Flow<List<WorkoutSet>>

    /**
     * Every set logged against [sessionId], across all of its exercises.
     *
     * Distinct from [getSets], which takes a *session exercise* id: reading a whole
     * session through that would be one query per exercise. Implementations must
     * satisfy this with a single join on `session_exercise`, since the caller
     * (session summary, export) wants the session in one read. [WorkoutSet] carries
     * `sessionExerciseId`, so callers can still group by exercise without a
     * second query.
     */
    fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>>

    suspend fun insert(session: WorkoutSession): Long
    suspend fun update(session: WorkoutSession)
    suspend fun delete(id: Long)
}
