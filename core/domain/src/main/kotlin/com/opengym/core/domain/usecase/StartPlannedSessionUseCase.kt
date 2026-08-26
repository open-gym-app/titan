package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.PlannedSet
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionPlan
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.superset.Supersets

/**
 * Turns a [SessionPlan] into a running session: the `workout_session` row, its
 * `session_exercise` rows, the `workout_set` rows for whatever the plan plans, and the
 * state machine moved to [SessionState.Active].
 *
 * The one call the new-session flow (#25) makes when the user commits. Everything
 * before it — choosing a template, picking exercises, changing their mind — writes
 * nothing at all, which is what makes backing out of that flow free: there is no
 * partial session row to clean up because there was never a session row.
 *
 * ### Why this is not part of [StartSessionUseCase]
 *
 * That one opens a session and says nothing about what is in it, which is what
 * [ResumeSessionUseCase] and a future quick-start both want. This one is the composite
 * the flow needs, and keeping them apart is what lets "the user chose a template" and
 * "the user began training" stay two separately cancellable decisions — see
 * [ApplyTemplateUseCase], which reads a template without starting anything.
 *
 * ### The plan's sets are written as sets
 *
 * A plan that carries [PlannedSet]s — one started from a template — lays them down as real
 * `workout_set` rows with `is_completed = 0`, which is what PERSISTENCE.md calls a
 * planned-but-unperformed set: it is in the table, under its number, and it counts towards
 * no figure until the lifter ticks it off. So a four-set template opens a four-set workout
 * with the weights already in the fields, and performing it is four taps rather than four
 * sets typed from nothing.
 *
 * `completed_at` is written as `0`, the placeholder the contract names for exactly this row.
 * It is not a time and must never be read as one; [MarkSetCompletedUseCase] stamps the real
 * moment when the set is ticked, which is the only point at which one exists.
 *
 * Nothing is validated on the way down, deliberately. A planned set is allowed to be empty —
 * "do a fourth set, weight to be decided" is a plan — and `SetValidator` only holds a set to
 * its exercise's logging mode once it is *completed*, which is a rule the tick already
 * enforces. Refusing a blank plan here would refuse the ordinary case.
 *
 * ### Failure leaves nothing behind
 *
 * The session row is written before the exercises are, because the exercises need its
 * id. If the layout then fails, the session is deleted and the state machine put back
 * to [SessionState.Idle] rather than left [SessionState.Active] over a workout with no
 * exercises in it — a state whose only exit is finishing a session that logged nothing,
 * which [FinishSessionUseCase] refuses. The rollback is best-effort and outside
 * [SessionLifecycleLock]: it undoes a transition this call made, and the only writer
 * that could interleave is another start, which the lock already serialises against
 * the transition itself.
 */
class StartPlannedSessionUseCase(
    private val startSession: StartSessionUseCase,
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
) {

    /**
     * @return the id of the session that is now running.
     *
     * Fails with whatever [StartSessionUseCase] refuses on — in practice
     * [com.opengym.core.domain.model.SessionError.SessionAlreadyActive], which a caller
     * should offer to resume rather than treat as a dead end — or with whatever the
     * store threw while laying the exercises out.
     */
    suspend operator fun invoke(plan: SessionPlan): Result<Long> {
        val exercises = plan.exercises.normalizeSupersets()

        val started = startSession(templateId = plan.templateId, name = plan.name)
            .getOrElse { return Result.failure(it) }

        return runCatching {
            val exerciseIds = sessionRepository.insertExercises(
                sessionId = started.sessionId,
                exercises = exercises,
                // The session's own start, not a second reading: the exercises were laid
                // out as part of starting it, and two timestamps a millisecond apart on
                // rows written by one action is a distinction nothing wants to read.
                createdAt = started.startedAt,
            )
            // One write for the whole workout rather than one per exercise: the plan is laid
            // down in a single action, and the ids come back in the order the drafts went in
            // — the contract `insertExercises` gives — so the two lists zip.
            sessionRepository.insertSets(
                exercises.zip(exerciseIds) { draft, id -> draft.plannedSets(id, started.startedAt) }
                    .flatten(),
            )
        }
            .map { started.sessionId }
            .onFailure { discard(started.sessionId) }
    }

    /**
     * Undoes [invoke]'s own half-finished start.
     *
     * The row goes first: a delete that fails leaves a session that
     * [StartSessionUseCase] will refuse to start alongside, which is recoverable — the
     * user is offered the resume path — where a state reset without it would hide that
     * session entirely. Both are best-effort, because the caller is already reporting
     * the original failure and a rollback that threw would replace it with a less
     * useful one.
     */
    private suspend fun discard(sessionId: Long) {
        runCatching { sessionRepository.delete(sessionId) }
        runCatching { sessionStateRepository.update(SessionState.Idle) }
    }
}

/**
 * The list with its superset grouping made canonical — groups of one dissolved, the
 * survivors renumbered densely from 0 — per [Supersets.normalize].
 *
 * Done on the way in rather than asked of the caller, matching the template write path.
 * A plan reaches here from two places, and both can produce a grouping worth repairing:
 * a template applied after one of a pair was removed from it, and an exercise dropped
 * from the review panel before starting.
 *
 * A grouping that [Supersets.validate] would refuse — a superset split by an exercise
 * that is not in it — is *not* repaired, because repairing it means moving an exercise
 * and that is a decision about the workout. It is also not refused here: nothing in
 * this flow can produce one, since both callers hand over a list already in performance
 * order, and turning a store write into a validation error for a case the UI cannot
 * reach would be a failure path nobody could exercise.
 */
private fun List<SessionExerciseDraft>.normalizeSupersets(): List<SessionExerciseDraft> {
    val normalized = Supersets.normalize(map(SessionExerciseDraft::supersetGroup))
    return mapIndexed { index, draft -> draft.copy(supersetGroup = normalized[index]) }
}

/**
 * This exercise's plan as the rows that hold it, numbered from 1 in the order planned.
 *
 * `createdAt` and `updatedAt` are the session's own start, matching the exercises they hang
 * off. `completedAt` is the `0` placeholder rather than that timestamp, and the difference is
 * the point: the other two say when the row was written, which is a fact, and this one would
 * be claiming the set was performed at the moment the workout began.
 */
private fun SessionExerciseDraft.plannedSets(
    sessionExerciseId: Long,
    createdAt: Long,
): List<WorkoutSet> =
    sets.mapIndexed { index, planned ->
        WorkoutSet(
            sessionExerciseId = sessionExerciseId,
            setNumber = index + 1,
            reps = planned.reps,
            weight = planned.weight,
            setType = planned.setType,
            completedAt = UNPERFORMED,
            isCompleted = false,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

/**
 * What `workout_set.completed_at` holds for a set nobody has performed.
 *
 * The column is NOT NULL — see PERSISTENCE.md — so a row that has not happened yet needs a
 * value that reads as "no time at all", and 0 is the one the contract names. Every aggregate
 * filters on `is_completed` before it looks at the timestamp, so the placeholder never reaches
 * one.
 */
private const val UNPERFORMED = 0L
