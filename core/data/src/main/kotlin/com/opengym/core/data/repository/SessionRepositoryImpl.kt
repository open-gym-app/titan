package com.opengym.core.data.repository

import com.opengym.core.data.db.dao.SessionExerciseDao
import com.opengym.core.data.db.dao.WorkoutSessionDao
import com.opengym.core.data.db.dao.WorkoutSetDao
import com.opengym.core.data.mapper.SessionExerciseMapper
import com.opengym.core.data.mapper.SessionMapper
import com.opengym.core.data.mapper.WorkoutSetMapper
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Workout sessions and their contents, over the three DAOs that own the rows.
 *
 * Three DAOs rather than one because the session, its exercises and its sets are
 * three tables and the interface reads them separately; what it does *not* do is
 * read them separately when it wants the whole session. [getSetsForSession] is one
 * join, not a fan-out over [getExercises] — see its contract.
 *
 * A cascade delete on `workout_session` takes its exercises and their sets with it,
 * so [delete] is the single DAO call it looks like; the schema does the rest.
 */
class SessionRepositoryImpl @Inject constructor(
    private val sessionDao: WorkoutSessionDao,
    private val sessionExerciseDao: SessionExerciseDao,
    private val setDao: WorkoutSetDao,
    private val sessionMapper: SessionMapper,
    private val sessionExerciseMapper: SessionExerciseMapper,
    private val setMapper: WorkoutSetMapper,
) : SessionRepository {

    override fun getAll(): Flow<List<WorkoutSession>> =
        sessionDao.getAll().map { rows -> rows.map(sessionMapper::toDomain) }

    override fun getById(id: Long): Flow<WorkoutSession?> =
        sessionDao.getById(id).map { row -> row?.let(sessionMapper::toDomain) }

    override fun getActive(): Flow<WorkoutSession?> =
        sessionDao.getActive().map { row -> row?.let(sessionMapper::toDomain) }

    override fun getExercises(sessionId: Long): Flow<List<SessionExercise>> =
        sessionExerciseDao.getBySession(sessionId).map { rows -> rows.map(sessionExerciseMapper::toDomain) }

    override fun getSets(sessionExerciseId: Long): Flow<List<WorkoutSet>> =
        setDao.getBySessionExercise(sessionExerciseId).map { rows -> rows.map(setMapper::toDomain) }

    override fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>> =
        setDao.getBySession(sessionId).map { rows -> rows.map(setMapper::toDomain) }

    override suspend fun insert(session: WorkoutSession): Long =
        sessionDao.insert(sessionMapper.toEntity(session))

    override suspend fun insertExercises(
        sessionId: Long,
        exercises: List<SessionExerciseDraft>,
        createdAt: Long,
    ): List<Long> = sessionExerciseDao.insertAll(
        sessionExerciseMapper.toEntities(
            sessionId = sessionId,
            drafts = exercises,
            createdAt = createdAt,
        ),
    )

    override suspend fun update(session: WorkoutSession) =
        sessionDao.update(sessionMapper.toEntity(session))

    override suspend fun delete(id: Long) = sessionDao.delete(id)
}
