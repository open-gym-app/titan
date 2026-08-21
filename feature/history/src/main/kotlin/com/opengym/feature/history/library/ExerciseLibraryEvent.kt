package com.opengym.feature.history.library

import com.opengym.core.domain.model.EquipmentType
import com.opengym.feature.history.exercise.ExerciseDraft

/**
 * Everything the exercise library screen can report.
 *
 * Navigation is *not* here: opening an exercise and confirming a selection leave the screen,
 * and a feature does not own the `NavController`. Those two are parameters of the screen, not
 * events — see `ExerciseLibraryScreen`.
 */
sealed interface ExerciseLibraryEvent {

    /** The search box changed. Fires per keystroke; the results follow. */
    data class QueryChanged(val query: String) : ExerciseLibraryEvent

    /** A muscle chip was tapped. Null is the ALL chip, which clears the filter. */
    data class MuscleFilterSelected(val muscleGroupId: Long?) : ExerciseLibraryEvent

    /** An equipment chip was tapped. Toggles, because several can be on at once. */
    data class EquipmentFilterToggled(val equipment: EquipmentType) : ExerciseLibraryEvent

    /**
     * The filter sheet's clear: both chip filters off, the search box left alone.
     *
     * A sheet that does not show the search box has no business emptying it — the user would
     * close it to find their typing gone and nothing on screen saying why. Contrast
     * [SearchAndFiltersCleared], which is offered from a screen that has just told them
     * nothing matches their *search and* filters.
     */
    data object FiltersReset : ExerciseLibraryEvent

    /** The way out of a no-results state: drops the query and both filters together. */
    data object SearchAndFiltersCleared : ExerciseLibraryEvent

    /**
     * A row was tapped in selection mode. Adds or removes it from the selection.
     *
     * @param exclusive whether picking this one unpicks everything else, because the screen is
     *   filling a slot rather than building a list — see [ExerciseLibraryScreen]'s
     *   `singleSelection`. The rule is applied here rather than by the caller for the reason
     *   the selection lives here at all: a screen that dropped the others itself would be
     *   holding an order the ViewModel is the owner of. Unticking the picked one still empties
     *   the selection, which is how somebody changes their mind about swapping at all.
     */
    data class ExerciseToggled(
        val exerciseId: Long,
        val exclusive: Boolean = false,
    ) : ExerciseLibraryEvent

    /**
     * Add an exercise to the selection, whether or not it is already in it.
     *
     * Not [ExerciseToggled]: this arrives from the detail screen's "Add to session", which is
     * a statement rather than a switch. A lifter who ticked an exercise, opened it to check
     * the form notes and pressed the button that says "add" must not find it *removed* on the
     * way back.
     *
     * @param exclusive as on [ExerciseToggled]. The detail screen is reachable from the picker
     *   in both modes, so the button on it answers whichever question the picker was opened
     *   with.
     */
    data class ExerciseSelected(
        val exerciseId: Long,
        val exclusive: Boolean = false,
    ) : ExerciseLibraryEvent

    /** The `+ New` action. Opens an empty draft. */
    data object NewExerciseRequested : ExerciseLibraryEvent

    /** A field in the custom-exercise form changed. Carries the whole draft. */
    data class NewExerciseChanged(val draft: ExerciseDraft) : ExerciseLibraryEvent

    /** Save the draft. Ignored unless [ExerciseDraft.canSave]. */
    data object NewExerciseSubmitted : ExerciseLibraryEvent

    /** The form was dismissed. Whatever was typed is discarded. */
    data object NewExerciseDismissed : ExerciseLibraryEvent

    /** The snackbar has been shown, so the message can be forgotten. */
    data object MessageShown : ExerciseLibraryEvent
}
