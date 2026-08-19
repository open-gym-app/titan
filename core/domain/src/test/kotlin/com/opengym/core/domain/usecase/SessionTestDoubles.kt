package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SessionTotals
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
     * Nothing. No use case reads the previous session's sets — it is the active screen's
     * reference column and nothing in the domain compares against it — so a fake that answered
     * would be a second, unexercised implementation of a query with real rules in it.
     */
    override fun getPreviousSets(sessionId: Long): Flow<Map<Long, List<WorkoutSet>>> =
        throw UnsupportedOperationException("no use case reads previous performance")

    /**
     * The same aggregate the real store computes in SQL, computed here over what is held.
     *
     * Derived rather than stubbed so it cannot drift from [getSetsForSession]: a fake whose
     * totals were set independently of its sets would let a test pass on figures the
     * database could never produce. Sessions with no completed set are absent, per the
     * contract.
     */
    override fun getTotals(): Flow<Map<Long, SessionTotals>> =
        combine(exercises, sets) { exercisesBySession, setsByExercise ->
            exercisesBySession.mapNotNull { (sessionId, sessionExercises) ->
                val performed = sessionExercises
                    .flatMap { setsByExercise[it.id].orEmpty() }
                    .filter(WorkoutSet::isCompleted)

                performed.takeIf { it.isNotEmpty() }?.let {
                    sessionId to SessionTotals(
                        totalSets = it.size,
                        totalVolume = it.sumOf { set ->
                            (set.weight ?: 0L) * (set.reps ?: 0).toLong()
                        },
                    )
                }
            }.toMap()
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

        // Appended past the highest position in use, per the contract — not the count, which
        // a removal makes a different number and which would then collide with a surviving
        // row on the unique `(session_id, position)` index.
        val existing = this.exercises.value[sessionId].orEmpty()
        val nextPosition = (existing.maxOfOrNull(SessionExercise::position) ?: -1) + 1
        val stored = exercises.mapIndexed { index, draft ->
            SessionExercise(
                id = nextExerciseId++,
                sessionId = sessionId,
                exerciseId = draft.exerciseId,
                position = nextPosition + index,
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

    /**
     * Thrown by [insertSet] instead of storing, when set. The only way to reach
     * `LogSetUseCase`'s store-failure branch: every other refusal it makes is one a test
     * can produce with an argument, and a store that cannot be written is not.
     */
    var setInsertFailure: Throwable? = null

    override suspend fun insertSet(set: WorkoutSet): Long {
        setInsertFailure?.let { throw it }
        return store(set.sessionExerciseId, set).id
    }

    /** Thrown by [updateSet] and [updateSets] instead of writing, when set. */
    var setUpdateFailure: Throwable? = null

    /** Every set row rewritten, oldest first, so a test can assert on what a renumbering wrote. */
    val setUpdates = mutableListOf<WorkoutSet>()

    override suspend fun updateSet(set: WorkoutSet) {
        setUpdateFailure?.let { throw it }
        setUpdates += set
        sets.value += set.sessionExerciseId to
            sets.value[set.sessionExerciseId].orEmpty()
                .map { if (it.id == set.id) set else it }
    }

    /**
     * Writes several sets at once, and checks the invariant the schema does not.
     *
     * `workout_set` carries no unique index on `(session_exercise_id, set_number)` — see
     * `SessionRepository.updateSets` — so nothing but the use cases keeps a table's numbers
     * contiguous. A fake that simply applied the list would let a renumbering that leaves two
     * sets on one number pass here and be read as "set 2 of 3" twice on a device.
     */
    override suspend fun updateSets(sets: List<WorkoutSet>) {
        setUpdateFailure?.let { throw it }
        sets.forEach { updateSet(it) }

        sets.map(WorkoutSet::sessionExerciseId).distinct().forEach { sessionExerciseId ->
            val numbers = this.sets.value[sessionExerciseId].orEmpty().map(WorkoutSet::setNumber)
            check(numbers.size == numbers.distinct().size) {
                "two sets of exercise $sessionExerciseId ended up on one number: $numbers"
            }
        }
    }

    /** Thrown by [deleteSet] instead of deleting, when set. */
    var setDeleteFailure: Throwable? = null

    override suspend fun deleteSet(setId: Long) {
        setDeleteFailure?.let { throw it }
        sets.value = sets.value.mapValues { (_, rows) -> rows.filterNot { it.id == setId } }
    }

    /**
     * Thrown by [updateExercise] instead of writing, when set. The only way to fail the
     * superset repair a removal does on its way out, which is what proves the repair cannot
     * take the removal down with it.
     */
    var exerciseUpdateFailure: Throwable? = null

    override suspend fun updateExercise(exercise: SessionExercise) {
        exerciseUpdateFailure?.let { throw it }
        exerciseUpdates += exercise
        exercises.value += exercise.sessionId to
            exercises.value[exercise.sessionId].orEmpty()
                .map { if (it.id == exercise.id) exercise else it }
    }

    /**
     * Writes several rows at once, refusing what the unique `(session_id, position)` index
     * would refuse.
     *
     * The check is the whole reason this is not a loop over [updateExercise]. The real store
     * parks the rows out of the way before writing them back — see
     * `SessionExerciseDao.rewriteLayout` — and a fake that simply applied the list would let a
     * reorder that leaves two exercises on one position pass here and abort on a device.
     */
    override suspend fun updateExercises(exercises: List<SessionExercise>) {
        exercises.forEach { updateExercise(it) }

        exercises.map(SessionExercise::sessionId).distinct().forEach { sessionId ->
            val positions = this.exercises.value[sessionId].orEmpty().map(SessionExercise::position)
            check(positions.size == positions.distinct().size) {
                "two exercises of session $sessionId ended up on one position: $positions"
            }
        }
    }

    /** Every exercise row rewritten, so a test can assert that only what changed was written. */
    val exerciseUpdates = mutableListOf<SessionExercise>()

    /** Thrown by [deleteExercise] instead of deleting, when set. */
    var exerciseDeleteFailure: Throwable? = null

    override suspend fun deleteExercise(sessionExerciseId: Long) {
        exerciseDeleteFailure?.let { throw it }
        exercises.value = exercises.value.mapValues { (_, rows) ->
            rows.filterNot { it.id == sessionExerciseId }
        }
        sets.value -= sessionExerciseId
    }

    override suspend fun update(session: WorkoutSession) {
        updateCount++
        sessions.value += session.id to session
    }

    /**
     * Deletes the session and everything under it, matching the schema's cascade from
     * `workout_session` through `session_exercise` to `workout_set`. A fake that dropped
     * the session and kept its sets would let a discard test pass over rows the real
     * store would have taken with it.
     */
    /**
     * Thrown by [delete] instead of deleting, when set. The only way to reach
     * `DiscardSessionUseCase`'s failure branch, which is what proves a discard that cannot
     * remove the row leaves the session running rather than clearing the state over it.
     */
    var deleteFailure: Throwable? = null

    /**
     * Runs inside [delete] before the row is removed. Lets a test park a caller mid-delete and
     * cancel the coroutine it is running in, which is the window a discard must not be torn in
     * half by.
     */
    var beforeDelete: (suspend () -> Unit)? = null

    override suspend fun delete(id: Long) {
        beforeDelete?.invoke()
        deleteFailure?.let { throw it }
        val orphaned = exercises.value[id].orEmpty().map(SessionExercise::id).toSet()
        sessions.value -= id
        exercises.value -= id
        sets.value -= orphaned
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

    /**
     * Drops the set numbered [setNumber] from [sessionExerciseId], leaving a gap.
     *
     * The raw deletion, without the renumbering [DeleteSetUseCase] follows it with: a table with
     * a gap in it is a state the write paths still have to survive — an older install, a torn
     * renumbering — and this is how a test stages one without going through the use case that
     * exists to prevent it.
     */
    fun removeSet(sessionExerciseId: Long, setNumber: Int) {
        sets.value += sessionExerciseId to
            sets.value[sessionExerciseId].orEmpty().filterNot { it.setNumber == setNumber }
    }

    /** Files [set] under [sessionExerciseId] with a fresh id, and returns it as stored. */
    private fun store(sessionExerciseId: Long, set: WorkoutSet): WorkoutSet {
        val stored = set.copy(id = nextSetId++)
        sets.value += sessionExerciseId to sets.value[sessionExerciseId].orEmpty() + stored
        return stored
    }
}
