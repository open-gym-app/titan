package com.titan.core.data.repository

import com.titan.core.data.db.dao.NOT_INSERTED
import com.titan.core.data.db.dao.WorkoutSessionDao
import com.titan.core.data.db.entity.WorkoutSessionEntity
import com.titan.core.data.mapper.SessionMapper
import com.titan.core.data.mapper.decodeEnum
import com.titan.core.domain.model.Discipline
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.repository.FinishedSessionActivity
import com.titan.core.domain.repository.HealthConnectSessionRepository
import javax.inject.Inject

/**
 * The local side of the Health Connect sync, as two reads on `workout_session` and one join for
 * the disciplines.
 *
 * The disciplines come in a second query rather than as a join on the first, because a session
 * is one row and its exercises are many: joined, every session would come back once per exercise
 * and have to be folded back together here, for the same answer two indexed reads give directly.
 */
class HealthConnectSessionRepositoryImpl @Inject constructor(
    private val sessionDao: WorkoutSessionDao,
    private val sessionMapper: SessionMapper,
) : HealthConnectSessionRepository {

    override suspend fun getFinished(sessionId: Long): FinishedSessionActivity? =
        sessionDao.getFinished(sessionId)?.let { withDisciplines(listOf(it)).single() }

    override suspend fun getFinishedStartedBetween(
        startedFrom: Long,
        startedUntil: Long,
    ): List<FinishedSessionActivity> = withDisciplines(sessionDao.getFinishedStartedBetween(startedFrom, startedUntil))

    override suspend fun insertImported(sessions: List<WorkoutSession>): Int {
        require(sessions.all(WorkoutSession::isImported)) { "only sessions read from Health Connect are imported" }
        return sessionDao.insertImported(sessions.map(sessionMapper::toEntity)).count { it != NOT_INSERTED }
    }

    private suspend fun withDisciplines(rows: List<WorkoutSessionEntity>): List<FinishedSessionActivity> {
        // An imported session has no exercises to look up, so it is not asked about.
        val logged = rows.filter { it.healthConnectId == null }.map(WorkoutSessionEntity::id)
        val disciplines = logged
            // Under SQLite's bound-parameter limit, which the oldest Android this app runs on
            // still sets at 999. A month of sessions is nowhere near it; a test is not a month.
            .chunked(MAX_BOUND_IDS)
            .flatMap { ids -> sessionDao.getPerformedDisciplines(ids) }
            .groupBy({ it.sessionId }, { decodeEnum<Discipline>(it.discipline) })

        return rows.map { row ->
            FinishedSessionActivity(
                session = sessionMapper.toDomain(row),
                disciplines = disciplines[row.id].orEmpty(),
            )
        }
    }

    private companion object {
        const val MAX_BOUND_IDS = 900
    }
}
