package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.superset.Supersets
import com.opengym.core.domain.time.Clock

/**
 * Stops one exercise of a running workout being performed back to back with the one after it.
 *
 * The other half of [LinkSupersetUseCase], and deliberately the *link's* inverse rather than the
 * group's: what the user taps is the join between two exercises, so breaking it splits a
 * three-member superset into a pair and a single rather than dissolving the whole thing. See
 * [Supersets.split].
 *
 * Succeeds where there was nothing to break — an exercise that already stands alone, or the last
 * one in the workout. A control that is only correct when the caller has already checked the
 * grouping is one every caller has to check the grouping for; the grouping is renormalised and
 * written back either way, and in the ordinary case that write touches no rows at all.
 */
class UnlinkSupersetUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
) {

    /**
     * @param sessionExerciseId the `session_exercise` row to unlink **forward**, i.e. from
     *   whatever is performed next.
     *
     * @return the grouping that was written, one entry per exercise in position order.
     *
     * Refused with [SessionError.SessionNotActive] when no session is running or the one that is
     * has been paused, and with [SessionError.ExerciseNotInSession] when the row belongs to
     * another session. There is no third refusal: see the note above on why a link that is not
     * there is not an error.
     */
    suspend operator fun invoke(sessionExerciseId: Long): Result<List<Int?>> {
        val target = supersetTarget(sessionRepository, sessionStateRepository, sessionExerciseId)
            .getOrElse { return Result.failure(it) }

        val split = Supersets.split(target.grouping, target.index)

        return runCatching {
            sessionRepository.writeGrouping(target, split, clock.nowMillis())
            split
        }
    }
}
