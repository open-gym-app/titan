package com.titan.core.domain.usecase

import com.titan.core.domain.model.SessionError
import com.titan.core.domain.model.SessionExercise
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.SessionStateRepository
import com.titan.core.domain.superset.Supersets
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Puts a running workout's exercises in a different order.
 *
 * The third of the layout edits, after [AddSessionExercisesUseCase] and
 * [RemoveSessionExerciseUseCase], and the one a busy gym actually forces: the machine that is
 * next in the plan is taken, so the accessory that was fourth is done second and the plan is
 * rewritten around what is free. A log that could only be appended to would record a workout
 * nobody performed.
 *
 * ### It takes the whole order, not a move
 *
 * The argument is every `session_exercise` id of the session, in the order they will now be
 * performed — not "move this one to position 3". Two reasons, and both are about a screen
 * holding a list it read a moment ago:
 *
 * - **A position is an index into that list.** Every other edit here names a *row* for exactly
 *   that reason, and the one edit whose subject genuinely is an ordering has to name all of
 *   them or name none.
 * - **It is checkable.** A list that is not this session's exercises, each exactly once, is a
 *   screen working from a layout that has since changed, and it is refused as
 *   [SessionError.InvalidExerciseOrder] rather than applied to whichever rows happen to match.
 *   A move expressed as a pair of indices cannot be told apart from a stale one.
 *
 * ### A superset a drag pulls apart is torn, not refused
 *
 * `Supersets` requires a group's members to occupy consecutive positions, so an order that
 * separates two of them cannot be stored as it stands. It is **repaired** rather than refused,
 * because by the time this is called the user has already said what they want: they picked one
 * exercise up and put it somewhere else, and a refusal would answer that by silently springing it
 * back to where it was.
 *
 * [Supersets.tear] is the repair, and it says the same thing from either end — an exercise
 * dragged out of a superset leaves it, and one dropped into the middle of a superset cuts it in
 * two. A group left holding a single exercise is not a superset and dissolves; a three broken in
 * the middle closes up and stays one. Nothing is destroyed either way: `superset_group` is how
 * the workout is *paced*, no set or figure reads it, and the join between two cards puts it back
 * in one tap.
 *
 * ### What it repairs on the way out
 *
 * Group ids are ordinals in position order — `SS A` is the first superset of the workout — so
 * moving the second superset above the first has to renumber them, exactly as a removal
 * dissolving a group does. [Supersets.normalize] does it, and the renumbered rows travel in the
 * same write as the positions: a screen reading a layout where the positions had moved and the
 * ordinals had not would draw `SS B` above `SS A`.
 *
 * ### Why it takes the lifecycle lock
 *
 * Reading every exercise of the session, computing positions and a grouping from what was read
 * and writing back the rows that moved is a read-modify-write, and it is the same one a removal
 * and a superset link do. Interleaved, the later of the two writes a layout computed before the
 * earlier one landed — which puts back the order, or the grouping, that the other had just
 * changed. The lock is held across the read and the write.
 *
 * ### Sets logged against an exercise do not pin it down
 *
 * An exercise that has already been worked reorders as freely as one that has not, and the
 * progress indicator stays coherent because it *counts* rather than indexes: the header's rail
 * is "how many exercises have a set against them, out of how many", which no permutation of the
 * same exercises can change. What a set records about *when* it happened is its own
 * `completed_at`, which nothing here touches — position is the order the workout is performed
 * in, and the whole point of this use case is that the two can differ.
 */
class ReorderSessionExercisesUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val lock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
) {

    /**
     * @param orderedSessionExerciseIds every `session_exercise` row of the running session, in
     *   the order they will now be performed.
     *
     * @return the ids as they were written, so a caller can assert on the order it asked for
     *   rather than on a re-read.
     *
     * Refused with [SessionError.SessionNotActive] when no session is running or the one that is
     * has been paused — the screen goes read-only while paused, and a reorder that slipped
     * through would be rearranging a workout the user has stepped away from — and with
     * [SessionError.InvalidExerciseOrder] when the list is not this session's exercises, each
     * exactly once.
     */
    suspend operator fun invoke(orderedSessionExerciseIds: List<Long>): Result<List<Long>> =
        lock.withTransition {
            val current = sessionStateRepository.observe().first()
            val active = current as? SessionState.Active
                ?: return@withTransition Result.failure(SessionError.SessionNotActive(current))

            val exercises = sessionRepository.getExercises(active.sessionId).first()
            val moved = rowsThatMove(orderedSessionExerciseIds, exercises.associateBy(SessionExercise::id))
                .getOrElse { return@withTransition Result.failure(it) }

            runCatching {
                // Nothing to write is a success rather than a special case: dragging an exercise
                // back where it came from is a legal thing to do and produces no order to save.
                if (moved.isNotEmpty()) sessionRepository.updateExercises(moved)
                orderedSessionExerciseIds
            }
        }

    /**
     * The rows [order] actually changes, or why it is not an order this session can be in.
     *
     * Only the rows that change, matching every other layout edit: a reorder that moved one
     * exercise past its neighbour writes the two that swapped and leaves the rest of the workout
     * — and their `updated_at` — alone.
     *
     * The one refusal it can make is about the list itself: a set of ids that is not this
     * session's exercises is a screen working from a layout that has changed under it, and the
     * grouping it implies is not worth computing.
     */
    private fun rowsThatMove(
        order: List<Long>,
        byId: Map<Long, SessionExercise>,
    ): Result<List<SessionExercise>> {
        // Set equality **and** the count, because the two catch different mistakes: a list
        // missing an exercise and a list naming one twice have the same set, and only the second
        // would otherwise be written — as two rows on one position, which the unique index would
        // abort on halfway through the transaction.
        if (order.size != byId.size || order.toSet() != byId.keys) {
            return Result.failure(
                SessionError.InvalidExerciseOrder(
                    "expected the ${byId.size} exercise(s) of this session, each once, got ${order.size}",
                ),
            )
        }

        val reordered = order.map { byId.getValue(it) }
        // Cut rather than checked: an order that separates two members of a superset is what
        // dragging one of them out *is*, and `tear` is where that decision is written down.
        val grouping = Supersets.tear(reordered.map(SessionExercise::supersetGroup))

        val now = clock.nowMillis()
        return Result.success(
            reordered.mapIndexedNotNull { index, exercise ->
                exercise
                    .copy(position = index, supersetGroup = grouping[index], updatedAt = now)
                    .takeIf {
                        it.position != exercise.position || it.supersetGroup != exercise.supersetGroup
                    }
            },
        )
    }
}
