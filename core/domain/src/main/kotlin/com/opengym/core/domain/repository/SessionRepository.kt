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
    suspend fun insert(session: WorkoutSession): Long
    suspend fun update(session: WorkoutSession)
    suspend fun delete(id: Long)
}
