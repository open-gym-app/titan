package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.superset.Supersets
import com.opengym.core.domain.time.Clock

/**
 * Makes one exercise of a running workout part of a superset with the one after it.
 *
 * Supersets are decided in the gym, not at a desk. Two machines happen to be free next to each
 * other, or the session is running long and the accessories get paired up — so the grouping has
 * to be editable at the moment the decision is made, which is mid-workout.
 *
 * ### Why it links a *pair*, not a selection
 *
 * `Supersets` requires a group's members to occupy consecutive positions, and this screen has no
 * reorder (#100). The only request a user can make that is guaranteed to satisfy that rule is
 * "join these two neighbours", so that is the only request this takes — [Supersets.join] then
 * absorbs whatever either side already belonged to, which is what lets a third exercise be added
 * to a superset by linking it to the group's last member.
 *
 * ### Sets logged against it are irrelevant
 *
 * Unlike [RemoveSessionExerciseUseCase], this refuses nothing on account of what has been
 * performed. A grouping says how the exercises are *paced*, and changing it destroys nothing:
 * `superset_group` is a column on `session_exercise` and no set, figure or total reads it.
 * A lifter who supersets the last two exercises after doing a round of each has described their
 * workout more accurately than before, not corrupted it.
 *
 * ### Why it takes the lifecycle lock
 *
 * Reading the session's grouping, joining a pair in it and writing back the rows that moved is a
 * read-modify-write, and so is the repair [RemoveSessionExerciseUseCase] runs on its way out.
 * Interleaved, the later of the two writes a grouping computed from the layout as it was before
 * the earlier one — which is how a superset the user has just made comes apart on its own. The
 * lock is held across the read and the write.
 */
class LinkSupersetUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val lock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
) {

    /**
     * @param sessionExerciseId the `session_exercise` row to link **forward**, i.e. with whatever
     *   is performed next. Named by the row rather than by its position for the reason every
     *   other edit here is: a position is an index into a list the screen last read, and the list
     *   can have changed under it.
     *
     * @return the grouping that was written, one entry per exercise in position order, so a
     *   caller can assert on the shape it asked for rather than on a re-read.
     *
     * Refused with [SessionError.SessionNotActive] when no session is running or the one that is
     * has been paused; with [SessionError.ExerciseNotInSession] when the row belongs to another
     * session; and with the reason from [Supersets.join] — as an `IllegalArgumentException` —
     * when the pair cannot be a superset, which on this screen means only that the exercise is
     * the last one in the workout.
     */
    suspend operator fun invoke(sessionExerciseId: Long): Result<List<Int?>> =
        lock.withTransition {
            val target =
                supersetTarget(sessionRepository, sessionStateRepository, sessionExerciseId)
                    .getOrElse { return@withTransition Result.failure(it) }

            val joined = Supersets.join(target.grouping, target.index)
                .getOrElse { return@withTransition Result.failure(it) }

            runCatching {
                sessionRepository.writeGrouping(target, joined, clock.nowMillis())
                joined
            }
        }
}
