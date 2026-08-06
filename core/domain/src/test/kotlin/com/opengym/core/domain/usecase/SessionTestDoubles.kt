package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionExercise
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

    /** Seeds an exercise on [sessionId] and returns its id. */
    fun addExercise(sessionId: Long, exerciseId: Long = 1L): Long {
        val id = nextExerciseId++
        val existing = exercises.value[sessionId].orEmpty()
        exercises.value += sessionId to existing + SessionExercise(
            id = id,
            sessionId = sessionId,
            exerciseId = exerciseId,
            position = existing.size,
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
        sets.value += sessionExerciseId to existing + WorkoutSet(
            id = nextSetId++,
            sessionExerciseId = sessionExerciseId,
            setNumber = existing.size + 1,
            reps = reps,
            weight = weight,
            isCompleted = isCompleted,
            completedAt = if (isCompleted) 1L else 0L,
            createdAt = 0,
            updatedAt = 0,
        )
    }
}
