package com.opengym.core.domain.repository

import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseType
import kotlinx.coroutines.flow.Flow

interface ExerciseRepository {
    fun getAll(): Flow<List<Exercise>>
    fun getById(id: Long): Flow<Exercise?>
    fun getByType(type: ExerciseType): Flow<List<Exercise>>
    suspend fun insert(exercise: Exercise): Long
}
