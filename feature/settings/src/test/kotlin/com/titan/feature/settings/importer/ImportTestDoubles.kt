package com.titan.feature.settings.importer

import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportRequest
import com.titan.core.domain.importer.HistoryImportRun
import com.titan.core.domain.importer.HistoryImportRunner
import com.titan.core.domain.model.SessionExercise
import com.titan.core.domain.model.SessionExerciseDraft
import com.titan.core.domain.model.SessionTotals
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The worker running an import, as the screen sees it: a state the test sets, and a record of
 * what the screen asked for. Starting an import reports it running, as the real one does once
 * WorkManager has it; cancelling and dismissing are recorded and change nothing by themselves,
 * so a test says what the worker answered.
 */
class FakeHistoryImportRunner(initial: HistoryImportRun = HistoryImportRun.None) : HistoryImportRunner {

    val run = MutableStateFlow(initial)
    val started = mutableListOf<HistoryImportRequest>()
    var cancels = 0
        private set
    var dismissals = 0
        private set

    /** When set, starting throws it — WorkManager refusing the work. */
    var startFailure: Throwable? = null

    override fun observe(): Flow<HistoryImportRun> = run

    override suspend fun start(request: HistoryImportRequest) {
        startFailure?.let { throw it }
        started += request
        run.value = HistoryImportRun.Running(request, HistoryImportProgress.Reading)
    }

    override suspend fun cancel() {
        cancels++
    }

    override suspend fun dismiss() {
        dismissals++
        run.value = HistoryImportRun.None
    }
}

/** The one read the import screen makes of the sessions: whether a workout is in progress. */
class FakeActiveSessions(active: WorkoutSession? = null) : SessionRepository {

    val active = MutableStateFlow(active)

    override fun getActive(): Flow<WorkoutSession?> = active

    override fun getAll(): Flow<List<WorkoutSession>> = unsupported()
    override fun getById(id: Long): Flow<WorkoutSession?> = unsupported()
    override fun getExercises(sessionId: Long): Flow<List<SessionExercise>> = unsupported()
    override fun getSets(sessionExerciseId: Long): Flow<List<WorkoutSet>> = unsupported()
    override fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>> = unsupported()
    override fun getPreviousSets(sessionId: Long): Flow<Map<Long, List<WorkoutSet>>> = unsupported()
    override fun getTotals(): Flow<Map<Long, SessionTotals>> = unsupported()
    override suspend fun countFinishedBy(atMillis: Long): Int = unsupported()
    override suspend fun insert(session: WorkoutSession): Long = unsupported()
    override suspend fun insertExercises(
        sessionId: Long,
        exercises: List<SessionExerciseDraft>,
        createdAt: Long,
    ): List<Long> = unsupported()

    override suspend fun insertSet(set: WorkoutSet): Long = unsupported()
    override suspend fun insertSets(sets: List<WorkoutSet>): List<Long> = unsupported()
    override suspend fun updateSet(set: WorkoutSet) = unsupported()
    override suspend fun updateSets(sets: List<WorkoutSet>) = unsupported()
    override suspend fun deleteSet(setId: Long) = unsupported()
    override suspend fun updateExercise(exercise: SessionExercise) = unsupported()
    override suspend fun updateExercises(exercises: List<SessionExercise>) = unsupported()
    override suspend fun deleteExercise(sessionExerciseId: Long) = unsupported()
    override suspend fun update(session: WorkoutSession) = unsupported()
    override suspend fun delete(id: Long) = unsupported()
    override suspend fun <T> atomically(block: suspend () -> T): T = unsupported()

    /** The import screen asks one question of the sessions. Anything else is a change of behaviour. */
    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("the import screen only asks for the active session")
}

/** A workout that is still open. */
val OPEN_WORKOUT = WorkoutSession(id = 7, lastActiveAt = 1L, createdAt = 1L, updatedAt = 1L)
