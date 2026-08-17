package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Hand-written fakes rather than mocks: these tests exercise a state machine over
 * several calls, and asserting on the resulting store is both shorter and stricter
 * than stubbing each read in turn.
 */

/** Clock whose reading only changes when a test says so. */
class MutableClock(var now: Long = 1_000L) : Clock {
    override fun nowMillis(): Long = now

    /** Advances the clock and returns the new reading. */
    fun advance(millis: Long): Long {
        now += millis
        return now
    }
}

class FakeSessionStateRepository(initial: SessionState = SessionState.Idle) : SessionStateRepository {

    private val state = MutableStateFlow(initial)

    /** Every state this repository has held, oldest first, for transition assertions. */
    val recorded = mutableListOf(initial)

    override fun observe(): Flow<SessionState> = state.asStateFlow()

    override suspend fun update(state: SessionState) {
        this.state.value = state
        recorded += state
    }

    val current: SessionState get() = state.value
}

class FakeSessionRepository : SessionRepository {

    private val sessions = MutableStateFlow<Map<Long, WorkoutSession>>(emptyMap())
    private val exercises = MutableStateFlow<Map<Long, List<SessionExercise>>>(emptyMap())
    private val sets = MutableStateFlow<Map<Long, List<WorkoutSet>>>(emptyMap())
    private var nextSessionId = 1L
    private var nextExerciseId = 1L
    private var nextSetId = 1L

    /** Number of times [update] was called, to catch writes that should not have happened. */
    var updateCount = 0
        private set

    /** Reads of a whole session's sets, to pin down that summarising takes one. */
    var setsForSessionReads = 0
        private set

    /** Per-exercise set reads, which the summary must no longer make. */
    var setsByExerciseReads = 0
        private set

    override fun getAll(): Flow<List<WorkoutSession>> = sessions.map { it.values.toList() }

    override fun getById(id: Long): Flow<WorkoutSession?> = sessions.map { it[id] }

    override fun getActive(): Flow<WorkoutSession?> = sessions.map { stored ->
        stored.values.filter { it.isInProgress }.maxByOrNull { it.lastActiveAt }
    }

    override fun getExercises(sessionId: Long): Flow<List<SessionExercise>> =
        exercises.map { it[sessionId].orEmpty() }

    override fun getSets(sessionExerciseId: Long): Flow<List<WorkoutSet>> =
        sets.map { stored ->
            setsByExerciseReads++
            stored[sessionExerciseId].orEmpty()
        }

    override fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>> =
        combine(exercises, sets) { exercisesBySession, setsByExercise ->
            setsForSessionReads++
            exercisesBySession[sessionId].orEmpty().flatMap { setsByExercise[it.id].orEmpty() }
        }

    /**
     * Runs inside [insert] before the row is stored. Lets a test park a caller
     * mid-transition and let a second one race it — the window a check-then-act
     * start would lose.
     */
    var beforeInsert: (suspend () -> Unit)? = null

    override suspend fun insert(session: WorkoutSession): Long {
        beforeInsert?.invoke()
        val id = nextSessionId++
        sessions.value += id to session.copy(id = id)
        return id
    }

    /**
     * Thrown by [insertExercises] instead of storing, when set. The one way to reach
     * `StartPlannedSessionUseCase`'s rollback: the layout failing after the session row
     * is written is a store failure, and there is no argument a caller can pass that
     * produces one.
     */
    var exerciseInsertFailure: Throwable? = null

    override suspend fun insertExercises(
        sessionId: Long,
        exercises: List<SessionExerciseDraft>,
        createdAt: Long,
    ): List<Long> {
        exerciseInsertFailure?.let { throw it }

        // Numbered from the order given and appended to whatever the session holds, per
        // the contract — which is what lets a test assert that the grouping and the
        // order the user picked survived the trip.
        val existing = this.exercises.value[sessionId].orEmpty()
        val stored = exercises.mapIndexed { index, draft ->
            SessionExercise(
                id = nextExerciseId++,
                sessionId = sessionId,
                exerciseId = draft.exerciseId,
                position = existing.size + index,
                supersetGroup = draft.supersetGroup,
                templateExerciseId = draft.templateExerciseId,
                notes = draft.notes,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        }
        this.exercises.value += sessionId to existing + stored
        return stored.map(SessionExercise::id)
    }

    override suspend fun update(session: WorkoutSession) {
        updateCount++
        sessions.value += session.id to session
    }

    override suspend fun delete(id: Long) {
        sessions.value -= id
        exercises.value -= id
    }

    /** Direct read, bypassing the Flow, for assertions. */
    fun stored(id: Long): WorkoutSession? = sessions.value[id]

    /** Direct read of a session's layout, bypassing the Flow, for assertions. */
    fun storedExercises(sessionId: Long): List<SessionExercise> =
        exercises.value[sessionId].orEmpty()

    /** Seeds an exercise on [sessionId], in [supersetGroup] or standalone, and returns its id. */
    fun addExercise(sessionId: Long, exerciseId: Long = 1L, supersetGroup: Int? = null): Long {
        val id = nextExerciseId++
        val existing = exercises.value[sessionId].orEmpty()
        exercises.value += sessionId to existing + SessionExercise(
            id = id,
            sessionId = sessionId,
            exerciseId = exerciseId,
            position = existing.size,
            supersetGroup = supersetGroup,
            createdAt = 0,
            updatedAt = 0,
        )
        return id
    }

    /** Seeds a set on [sessionExerciseId]. */
    fun addSet(
        sessionExerciseId: Long,
        reps: Int? = 10,
        weight: Long? = 50_000L,
        isCompleted: Boolean = true,
    ) {
        val existing = sets.value[sessionExerciseId].orEmpty()
        store(
            sessionExerciseId,
            WorkoutSet(
                sessionExerciseId = sessionExerciseId,
                setNumber = existing.size + 1,
                reps = reps,
                weight = weight,
                isCompleted = isCompleted,
                completedAt = if (isCompleted) 1L else 0L,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
    }

    /**
     * Seeds [set] as given, returning it with the id and set number the store assigned.
     *
     * The defaulted overload above is for tests that only care how *many* sets a session
     * holds; this one is for tests that care what is in them — an integration run logging
     * a real set of reps and weight, say, whose figures then have to come back out of the
     * summary.
     */
    fun addSet(sessionExerciseId: Long, set: WorkoutSet): WorkoutSet {
        val existing = sets.value[sessionExerciseId].orEmpty()
        return store(
            sessionExerciseId,
            set.copy(sessionExerciseId = sessionExerciseId, setNumber = existing.size + 1),
        )
    }

    /** Files [set] under [sessionExerciseId] with a fresh id, and returns it as stored. */
    private fun store(sessionExerciseId: Long, set: WorkoutSet): WorkoutSet {
        val stored = set.copy(id = nextSetId++)
        sets.value += sessionExerciseId to sets.value[sessionExerciseId].orEmpty() + stored
        return stored
    }
}
