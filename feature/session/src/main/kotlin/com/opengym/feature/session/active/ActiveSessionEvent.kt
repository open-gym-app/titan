package com.opengym.feature.session.active

import com.opengym.core.domain.model.SetType

/**
 * Everything the user can do to a workout in progress.
 *
 * One sealed type per screen, so the stateless half emits and knows nothing else — see
 * CONTRIBUTING.md § Screens.
 *
 * Every event about a set names the exercise it is for. There is no exercise "open" on this
 * screen — see [ActiveSessionUiState] — so an event that left it implicit would be one the
 * ViewModel had to resolve against a selection that no longer exists.
 */
sealed interface ActiveSessionEvent {

    /** The weight field changed. Carries text, not a number — see [SetEntry]. */
    data class WeightChanged(val sessionExerciseId: Long, val text: String) : ActiveSessionEvent

    /** The rep field changed. */
    data class RepsChanged(val sessionExerciseId: Long, val text: String) : ActiveSessionEvent

    /**
     * The rating changed, or was cleared with null.
     *
     * Null is reachable: tapping the selected value on the RPE scale clears it, which is the
     * only way back to an unrated set.
     */
    data class RpeChanged(val sessionExerciseId: Long, val rpe: Int?) : ActiveSessionEvent

    /** The kind of set changed. */
    data class SetTypeChanged(val sessionExerciseId: Long, val setType: SetType) : ActiveSessionEvent

    /** The per-set note field was opened, or closed again with null. */
    data class NotesChanged(val sessionExerciseId: Long, val text: String?) : ActiveSessionEvent

    /** Write this exercise's entry row as a set. */
    data class SetLogged(val sessionExerciseId: Long) : ActiveSessionEvent

    /**
     * Perform this exercise back to back with the one after it — make them a superset.
     *
     * Names the exercise *above* the join, because what the user tapped is the gap between two
     * cards. Whatever either side is already grouped with comes along, so linking the last
     * member of a superset to the exercise below it grows the superset rather than starting a
     * new one; see `LinkSupersetUseCase`.
     */
    data class SupersetLinked(val sessionExerciseId: Long) : ActiveSessionEvent

    /**
     * Stop performing this exercise back to back with the one after it.
     *
     * Breaks the one join, not the whole group: a superset of three split in the middle leaves a
     * pair and a single. See `UnlinkSupersetUseCase`.
     */
    data class SupersetUnlinked(val sessionExerciseId: Long) : ActiveSessionEvent

    /**
     * Open the exercise picker to add more to this workout.
     *
     * The screen only says that it wants one; what opens is a destination in another feature
     * module, which is `:app`'s to resolve — see `ExercisePicker`.
     */
    data object AddExercisesRequested : ActiveSessionEvent

    /** The picker came back with these, in the order they were ticked. */
    data class ExercisesPicked(val exerciseIds: List<Long>) : ActiveSessionEvent

    /**
     * Cut this exercise out of the workout.
     *
     * Refused when sets have been logged against it — the cascade would take them with it — and
     * the refusal is reported rather than confirmed away. See `RemoveSessionExerciseUseCase`.
     */
    data class ExerciseRemoved(val sessionExerciseId: Long) : ActiveSessionEvent

    /** Open the rename dialog on whatever the session is called now. */
    data object NameEditRequested : ActiveSessionEvent

    /** The rename field changed. */
    data class NameChanged(val text: String) : ActiveSessionEvent

    /** Close the rename dialog without writing. */
    data object NameEditCancelled : ActiveSessionEvent

    /** Write the name as typed. Blank clears it, which is the only way back to unnamed. */
    data object NameConfirmed : ActiveSessionEvent

    /** Suspend the session, or put it back into play. */
    data object PauseToggled : ActiveSessionEvent

    /** End the workout and go to the recap. */
    data object FinishRequested : ActiveSessionEvent

    /** Ask whether to throw the workout away. */
    data object DiscardRequested : ActiveSessionEvent

    /** Yes, throw it away. */
    data object DiscardConfirmed : ActiveSessionEvent

    /** No, keep it. */
    data object DiscardDismissed : ActiveSessionEvent

    /** The transient message has been shown. */
    data object MessageShown : ActiveSessionEvent
}
