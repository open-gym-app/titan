package com.titan.core.domain.usecase

import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.SessionError
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.SessionStateRepository
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Lays planned sets into an exercise that is already in the running workout, between what has
 * been performed and what is still to come.
 *
 * The warm-up card's ramp-up (#214) is what this exists for. Its two light sets hang off the
 * session's first compound lift, and that lift is already in the workout — often with its
 * working sets already laid down by a template — so there is no draft to carry them on, and
 * [AddSessionExercisesUseCase], which writes a plan only under exercises it has just added,
 * cannot place them. What the card needs is a plan written *into* an existing table.
 *
 * ### Where in the table
 *
 * After the last set the lifter has ticked, and in front of every set they have not. A ramp-up
 * drawn below the working sets is a warm-up performed after the work, which is not a warm-up;
 * one drawn above a set already performed would be a table claiming the lifter warmed up after
 * lifting. The table is split into what is ticked and what is not — not by comparing numbers,
 * because ticking is not ordered: nothing stops a lifter ticking set 3 before set 2 — and each
 * half keeps its own relative order. The plan goes between the two halves.
 *
 * A ticked set keeps its number only when nothing ticked out of order needs to move past it;
 * where it does, the gap an out-of-order tick would otherwise leave in the ticked half is closed
 * the same way a deletion closes one, because leaving it would put an unticked set in front of
 * the plan — which is the one thing this insertion point promises not to do. Ordinary,
 * in-sequence ticking is unaffected: the ticked half is already contiguous, so renumbering it
 * changes nothing and rewrites nothing.
 *
 * Only the rows whose number actually changes are rewritten, so a renumbering here is a shift
 * rather than a rewrite: the tail keeps its order and its gaps stay closed, which is the
 * invariant [SetNumbering] keeps for the deletes.
 *
 * ### One write
 *
 * The shift and the insert go through [SessionRepository.atomically]. The intermediate state
 * — a table numbered `1, 4, 5` — is exactly the gap [SetNumbering] exists to prevent, and a
 * process killed between the two writes would leave it behind for good. The shift goes first,
 * so the table never holds two sets on one number even for the length of the transaction; the
 * fakes in the tests check that, and the real store's `updateSets` contract explains why the
 * schema does not.
 */
class AddPlannedSetsUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val lock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
) {

    /**
     * @param sessionExerciseId the `session_exercise` row the sets go under. Checked against
     *   the running session's own exercises rather than trusted, for the reason
     *   [RemoveSessionExerciseUseCase] checks: a screen left behind by a session that has since
     *   been replaced would otherwise write into a workout the user is not looking at.
     * @param sets the plan, in the order it will be performed. An empty one writes nothing and
     *   succeeds — a lift whose ramp-up rounded away is ordinary, not an error.
     *
     * @return the ids of the rows written, in the same order.
     *
     * Refused with [SessionError.SessionNotActive] when no session is running or the one that
     * is has been paused, and with [SessionError.ExerciseNotInSession] when the exercise is not
     * one of its own.
     */
    suspend operator fun invoke(
        sessionExerciseId: Long,
        sets: List<PlannedSet>,
    ): Result<List<Long>> =
        lock.withTransition {
            if (sets.isEmpty()) return@withTransition Result.success(emptyList())

            val current = sessionStateRepository.observe().first()
            val active = current as? SessionState.Active
                ?: return@withTransition Result.failure(SessionError.SessionNotActive(current))

            val exercises = sessionRepository.getExercises(active.sessionId).first()
            if (exercises.none { it.id == sessionExerciseId }) {
                return@withTransition Result.failure(
                    SessionError.ExerciseNotInSession(sessionExerciseId, active.sessionId),
                )
            }

            runCatching {
                val now = clock.nowMillis()
                // The read is inside the transaction as well as the writes. The set writes — a
                // tick, an edit — do not take [lock], and the shift below writes whole rows back:
                // a table read outside would let a set ticked in between be written back unticked.
                sessionRepository.atomically {
                    val table = SetNumbering.inOrder(sessionRepository.getSets(sessionExerciseId).first())
                    // Ticked and not, each in the order they already had. Partitioned rather than
                    // split on a number, because ticking is not ordered — see the class doc.
                    val (performed, unperformed) = table.partition(WorkoutSet::isCompleted)

                    val performedRenumbered = SetNumbering.renumber(sets = performed, now = now)

                    val planned = PlannedSets.rows(
                        planned = sets,
                        sessionExerciseId = sessionExerciseId,
                        from = performed.size + 1,
                        createdAt = now,
                    )
                    val unperformedRenumbered = SetNumbering.renumber(
                        sets = unperformed,
                        now = now,
                        from = performed.size + planned.size + 1,
                    )

                    val shifted = performedRenumbered + unperformedRenumbered
                    if (shifted.isNotEmpty()) sessionRepository.updateSets(shifted)
                    sessionRepository.insertSets(planned)
                }
            }
        }
}
