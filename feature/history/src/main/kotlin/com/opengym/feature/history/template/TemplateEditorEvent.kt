package com.opengym.feature.history.template

import com.opengym.core.domain.model.TemplateCategory

/**
 * Everything the template editor can report.
 *
 * Navigation is not here, with one seam: opening the exercise picker leaves the screen, and a
 * feature does not own the `NavController`. That is a parameter of the screen —
 * [ExercisesPickRequested] is the event that goes *with* it, so the ViewModel knows the picker is
 * up, and the push itself is `:app`'s. Leaving after a save is reported as state, for the reason
 * `TemplateEditorUiState.isSaved` gives.
 */
sealed interface TemplateEditorEvent {

    /** Open the name-and-category dialog on what the draft says now. */
    data object NameEditRequested : TemplateEditorEvent

    /** The name field changed. Also clears a name-already-taken refusal. */
    data class NameChanged(val text: String) : TemplateEditorEvent

    /** A category chip was chosen in that dialog, or `null` for the `None` chip. */
    data class CategoryChanged(val category: TemplateCategory?) : TemplateEditorEvent

    /**
     * Apply the name and category to the draft.
     *
     * Nothing is written: the draft lives here until SAVE, so this records a decision rather
     * than renaming a template.
     */
    data object NameConfirmed : TemplateEditorEvent

    /** Close the dialog without applying it. */
    data object NameEditCancelled : TemplateEditorEvent

    /**
     * One of an exercise's four figures was typed into.
     *
     * By [key] rather than by index, so a keystroke that lands after a reorder still reaches the
     * exercise it was typed into — and one field rather than four events, because the four
     * differ only in which figure they carry and the parsing they need is the same shape.
     */
    data class FieldChanged(
        val key: Long,
        val field: TemplateField,
        val text: String,
    ) : TemplateEditorEvent

    /**
     * An exercise was dropped from the plan.
     *
     * By [key], not by exercise id: the same movement may legitimately appear twice in one
     * template, and an id would remove whichever came first. A superset left with one member
     * dissolves rather than being refused — see `Supersets.normalize`.
     */
    data class ExerciseRemoved(val key: Long) : TemplateEditorEvent

    /**
     * The plan was reordered, given as the new order of [TemplateExerciseRow.key].
     *
     * A superset the drag pulled apart is **torn**, not bounced: pulling one member away from
     * the other is what "these are not performed back to back any more" looks like, and it is
     * the same rule the workout itself applies. See `Supersets.tear`.
     */
    data class ExercisesReordered(val order: List<Long>) : TemplateEditorEvent

    /** The join below the exercise at [index] was tapped, making the two one superset. */
    data class SupersetLinked(val index: Int) : TemplateEditorEvent

    /** The same join, tapped again: the two are no longer performed back to back. */
    data class SupersetUnlinked(val index: Int) : TemplateEditorEvent

    /** "Add exercises". Records that the picker is being opened; the push is the screen's. */
    data object ExercisesPickRequested : TemplateEditorEvent

    /**
     * The picker's answer: the exercises chosen, in the order they were picked, appended to the
     * end of the plan. Each arrives with no defaults — a line the user has not priced yet.
     */
    data class ExercisesPicked(val exerciseIds: List<Long>) : TemplateEditorEvent

    /** The picker is no longer up, however it went away. Clears the flag [ExercisesPickRequested] set. */
    data object ExercisesPickClosed : TemplateEditorEvent

    /** SAVE. Writes the whole template — name, category and body — through `UpdateTemplateUseCase`. */
    data object SaveRequested : TemplateEditorEvent

    /**
     * The user asked to leave.
     *
     * Raises the discard confirmation when the draft has been changed, and reports nothing when
     * it has not — the screen leaves on its own in that case, since there is nothing to lose.
     */
    data object BackRequested : TemplateEditorEvent

    /** Throw the edits away and leave. */
    data object DiscardConfirmed : TemplateEditorEvent

    /** Stay and keep editing. */
    data object DiscardDismissed : TemplateEditorEvent

    /** The snackbar has been shown, so the message can be forgotten. */
    data object MessageShown : TemplateEditorEvent
}
