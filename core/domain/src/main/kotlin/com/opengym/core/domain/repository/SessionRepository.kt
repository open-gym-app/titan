package com.opengym.core.domain.repository

import com.opengym.core.domain.model.WorkoutSession
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    fun getAll(): Flow<List<WorkoutSession>>
    fun getById(id: Long): Flow<WorkoutSession?>
    fun getActive(): Flow<WorkoutSession?>
    suspend fun insert(session: WorkoutSession): Long
    suspend fun update(session: WorkoutSession)
    suspend fun delete(id: Long)
}
