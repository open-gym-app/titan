package com.titan.feature.history.detail

import com.titan.feature.history.exercise.ExerciseDraft

/**
 * Everything one exercise's screen can report.
 *
 * Navigation is *not* here: leaving, adding to a session and jumping to the plate calculator
 * all end up somewhere else, and a feature does not own the `NavController`. Those are
 * parameters of the screen, not events — see [ExerciseDetailScreen].
 *
 * The note and the whole-exercise edit are two sets of events rather than one, because they
 * are two different writes with two different rules: anybody may annotate any exercise, and
 * only the author of a custom one may rewrite it.
 */
sealed interface ExerciseDetailEvent {

    /** Open the note for editing, seeded with whatever is stored. */
    data object NoteEditRequested : ExerciseDetailEvent

    /** The note field changed. */
    data class NoteChanged(val text: String) : ExerciseDetailEvent

    /** Close the note field, discarding what was typed. */
    data object NoteEditCancelled : ExerciseDetailEvent

    /** Write the note. A blank note clears the column rather than storing whitespace. */
    data object NoteSaved : ExerciseDetailEvent

    /** The Edit action. Opens the form on this exercise. Ignored for a seeded one. */
    data object EditRequested : ExerciseDetailEvent

    /** A field in the edit form changed. Carries the whole draft. */
    data class EditChanged(val draft: ExerciseDraft) : ExerciseDetailEvent

    /** Save the edit. Ignored unless [ExerciseDraft.canSave]. */
    data object EditSubmitted : ExerciseDetailEvent

    /** The form was dismissed. Whatever was typed is discarded. */
    data object EditDismissed : ExerciseDetailEvent

    /** The Delete action. Raises the confirmation; deletes nothing. */
    data object DeleteRequested : ExerciseDetailEvent

    /** The confirmation was accepted. This is the destructive one. */
    data object DeleteConfirmed : ExerciseDetailEvent

    /** The confirmation was dismissed. */
    data object DeleteDismissed : ExerciseDetailEvent

    /** The snackbar has been shown, so the message can be forgotten. */
    data object MessageShown : ExerciseDetailEvent
}
