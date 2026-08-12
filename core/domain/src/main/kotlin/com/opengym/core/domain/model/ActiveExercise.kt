package com.opengym.core.domain.model

import kotlinx.serialization.Serializable

/**
 * One exercise of an in-progress workout, with the sets logged against it so far.
 *
 * The draft counterpart of [SessionExercise]. `sessionId` and `position` are absent
 * for the same reasons [ActiveSet] drops its back-reference and its number: the
 * exercise is nested inside the session that owns it, and its position is its index in
 * [ActiveSession.exercises].
 *
 * @property exerciseId the catalogue [Exercise] being performed. This is the one id
 *   that is never null — an exercise the user has not chosen is not on the screen.
 * @property sessionExerciseId the `session_exercise` row this draft entry was read
 *   from, or null for an exercise added to the running session but not yet written.
 * @property supersetGroup which superset this exercise belongs to, or null when it
 *   stands alone — mirroring [SessionExercise.supersetGroup]. Carried in the draft
 *   rather than read back off the rows because the live session needs it on every
 *   logged set: it is what decides whether a rest is due yet
 *   ([ActiveSession.isRestDueAfter]), and an exercise added to the running session may
 *   have no row to read it from at all.
 * @property notes what the user typed against this exercise, or null. Carried in the
 *   draft rather than left to the row it came from because it is edited on the same
 *   screen as the sets and is unsaved for exactly as long as they are.
 * @property sets every set on this exercise, completed or not, in the order they are
 *   shown.
 */
@Serializable
data class ActiveExercise(
    val exerciseId: Long,
    val sessionExerciseId: Long? = null,
    val supersetGroup: Int? = null,
    val notes: String? = null,
    val sets: List<ActiveSet> = emptyList(),
) {
    init {
        require(exerciseId > 0) { "an active exercise references a catalogue exercise, was $exerciseId" }
    }

    /** The sets the user has ticked off, which are the ones any figure counts. */
    val completedSets: List<ActiveSet> get() = sets.filter { it.isCompleted }
}
