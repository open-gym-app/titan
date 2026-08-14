package com.opengym.feature.history.library

import com.opengym.core.domain.model.EquipmentType

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

    /** A row was tapped in selection mode. Adds or removes it from the selection. */
    data class ExerciseToggled(val exerciseId: Long) : ExerciseLibraryEvent

    /** The `+ NEW` action. Opens an empty draft. */
    data object NewExerciseRequested : ExerciseLibraryEvent

    /** A field in the custom-exercise form changed. Carries the whole draft. */
    data class NewExerciseChanged(val draft: NewExerciseDraft) : ExerciseLibraryEvent

    /** Save the draft. Ignored unless [NewExerciseDraft.canSave]. */
    data object NewExerciseSubmitted : ExerciseLibraryEvent

    /** The form was dismissed. Whatever was typed is discarded. */
    data object NewExerciseDismissed : ExerciseLibraryEvent

    /** The snackbar has been shown, so the message can be forgotten. */
    data object MessageShown : ExerciseLibraryEvent
}
