package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.validation.SetValidator
import com.opengym.core.domain.validation.ValidationResult
import kotlinx.coroutines.flow.first

/**
 * Writes one performed set of the running session, validated on the way in.
 *
 * The active session screen's only write. It exists rather than the screen calling
 * [SessionRepository.insertSet] itself because three things have to happen together and
 * none of them is presentation: the session has to actually be running, the set has to
 * clear [SetValidator], and it has to be numbered without colliding with the sets already
 * under its exercise.
 *
 * ### Validation is here, and also in the screen
 *
 * That is not duplication so much as two different questions asked of the same rules. The
 * screen runs [SetValidator] as the user types, so a refusal is visible before the tick is
 * pressed; this runs it as the last thing before the row is written, because
 * PERSISTENCE.md is explicit that the bounds hold only for callers that check them, and a
 * write path that trusted its caller would be the one place they silently do not. The
 * wording a refusal comes back with is the validator's own, so the inline message and the
 * refused message are the same sentence.
 *
 * ### What it does not do
 *
 * It does not stamp the session's `lastActiveAt`. Logging a set is activity, but a write
 * to `workout_session` per set would re-emit every observer of the session list for the
 * length of a workout, and the fact it would record is already recorded — the set's own
 * `completedAt`, and the in-progress draft, which is written on every change. The
 * lifecycle transitions stamp the column because they are the ones with nothing else to
 * leave behind.
 *
 * It also takes no [SessionLifecycleLock]. Logging is not a transition, and the set number
 * it computes is only stale if two sets are written to the same exercise at once — which
 * the caller prevents, because there is one screen, one button and one in-flight write at
 * a time. What the lock *would* buy is serialisation against a concurrent finish, and that
 * race is harmless in both directions: a set that lands first is counted, and one that
 * lands after the session is closed out is refused here by the state check.
 */
class LogSetUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
) {

    /**
     * @param sessionExerciseId the `session_exercise` row the set belongs to. Checked
     *   against the running session's own exercises rather than trusted — see
     *   [SessionError.ExerciseNotInSession] — because a foreign key that resolves is not
     *   the same as one that belongs here.
     * @param draft what the user entered, as the screen is already holding it. Its [ActiveSet.id],
     *   [ActiveSet.isCompleted] and [ActiveSet.completedAt] are **ignored** and supplied
     *   here instead — the row does not exist yet, this call is what completes the set, and
     *   the moment it was completed is a reading of [clock] rather than something a screen
     *   should be inventing.
     * @param exerciseType how the exercise is logged, so [SetValidator] can also check that
     *   the set filled in the dimension its mode is built around. Null skips that half and
     *   checks the bounds alone — see [SetValidator.validate].
     *
     * @return the set as it was written, carrying the id the store assigned and the
     *   `setNumber` it was given, so the caller can put both into its draft rather than
     *   re-reading the session to find out what it just logged.
     */
    suspend operator fun invoke(
        sessionExerciseId: Long,
        draft: ActiveSet,
        exerciseType: ExerciseType? = null,
    ): Result<WorkoutSet> {
        val current = sessionStateRepository.observe().first()
        val active = current as? SessionState.Active
            ?: return Result.failure(SessionError.SessionNotActive(current))

        val belongs = sessionRepository.getExercises(active.sessionId).first()
            .any { it.id == sessionExerciseId }
        if (!belongs) {
            return Result.failure(
                SessionError.ExerciseNotInSession(sessionExerciseId, active.sessionId),
            )
        }

        val now = clock.nowMillis()
        val set = draft.toWorkoutSet(
            sessionExerciseId = sessionExerciseId,
            setNumber = nextSetNumber(sessionExerciseId),
            now = now,
        )

        return when (val result = SetValidator.validate(set, exerciseType)) {
            is ValidationResult.Invalid -> Result.failure(SessionError.SetRejected(result.reason))
            ValidationResult.Valid -> runCatching {
                set.copy(id = sessionRepository.insertSet(set))
            }
        }
    }

    /**
     * One past the highest number already used under this exercise, counting from 1.
     *
     * The highest rather than the count, because the two disagree the moment a set is
     * deleted (#99) and only one of them cannot collide with the unique
     * `(session_exercise_id, set_number)` index. A gap left behind by a deletion stays a
     * gap; the number is a key, and the position a user reads is the row's place in the
     * list.
     */
    private suspend fun nextSetNumber(sessionExerciseId: Long): Int =
        (sessionRepository.getSets(sessionExerciseId).first().maxOfOrNull(WorkoutSet::setNumber) ?: 0) + 1
}

/**
 * The draft as the row it is about to become.
 *
 * `createdAt` and `updatedAt` are both [now]: the set is created by being completed, so
 * there is no earlier moment for it to have been created at.
 */
private fun ActiveSet.toWorkoutSet(
    sessionExerciseId: Long,
    setNumber: Int,
    now: Long,
): WorkoutSet = WorkoutSet(
    sessionExerciseId = sessionExerciseId,
    setNumber = setNumber,
    reps = reps,
    weight = weight,
    duration = duration,
    distance = distance,
    heartRateAvg = heartRateAvg,
    heartRateMax = heartRateMax,
    restAfter = restAfter,
    rpe = rpe,
    setType = setType,
    completedAt = now,
    isCompleted = true,
    notes = notes,
    createdAt = now,
    updatedAt = now,
)
