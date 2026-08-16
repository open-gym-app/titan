package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.superset.Supersets
import kotlinx.coroutines.flow.first

/**
 * Drops one exercise from a workout that is already running.
 *
 * The other half of [AddSessionExercisesUseCase]: a plan that can be added to but not cut from
 * is one where a machine being occupied leaves an exercise sitting in the log forever, with no
 * sets and no explanation.
 *
 * ### An exercise with sets logged against it is refused
 *
 * `session_exercise` cascades to `workout_set`, so removing one that has been worked would
 * delete sets the user actually performed. That is the one outcome no confirmation dialog makes
 * acceptable — a dialog turns "you are about to lose eleven sets" into a button somebody presses
 * on autopilot between sets, in a gym, on a phone.
 *
 * The refusal is also not a dead end, which is what makes it a reasonable rule rather than an
 * obstruction. Deleting the sets first (#99) leaves the exercise empty and it then goes freely,
 * and a user who means to throw the whole workout away has `DiscardSessionUseCase`. What is not
 * offered is the middle path, because there is no honest version of it.
 *
 * ### What it repairs on the way out
 *
 * A superset left with a single member is not a superset — see `Supersets` — so the surviving
 * grouping is renormalised and any exercise whose group changed is written back. Positions are
 * *not* renumbered: the removal leaves a gap, nothing reads position as a dense index, and a
 * write across every surviving row would be a second thing to get wrong for no reader's benefit.
 */
class RemoveSessionExerciseUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
) {

    /**
     * @param sessionExerciseId the `session_exercise` row to drop. Checked against the running
     *   session's own exercises rather than trusted: a screen left behind by a session that has
     *   since been replaced would otherwise delete out of a workout the user is not looking at.
     *
     * @return the id that was removed, so a caller can name it in what it shows.
     */
    suspend operator fun invoke(sessionExerciseId: Long): Result<Long> {
        val current = sessionStateRepository.observe().first()
        val active = current as? SessionState.Active
            ?: return Result.failure(SessionError.SessionNotActive(current))

        val exercises = sessionRepository.getExercises(active.sessionId).first()
            .sortedBy(SessionExercise::position)

        refusal(sessionExerciseId, exercises, active.sessionId)
            ?.let { return Result.failure(it) }

        return runCatching {
            sessionRepository.deleteExercise(sessionExerciseId)
            repairSupersets(exercises.filterNot { it.id == sessionExerciseId })
            sessionExerciseId
        }
    }

    /**
     * Why this exercise cannot go, or null.
     *
     * The two checks together rather than inline, because they answer the same question — is
     * this a removal the session will survive — and because reading them as one list is what
     * makes it obvious that the *order* matters: an exercise that is not in this session must
     * be reported as such rather than as one whose sets happen to be unreadable.
     */
    private suspend fun refusal(
        sessionExerciseId: Long,
        exercises: List<SessionExercise>,
        sessionId: Long,
    ): SessionError? {
        if (exercises.none { it.id == sessionExerciseId }) {
            return SessionError.ExerciseNotInSession(sessionExerciseId, sessionId)
        }

        val logged = sessionRepository.getSets(sessionExerciseId).first()
        return SessionError.ExerciseHasLoggedSets(sessionExerciseId, logged.size)
            .takeIf { logged.isNotEmpty() }
    }

    /**
     * Renormalises the grouping over what is left, writing back only the rows it changed.
     *
     * Only the rows that changed, because the ordinary removal changes none of them — an
     * exercise that stood alone leaves every group intact — and a loop that rewrote the whole
     * layout would re-emit every observer of the session on each removal.
     *
     * A failure here leaves a group with one member in it, which is worth naming as tolerable
     * rather than papered over: the screens renormalise before reading labels off a grouping,
     * so what the user sees is already correct, and the next removal repairs the rows.
     */
    private suspend fun repairSupersets(surviving: List<SessionExercise>) {
        val repaired = Supersets.normalize(surviving.map(SessionExercise::supersetGroup))

        surviving.forEachIndexed { index, exercise ->
            if (exercise.supersetGroup != repaired[index]) {
                sessionRepository.updateExercise(exercise.copy(supersetGroup = repaired[index]))
            }
        }
    }
}
