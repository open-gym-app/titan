package com.opengym.core.domain.repository

import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.Tag
import kotlinx.coroutines.flow.Flow

interface ExerciseRepository {
    fun getAll(): Flow<List<Exercise>>
    fun getById(id: Long): Flow<Exercise?>
    fun getByType(type: ExerciseType): Flow<List<Exercise>>
    fun getByEquipmentType(equipmentType: EquipmentType): Flow<List<Exercise>>
    fun searchByPartialName(query: String): Flow<List<Exercise>>

    /**
     * The muscle groups [exerciseId] trains, each carrying its `is_primary` flag.
     *
     * Returns links rather than bare [com.opengym.core.domain.model.MuscleGroup]s so a
     * caller can weight primary against secondary — what
     * [com.opengym.core.domain.stats.MuscleVolumeCalculator] needs — without a second
     * query for the flags.
     *
     * Ordered primary first, then by muscle group name, so a detail screen can render
     * the list as it arrives.
     */
    fun getMuscleGroupLinks(exerciseId: Long): Flow<List<ExerciseMuscleGroupLink>>

    fun getTags(exerciseId: Long): Flow<List<Tag>>
    suspend fun insert(exercise: Exercise): Long
    suspend fun update(exercise: Exercise)
    suspend fun delete(id: Long)
}
