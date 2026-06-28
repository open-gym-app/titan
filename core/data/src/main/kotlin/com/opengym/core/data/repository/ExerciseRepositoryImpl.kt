package com.opengym.core.data.repository

import com.opengym.core.data.db.dao.ExerciseDao
import com.opengym.core.data.db.mapper.toDomain
import com.opengym.core.data.db.mapper.toEntity
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.repository.ExerciseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ExerciseRepositoryImpl @Inject constructor(
    private val dao: ExerciseDao,
) : ExerciseRepository {

    override fun getAll(): Flow<List<Exercise>> =
        dao.getAll().map { list -> list.map { it.toDomain() } }

    override fun getById(id: Long): Flow<Exercise?> =
        dao.getById(id).map { it?.toDomain() }

    override fun getByType(type: ExerciseType): Flow<List<Exercise>> =
        dao.getByType(type.name).map { list -> list.map { it.toDomain() } }

    override suspend fun insert(exercise: Exercise): Long =
        dao.insert(exercise.toEntity())
}
