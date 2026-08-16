package com.opengym.core.domain.repository

import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionTotals
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    fun getAll(): Flow<List<WorkoutSession>>
    fun getById(id: Long): Flow<WorkoutSession?>
    fun getActive(): Flow<WorkoutSession?>
    fun getExercises(sessionId: Long): Flow<List<SessionExercise>>
    fun getSets(sessionExerciseId: Long): Flow<List<WorkoutSet>>

    /**
     * Every set logged against [sessionId], across all of its exercises.
     *
     * Distinct from [getSets], which takes a *session exercise* id: reading a whole
     * session through that would be one query per exercise. Implementations must
     * satisfy this with a single join on `session_exercise`, since the caller
     * (session summary, export) wants the session in one read. [WorkoutSet] carries
     * `sessionExerciseId`, so callers can still group by exercise without a
     * second query.
     */
    fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>>

    /**
     * The set count and volume of **every** session, keyed by session id.
     *
     * The history list's read. It shows a figure per row and there is no upper bound on how
     * many rows a user accumulates, so the only shape that works is one aggregate over the
     * whole table: implementations must satisfy this with a single grouped query, never with
     * a [getSetsForSession] per session. That fan-out is an observer per row of a list that
     * grows for the life of the install.
     *
     * A session with no completed sets — one abandoned, or one still being logged — has no
     * entry rather than a zeroed one. The absence is the honest answer for a row whose
     * figures are not yet a fact, and a caller drawing a list treats it as such; see
     * [SessionTotals] for why the duration is not here.
     */
    fun getTotals(): Flow<Map<Long, SessionTotals>>

    suspend fun insert(session: WorkoutSession): Long

    /**
     * Adds [exercises] to the **end** of [sessionId]'s layout, in the order given, and
     * returns the ids of the rows written in that same order.
     *
     * One call rather than an insert per exercise, and applied in a single
     * transaction: a session is laid out once, at the moment it starts, and a failure
     * halfway would leave the user in a workout holding three of the eight exercises
     * they chose with no way to tell which five are missing.
     *
     * Numbering is the store's job for the reason [SessionExerciseDraft] has no
     * position field — `session_exercise` carries a unique `(session_id, position)`
     * index, and a caller that hand-numbers is one reorder away from violating it.
     * **The list must therefore be in the order the exercises will be performed**,
     * which is also the order the superset grouping is read in.
     *
     * Appending rather than laying out from zero is what lets an exercise be added to a
     * session that is already running ([com.opengym.core.domain.usecase.AddSessionExercisesUseCase]).
     * It is the same operation at the moment a session *starts*, since the session is
     * empty then — which is why there is one write path here rather than two that would
     * have to keep agreeing about how a position is chosen.
     *
     * An empty list writes nothing and returns nothing. A session with no exercises is
     * a legal, if unusual, starting point — the user adds them as they go — so it is
     * not an error to lay out none.
     *
     * @param createdAt stamped on every row's `created_at` *and* `updated_at`. Supplied
     *   by the caller rather than read off a clock in here, as [insert] takes the
     *   session's own timestamps: the use cases own the clock in this codebase, which is
     *   what makes them testable without one.
     */
    suspend fun insertExercises(
        sessionId: Long,
        exercises: List<SessionExerciseDraft>,
        createdAt: Long,
    ): List<Long>

    /**
     * Writes [set] against the `session_exercise` row it names, and returns the id of
     * the row that now holds it.
     *
     * One set at a time, unlike [insertExercises], because that is how they arrive: a
     * session is laid out once and then logged a set at a time over an hour, so there
     * is no batch to write and nothing for a transaction to make atomic.
     *
     * **The bounds are not checked here.** `SetValidator` is a check a caller runs
     * rather than a gate the write path forces it through — see PERSISTENCE.md — so
     * anything reaching this must have run it first.
     * [com.opengym.core.domain.usecase.LogSetUseCase] is the path that does, and is
     * what a screen should be calling.
     *
     * @param set with `id = 0`, the value [WorkoutSet] defaults to: the row does not
     *   exist yet, and the store assigns its id. `setNumber` is the caller's, unlike
     *   an exercise's position, because `workout_set` is unique on
     *   `(session_exercise_id, set_number)` and a set can legitimately be written
     *   against an exercise that already has some — there is no list here for the
     *   store to take an order from.
     */
    suspend fun insertSet(set: WorkoutSet): Long

    /**
     * Replaces one `session_exercise` row.
     *
     * Narrow on purpose: the only field anything currently edits is `supersetGroup`, which
     * has to be repaired when a removal leaves a group with a single member. Reordering —
     * which rewrites `position` across several rows at once — is #100's, and will want a
     * transactional multi-row write rather than a loop over this.
     */
    suspend fun updateExercise(exercise: SessionExercise)

    /**
     * Removes one exercise from its session, and its sets with it — the schema cascades from
     * `session_exercise` to `workout_set`.
     *
     * That cascade is why the caller checks first.
     * [com.opengym.core.domain.usecase.RemoveSessionExerciseUseCase] refuses an exercise that
     * has anything logged against it, because silently deleting sets a user performed is the
     * one outcome no confirmation makes acceptable; this method is the blunt instrument under
     * that rule and enforces nothing itself.
     *
     * The surviving exercises keep their positions, so a removal leaves a gap. Nothing reads
     * position as a dense index — everything that cares orders by it — and a renumbering write
     * across every surviving row would be a second thing to get wrong for no reader's benefit.
     */
    suspend fun deleteExercise(sessionExerciseId: Long)

    suspend fun update(session: WorkoutSession)
    suspend fun delete(id: Long)
}
