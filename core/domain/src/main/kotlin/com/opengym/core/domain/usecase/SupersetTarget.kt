package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import kotlinx.coroutines.flow.first

/**
 * The two halves [LinkSupersetUseCase] and [UnlinkSupersetUseCase] have in common: finding the
 * exercise a tap was aimed at, and writing a new grouping over the session.
 *
 * Shared rather than duplicated because they are the *same* question asked twice — the two use
 * cases differ only in what they do to the grouping in between, which is `Supersets`'.
 */

/**
 * A session's exercises in position order, with the place of the one being edited.
 *
 * @param exercises every exercise of the running session, ordered by `position` — which is the
 *   order a grouping is indexed by.
 * @param index where the exercise the caller named sits in [exercises].
 */
internal class SupersetTarget(val exercises: List<SessionExercise>, val index: Int) {

    /** The session's grouping, in the shape `Supersets` works on. */
    val grouping: List<Int?> get() = exercises.map(SessionExercise::supersetGroup)
}

/**
 * Resolves [sessionExerciseId] against the session that is actually running.
 *
 * Checked rather than trusted, matching [RemoveSessionExerciseUseCase]: a screen left behind by a
 * session that has since been replaced would otherwise regroup a workout the user is not looking
 * at. A paused session is refused too — the screen goes read-only while paused, so a write that
 * slipped through would be editing a workout they have stepped away from.
 */
internal suspend fun supersetTarget(
    sessionRepository: SessionRepository,
    sessionStateRepository: SessionStateRepository,
    sessionExerciseId: Long,
): Result<SupersetTarget> {
    val current = sessionStateRepository.observe().first()
    val active = current as? SessionState.Active
        ?: return Result.failure(SessionError.SessionNotActive(current))

    val exercises = sessionRepository.getExercises(active.sessionId).first()
        .sortedBy(SessionExercise::position)
    val index = exercises.indexOfFirst { it.id == sessionExerciseId }

    return if (index < 0) {
        Result.failure(SessionError.ExerciseNotInSession(sessionExerciseId, active.sessionId))
    } else {
        Result.success(SupersetTarget(exercises, index))
    }
}

/**
 * Writes [grouping] onto [SupersetTarget.exercises], row by row, touching only what it changes.
 *
 * Only what it changes, because most edits change little: extending a superset rewrites the rows
 * at the join and leaves the rest of the workout alone, and a loop that wrote every row would
 * re-emit every observer of the session on each tap.
 *
 * There is no transaction here, unlike the layout write in
 * [SessionRepository.insertExercises]. A grouping half-applied is still a legal grouping —
 * `Supersets.normalize` is run over what is read back before any label is drawn from it, and the
 * worst a torn write leaves is a group of one, which dissolves on sight. The alternative is a
 * multi-row transactional update that nothing else needs, which is #100's problem to solve when
 * a reorder makes it real.
 *
 * @param updatedAt stamped on the rows that move. Passed in rather than read off a clock here, as
 *   every other write in this package does it: the use cases own the clock, which is what makes
 *   them testable without one.
 */
internal suspend fun SessionRepository.writeGrouping(
    target: SupersetTarget,
    grouping: List<Int?>,
    updatedAt: Long,
) {
    target.exercises.forEachIndexed { index, exercise ->
        val group = grouping.getOrNull(index)
        if (exercise.supersetGroup != group) {
            updateExercise(exercise.copy(supersetGroup = group, updatedAt = updatedAt))
        }
    }
}
