package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Puts a different exercise in one of a running workout's slots.
 *
 * The other thing a busy gym forces. The bench is taken and the dumbbells are not, so the second
 * exercise of the plan becomes a different one — at the same place in the running order, in the
 * same superset, with the rest of the workout untouched. Reached by removing and re-adding it
 * would put the new exercise at the *end* of the session, which is not what happened.
 *
 * ### An exercise with sets logged against it is refused
 *
 * The same rule [RemoveSessionExerciseUseCase] applies, for a related reason with a different
 * mechanism. Nothing here cascades — the `session_exercise` row survives a swap and its
 * `workout_set` rows with it — and that is exactly the problem: the sets would stay, attached to
 * a movement they were not performed on. Three sets of bench press would silently become three
 * sets of incline press, at the weights the bench was done at, and would count towards the
 * incline's records for the rest of the user's history.
 *
 * Discarding them instead is the outcome the issue this comes from rules out, and keeping the
 * old exercise while appending the new one — the other option on the table — is what "add more
 * exercises" already does, said less clearly. So the swap is refused with
 * [SessionError.ExerciseHasLoggedSets], the same refusal the × on the card gives, and it names
 * the same way past itself: delete the sets first (#99), or add the new exercise and leave the
 * old one where it is.
 *
 * ### What travels with the slot, and what does not
 *
 * The row keeps its `position`, its `supersetGroup` and its `notes`: the slot in the workout is
 * unchanged and it is the *movement* that was swapped, so an exercise paired with the one after
 * it stays paired, and a remark the user typed about this part of their session is theirs and is
 * not deleted on their behalf. `template_exercise_id` does not travel — it records which row of
 * a template this exercise came from, and after a swap that provenance is simply false.
 */
class SwapSessionExerciseUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val lock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
) {

    /**
     * @param sessionExerciseId the slot to fill differently, checked against the running
     *   session's own exercises rather than trusted — matching every other edit here, and for
     *   the same reason: a screen left behind by a session that has since been replaced would
     *   otherwise rewrite a workout the user is not looking at.
     * @param exerciseId the catalogue exercise to put in it. Not checked against the catalogue:
     *   `session_exercise.exercise_id` is a foreign key with `RESTRICT`, so an id that names
     *   nothing is refused by the store rather than by a read this would otherwise make on every
     *   swap — the same trade [AddSessionExercisesUseCase] makes.
     *
     * @return the row as it now stands, so a caller can name what is in the slot without a
     *   re-read.
     *
     * Refused with [SessionError.SessionNotActive] when no session is running or the one that is
     * has been paused; with [SessionError.ExerciseNotInSession] when the slot belongs to another
     * session; and with [SessionError.ExerciseHasLoggedSets] once anything has been logged
     * against it.
     */
    suspend operator fun invoke(
        sessionExerciseId: Long,
        exerciseId: Long,
    ): Result<SessionExercise> = lock.withTransition {
        val current = sessionStateRepository.observe().first()
        val active = current as? SessionState.Active
            ?: return@withTransition Result.failure(SessionError.SessionNotActive(current))

        val exercise = sessionRepository.getExercises(active.sessionId).first()
            .firstOrNull { it.id == sessionExerciseId }
            ?: return@withTransition Result.failure(
                SessionError.ExerciseNotInSession(sessionExerciseId, active.sessionId),
            )

        fill(exercise, exerciseId)
    }

    /**
     * Puts [exerciseId] in [slot], once what is already logged against it allows.
     *
     * Split off the resolution above so that each half reads as one question: which row is this,
     * and can what is in it be changed.
     */
    private suspend fun fill(slot: SessionExercise, exerciseId: Long): Result<SessionExercise> {
        // Swapping an exercise for itself is what a picker answers with when the user reopens it
        // and taps what is already there. Nothing is written, and it is not a refusal: the slot
        // holds what they asked it to.
        if (slot.exerciseId == exerciseId) return Result.success(slot)

        val logged = sessionRepository.getSets(slot.id).first()
        if (logged.isNotEmpty()) {
            return Result.failure(SessionError.ExerciseHasLoggedSets(slot.id, logged.size))
        }

        val swapped = slot.copy(
            exerciseId = exerciseId,
            templateExerciseId = null,
            updatedAt = clock.nowMillis(),
        )
        return runCatching {
            sessionRepository.updateExercise(swapped)
            swapped
        }
    }
}
