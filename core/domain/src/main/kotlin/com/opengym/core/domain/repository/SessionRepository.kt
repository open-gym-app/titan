package com.opengym.core.domain.repository

import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionExerciseDraft
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

    suspend fun insert(session: WorkoutSession): Long

    /**
     * Lays [exercises] out on [sessionId], numbered from position 0 in the order
     * given, and returns the ids of the rows written in that same order.
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

    suspend fun update(session: WorkoutSession)
    suspend fun delete(id: Long)
}
