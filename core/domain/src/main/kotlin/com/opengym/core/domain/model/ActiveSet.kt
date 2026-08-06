package com.opengym.core.domain.model

import kotlinx.serialization.Serializable

/**
 * One set of an in-progress workout as it stands on screen, saved so it survives a
 * process kill.
 *
 * The draft counterpart of [WorkoutSet], and distinct from it by what it leaves out.
 * `sessionExerciseId` is redundant here because the set is nested inside the exercise
 * that owns it, and `setNumber` is taken from the order of [ActiveExercise.sets]
 * rather than from a field — for the same reason [TemplateExerciseDraft] drops
 * `position`: a list is already ordered, and a caller that hand-numbers sets is one
 * reorder away from a duplicate `(session_exercise_id, set_number)`. The write path
 * numbers them.
 *
 * Units match [WorkoutSet] exactly, because these values are written straight through
 * to it: [weight] in milligrams, [distance] in centimeters, [duration] and [restAfter]
 * in seconds, [rpe] as RPE x 10.
 *
 * @property id the `workout_set` row this draft entry was read from, or null for a set
 *   the user has typed but that has not been written yet. Restoring uses it to update
 *   rather than duplicate.
 * @property isCompleted whether the user has ticked the set off. An incomplete set is
 *   still worth saving — a half-typed set is exactly the state this contract exists to
 *   bring back — but it contributes to no figure until it is completed, which is why
 *   [completedAt] may be null while the set exists.
 * @property completedAt when the set was ticked off, epoch millis UTC, or null while it
 *   is not yet completed.
 */
@Serializable
data class ActiveSet(
    val id: Long? = null,
    val reps: Int? = null,
    val weight: Long? = null,
    val duration: Long? = null,
    val distance: Long? = null,
    val heartRateAvg: Int? = null,
    val heartRateMax: Int? = null,
    val restAfter: Long? = null,
    val rpe: Int? = null,
    val setType: SetType = SetType.NORMAL,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val notes: String? = null,
) {
    init {
        require(rpe == null || rpe in WorkoutSet.RPE_MIN..WorkoutSet.RPE_MAX) {
            "rpe must be within ${WorkoutSet.RPE_MIN}..${WorkoutSet.RPE_MAX} (RPE x 10), was $rpe"
        }
    }
}
