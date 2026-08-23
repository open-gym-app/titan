package com.opengym.feature.history.template

import com.opengym.core.domain.model.SetType
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
     * A planned set's weight or reps was typed into.
     *
     * By [exerciseKey] and [setKey] rather than by index, so a keystroke that lands after a set
     * was inserted above it — or after the plan was reordered — still reaches the set it was
     * typed into. One event rather than one per figure, because the two differ only in which
     * cell they carry and the parsing they need is the same shape.
     */
    data class SetFieldChanged(
        val exerciseKey: Long,
        val setKey: Long,
        val field: TemplateSetField,
        val text: String,
    ) : TemplateEditorEvent

    /**
     * A planned set was made a warm-up, a drop set, or a standard set again.
     *
     * Its own event rather than a [SetFieldChanged] with a `SetType` in it: the menu's answer is
     * a closed vocabulary and arrives whole, where the two figures arrive a character at a time.
     * The same split the workout screen makes.
     */
    data class SetTypeChanged(
        val exerciseKey: Long,
        val setKey: Long,
        val setType: SetType,
    ) : TemplateEditorEvent

    /**
     * ADD SET on one exercise: another set on the end of its plan.
     *
     * The new set copies the one above it — its type, its weight, its reps — because the next
     * set of an exercise is usually the last one again, and a lifter planning `3 × 80 × 8` should
     * not type the same three figures three times. The first set of an exercise has nothing to
     * copy and starts blank. Refused past [MAX_SETS_PER_EXERCISE].
     */
    data class SetAdded(val exerciseKey: Long) : TemplateEditorEvent

    /**
     * A planned set was swiped away.
     *
     * By [setKey] for the reason every other edit is: the sets below it move up, so an index
     * resolved a frame later removes the wrong one. Removing the last set of an exercise leaves
     * the exercise in the plan with nothing planned — which is a line that says "do this, sets to
     * be decided" and a legitimate thing to save. Dropping the exercise is
     * [ExerciseRemoved], and the user has a control for that.
     */
    data class SetRemoved(val exerciseKey: Long, val setKey: Long) : TemplateEditorEvent

    /**
     * An exercise was dropped from the plan.
     *
     * By [key], not by exercise id: the same movement may legitimately appear twice in one
     * template, and an id would remove whichever came first. The sets it planned go with it. A
     * superset left with one member dissolves rather than being refused — see
     * `Supersets.normalize`.
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

    /**
     * The join below the exercise identified by [key] was tapped, making the two one superset.
     *
     * By [key] rather than by index, for the reason [SetFieldChanged] and [ExerciseRemoved] are:
     * the plan is drawn in the order being *dragged*, which is a frame ahead of the draft while
     * a reorder is being applied, and a join resolved against the wrong list of the two joins a
     * pair the user did not point at.
     */
    data class SupersetLinked(val key: Long) : TemplateEditorEvent

    /** The same join, tapped again: the two are no longer performed back to back. */
    data class SupersetUnlinked(val key: Long) : TemplateEditorEvent

    /**
     * The ⇄ on one exercise: plan a different movement in this slot.
     *
     * Records *which* slot the picker about to open is being asked about; the push is the
     * screen's, as it is for [ExercisesPickRequested]. Whichever of the two was tapped last is
     * the question the answer belongs to — see `TemplateEditorViewModel.swappingKey`.
     *
     * By [key] rather than by index or by exercise id, for the reason [ExerciseRemoved] is: the
     * plan is drawn in the order being dragged, and the same movement may legitimately appear
     * twice in one template.
     */
    data class ExerciseSwapRequested(val key: Long) : TemplateEditorEvent

    /** "Add exercises". Records that the picker is being opened; the push is the screen's. */
    data object ExercisesPickRequested : TemplateEditorEvent

    /**
     * The picker's answer: the exercises chosen, in the order they were picked, appended to the
     * end of the plan. Each arrives with one blank set — a line the user has not priced yet, but
     * one whose plan is a table they can already type into rather than an empty card with an
     * invitation on it.
     *
     * The same event answers a swap, where it carries the one exercise the slot is to hold —
     * see [ExerciseSwapRequested]. One event rather than two because the *picker* is one screen
     * that returns one list either way, and which question it answered is this screen's to
     * remember rather than the picker's to report.
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
