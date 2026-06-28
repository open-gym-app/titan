package com.opengym.core.data.repository

import com.opengym.core.data.db.dao.SessionDao
import com.opengym.core.data.db.mapper.toDomain
import com.opengym.core.data.db.mapper.toEntity
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SessionRepositoryImpl @Inject constructor(
    private val dao: SessionDao,
) : SessionRepository {

    override fun getAll(): Flow<List<WorkoutSession>> =
        dao.getAll().map { list -> list.map { it.toDomain() } }

    override fun getById(id: Long): Flow<WorkoutSession?> =
        dao.getById(id).map { it?.toDomain() }

    override fun getActive(): Flow<WorkoutSession?> =
        dao.getActive().map { it?.toDomain() }

    override suspend fun insert(session: WorkoutSession): Long =
        dao.insert(session.toEntity())

    override suspend fun update(session: WorkoutSession) =
        dao.update(session.toEntity())

    override suspend fun delete(id: Long) =
        dao.delete(id)
}
