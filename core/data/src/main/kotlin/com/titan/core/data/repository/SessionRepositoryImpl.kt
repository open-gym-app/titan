package com.titan.core.data.repository

import androidx.room.withTransaction
import com.titan.core.data.db.TitanDatabase
import com.titan.core.data.db.projection.PreviousSetRow
import com.titan.core.data.mapper.SessionExerciseMapper
import com.titan.core.data.mapper.SessionMapper
import com.titan.core.data.mapper.WorkoutSetMapper
import com.titan.core.domain.model.SessionExercise
import com.titan.core.domain.model.SessionExerciseDraft
import com.titan.core.domain.model.SessionTotals
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.repository.SessionRepository
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
@Suppress("TooManyFunctions") // One per `SessionRepository` method — see its own note.
class SessionRepositoryImpl @Inject constructor(
    private val database: TitanDatabase,
    private val sessionMapper: SessionMapper,
    private val sessionExerciseMapper: SessionExerciseMapper,
    private val setMapper: WorkoutSetMapper,
) : SessionRepository {

    /*
     * Reached through the database rather than injected beside it, as `LocalDataRepositoryImpl`
     * does: [atomically] needs the database itself, the three DAOs would always be its own, and
     * a constructor that takes all four invites the day they are not.
     */
    private val sessionDao get() = database.workoutSessionDao()
    private val sessionExerciseDao get() = database.sessionExerciseDao()
    private val setDao get() = database.workoutSetDao()

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

    /**
     * One query, as the contract requires — see [SessionRepository.getPreviousSets].
     *
     * The grouping is done here rather than in SQL for the reason [getTotals]'s map is built
     * here: the caller wants an exercise's reference by id, and SQLite has no shape for "a list
     * per key". What is not done here is the *choosing* — which session, and which sets count —
     * because that is the part that would be a fan-out if it left the database.
     */
    override fun getPreviousSets(sessionId: Long): Flow<Map<Long, List<WorkoutSet>>> =
        setDao.getPreviousSetsForSession(sessionId).map { rows ->
            rows.groupBy(PreviousSetRow::exerciseId) { setMapper.toDomain(it.set) }
        }

    /**
     * One grouped query, as the contract requires — see [SessionRepository.getTotals].
     *
     * The map is built here rather than being handed over as a list because every caller
     * looks a session's figures up by id, and the query's `GROUP BY` already guarantees at
     * most one row per session.
     */
    override fun getTotals(): Flow<Map<Long, SessionTotals>> =
        setDao.getTotalsBySession().map { rows ->
            rows.associate { row ->
                row.sessionId to SessionTotals(
                    totalSets = row.totalSets,
                    totalVolume = row.totalVolume,
                    timedSeconds = row.timedSeconds,
                    totalDistance = row.totalDistance,
                )
            }
        }

    /** Counted in SQLite, not over [getAll] — see [SessionRepository.countFinished]. */
    override suspend fun countFinished(): Int = sessionDao.countFinished()

    override suspend fun insert(session: WorkoutSession): Long = sessionDao.insert(sessionMapper.toEntity(session))

    override suspend fun insertExercises(
        sessionId: Long,
        exercises: List<SessionExerciseDraft>,
        createdAt: Long,
    ): List<Long> =
        sessionExerciseDao.appendAll(
            sessionId = sessionId,
            rows = sessionExerciseMapper.toEntities(
                sessionId = sessionId,
                drafts = exercises,
                createdAt = createdAt,
            ),
        )

    override suspend fun updateExercise(exercise: SessionExercise) =
        sessionExerciseDao.update(sessionExerciseMapper.toEntity(exercise))

    /**
     * One transaction, and never two rows on one position — see
     * `SessionExerciseDao.rewriteLayout`, which is where both halves of that live.
     */
    override suspend fun updateExercises(exercises: List<SessionExercise>) =
        sessionExerciseDao.rewriteLayout(exercises.map(sessionExerciseMapper::toEntity))

    override suspend fun deleteExercise(sessionExerciseId: Long) = sessionExerciseDao.delete(sessionExerciseId)

    override suspend fun insertSet(set: WorkoutSet): Long = setDao.insert(setMapper.toEntity(set))

    /** One transaction, which is Room's own for a multi-row `@Insert` — see `WorkoutSetDao`. */
    override suspend fun insertSets(sets: List<WorkoutSet>): List<Long> =
        if (sets.isEmpty()) emptyList() else setDao.insertAll(sets.map(setMapper::toEntity))

    override suspend fun updateSet(set: WorkoutSet) = setDao.update(setMapper.toEntity(set))

    /** One transaction, which is Room's own for a multi-row `@Update` — see `WorkoutSetDao`. */
    override suspend fun updateSets(sets: List<WorkoutSet>) = setDao.updateAll(sets.map(setMapper::toEntity))

    override suspend fun deleteSet(setId: Long) = setDao.delete(setId)

    override suspend fun update(session: WorkoutSession) = sessionDao.update(sessionMapper.toEntity(session))

    override suspend fun delete(id: Long) = sessionDao.delete(id)

    /**
     * Room's own transaction, which is what gives the contract its two properties for free:
     * a DAO call from inside the block — including one that is `@Transaction` itself — joins
     * it rather than opening a second, and a `Flow` read inside it runs on the transaction's
     * dispatcher and so sees its writes.
     */
    override suspend fun <T> atomically(block: suspend () -> T): T = database.withTransaction { block() }
}
