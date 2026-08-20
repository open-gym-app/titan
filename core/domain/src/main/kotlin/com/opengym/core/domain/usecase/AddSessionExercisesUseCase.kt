package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Adds exercises to the end of a workout that is already running.
 *
 * A plan does not survive contact with a gym. The squat rack is taken, a training partner
 * suggests something, an accessory gets added because there is time — and a session laid out
 * once at the moment it started, never to change, is a log the user has to lie in. This and
 * [RemoveSessionExerciseUseCase] are what make the layout editable; reordering and swapping are
 * #100's.
 *
 * ### Appended, never inserted
 *
 * New exercises go on the end, in the order they were picked, and there is no way to place one
 * in the middle. That is not a limitation so much as the honest shape of the operation: what
 * "the middle" means while a workout is half done is a question about the *order things will be
 * performed in*, which is a reorder — the exact thing #100 exists for — and answering it here
 * with an implicit position would be a second, quieter reorder that nothing tests.
 *
 * ### They stand alone
 *
 * Nothing added here joins a superset. A group is a decision about two exercises performed back
 * to back, and there is no way to express *which* one a new exercise pairs with from an
 * "add to this workout" action. `Supersets` also requires a group's members to occupy
 * consecutive positions, and an appended exercise is by construction at the end.
 */
class AddSessionExercisesUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
) {

    /**
     * @param exerciseIds the catalogue exercises to add, in the order they will be performed.
     *   An empty list writes nothing and succeeds: a picker dismissed without a choice is not
     *   an error, and making the caller special-case it would put the check in every caller.
     *
     * @return the `session_exercise` ids written, in the same order.
     *
     * Refused with [SessionError.SessionNotActive] when no session is running, or when the one
     * that is has been paused — the screen goes read-only while paused, and a write that
     * slipped through would be editing a workout the user has stepped away from.
     */
    suspend operator fun invoke(exerciseIds: List<Long>): Result<List<Long>> {
        if (exerciseIds.isEmpty()) return Result.success(emptyList())

        val current = sessionStateRepository.observe().first()
        val active = current as? SessionState.Active
            ?: return Result.failure(SessionError.SessionNotActive(current))

        return runCatching {
            sessionRepository.insertExercises(
                sessionId = active.sessionId,
                exercises = exerciseIds.map { SessionExerciseDraft(exerciseId = it) },
                // Now, not the session's own start: these were added part way through, and
                // `created_at` is the one column that records that they were not in the plan
                // the workout began with.
                createdAt = clock.nowMillis(),
            )
        }
    }
}
