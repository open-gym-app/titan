package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.PlannedSet
import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.time.Clock
import kotlinx.coroutines.flow.first

/**
 * Adds exercises to a workout that is already running, at either end of it.
 *
 * A plan does not survive contact with a gym. The squat rack is taken, a training partner
 * suggests something, an accessory gets added because there is time — and a session laid out
 * once at the moment it started, never to change, is a log the user has to lie in. This and
 * [RemoveSessionExerciseUseCase] are what make the layout editable; the order it is performed in
 * is [ReorderSessionExercisesUseCase]'s, and replacing one exercise with another is
 * [SwapSessionExerciseUseCase]'s.
 *
 * ### Either end, and only the ends
 *
 * [ExercisePlacement.END] is what the picker does and what this used to do exclusively.
 * [ExercisePlacement.START] arrived with the warm-up card (#214), which has nowhere else to put
 * its movements: a warm-up performed after the working sets is not a warm-up. There is still no
 * way to place one *in the middle*, and that is not a limitation so much as the honest shape of
 * the operation — what "the middle" means while a workout is half done is a question about the
 * order things will be performed in, which is [ReorderSessionExercisesUseCase]'s, and answering
 * it here with an implicit position would be a second, quieter reorder that nothing tests.
 *
 * A prepend is written as an append followed by a renumbering of the whole layout, because the
 * store only appends — see [SessionRepository.insertExercises], which takes its positions from
 * the list rather than from the caller. That is a read-modify-write over `session_exercise`, so
 * this takes [SessionLifecycleLock] for the same reason the removal and the superset writes do.
 *
 * ### One write, not three
 *
 * The append, the sets and the renumbering go through [SessionRepository.atomically]. Each is
 * atomic on its own, but a process killed between two of them would leave a workout nobody
 * asked for — a warm-up at the *end*, or exercises with no sets — and no compensating write
 * can reach that case, because nothing is running to compensate. A store that throws part-way
 * rolls the lot back, so a failure here is a workout exactly as it was.
 *
 * ### The plan travels with them
 *
 * A draft carrying [PlannedSet]s lays them down as real `workout_set` rows with
 * `is_completed = 0`, exactly as [StartPlannedSessionUseCase] does for a session started from a
 * template — see [PlannedSets] for the shape, which the two share. A draft carrying none adds a
 * bare exercise, which is what both the picker and the warm-up card produce: the card's ramp-up
 * hangs off the *lift*, which is already in the workout, and goes in through
 * [AddPlannedSetsUseCase] rather than on a draft.
 *
 * ### They stand alone
 *
 * Nothing added here joins a superset, and that is enforced rather than merely asked of callers:
 * a group is a decision about two exercises performed back to back, there is no way to express
 * *which* one a new exercise pairs with from an "add to this workout" action, and a
 * [SessionExerciseDraft] carrying a [SessionExerciseDraft.supersetGroup] left over from wherever
 * it was copied from (a template, an existing row) would otherwise write a grouping nothing here
 * asked for. `supersetGroup` is cleared on the way in for that reason, not left to each caller to
 * remember.
 */
class AddSessionExercisesUseCase(
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val lock: SessionLifecycleLock = SessionLifecycleLock.SHARED,
) {

    /**
     * @param exercises the exercises to add, in the order they will be performed among
     *   themselves. An empty list writes nothing and succeeds: a picker dismissed without a
     *   choice is not an error, and making the caller special-case it would put the check in
     *   every caller.
     * @param placement which end of the workout they go on.
     *
     * @return the `session_exercise` ids written, in the same order.
     *
     * Refused with [SessionError.SessionNotActive] when no session is running, or when the one
     * that is has been paused — the screen goes read-only while paused, and a write that
     * slipped through would be editing a workout the user has stepped away from.
     */
    suspend operator fun invoke(
        exercises: List<SessionExerciseDraft>,
        placement: ExercisePlacement = ExercisePlacement.END,
    ): Result<List<Long>> =
        lock.withTransition {
            if (exercises.isEmpty()) return@withTransition Result.success(emptyList())

            val current = sessionStateRepository.observe().first()
            val active = current as? SessionState.Active
                ?: return@withTransition Result.failure(SessionError.SessionNotActive(current))

            runCatching {
                // Now, not the session's own start: these were added part way through, and
                // `created_at` is the one column that records that they were not in the plan
                // the workout began with.
                val now = clock.nowMillis()
                // Stripped here rather than trusted from the caller — see the class doc.
                val standalone = exercises.map { it.copy(supersetGroup = null) }
                sessionRepository.atomically {
                    val added = sessionRepository.insertExercises(
                        sessionId = active.sessionId,
                        exercises = standalone,
                        createdAt = now,
                    )
                    sessionRepository.insertSets(
                        standalone.zip(added) { draft, id -> draft.plannedSets(id, now) }.flatten(),
                    )
                    if (placement == ExercisePlacement.START) moveToFront(active.sessionId, added, now)
                    added
                }
            }
        }

    /**
     * Puts the rows just appended in front of everything else, keeping both groups' own order.
     *
     * The superset grouping travels untouched, and it stays valid: a group's members have to
     * occupy consecutive positions, and shifting every existing exercise down by the same amount
     * moves them together. The group *ordinals* do not need renumbering either — nothing added
     * here belongs to a superset, so the first superset of the workout is still the first one.
     *
     * Nothing else is rewritten if the workout was empty, which is the case where "the front" and
     * "the end" are the same place: the rows are already where they belong, and a layout rewrite
     * that changes no position is a write for nothing.
     */
    private suspend fun moveToFront(
        sessionId: Long,
        added: List<Long>,
        now: Long,
    ) {
        val stored = sessionRepository.getExercises(sessionId).first()
        val addedIds = added.toSet()
        val reordered = stored.filter { it.id in addedIds } + stored.filterNot { it.id in addedIds }

        val moved = reordered.mapIndexedNotNull { index, exercise ->
            exercise.copy(position = index, updatedAt = now).takeIf { it.position != exercise.position }
        }
        if (moved.isNotEmpty()) sessionRepository.updateExercises(moved)
    }
}

/**
 * Which end of a running workout new exercises go on.
 *
 * Two values rather than a position, because those are the two questions anything asks: the
 * picker adds to what is left to do, and the warm-up card adds to what comes first. Anything
 * finer is a reorder — see [AddSessionExercisesUseCase].
 */
enum class ExercisePlacement {
    /** In front of everything, which is where a warm-up belongs. */
    START,

    /** After everything, which is where an accessory added mid-workout belongs. */
    END,
}
