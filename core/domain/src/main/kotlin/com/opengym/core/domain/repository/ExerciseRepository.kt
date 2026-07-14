package com.opengym.core.domain.repository

import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.Tag
import kotlinx.coroutines.flow.Flow

interface ExerciseRepository {
    fun getAll(): Flow<List<Exercise>>
    fun getById(id: Long): Flow<Exercise?>
    fun getByType(type: ExerciseType): Flow<List<Exercise>>
    fun getByEquipmentType(equipmentType: EquipmentType): Flow<List<Exercise>>
    fun searchByName(query: String): Flow<List<Exercise>>
    fun getMuscleGroups(exerciseId: Long): Flow<List<MuscleGroup>>
    fun getTags(exerciseId: Long): Flow<List<Tag>>
    suspend fun insert(exercise: Exercise): Long
    suspend fun update(exercise: Exercise)
    suspend fun delete(id: Long)
}
