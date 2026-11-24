package com.titan.feature.session.summary

import com.titan.core.domain.model.TemplateCategory

/**
 * Everything the session recap can report.
 *
 * Navigation is not here: leaving the recap ends the session flow in one mode and pops back
 * to the session list in the other, and a feature does not own the `NavController`. That is a
 * parameter of the screen — see `SessionSummaryScreen`.
 *
 * The two writes the screen can make are the note and the template, and they are separate
 * sets of events because they are separate writes with different failure modes: a note is one
 * column on a row that already exists, and a template is a new row plus its exercises, which
 * can be refused for a name that is taken.
 */
sealed interface SessionSummaryEvent {

    /** Open the note field, seeded with whatever is stored. */
    data object NoteEditRequested : SessionSummaryEvent

    /** The note field changed. */
    data class NoteChanged(val text: String) : SessionSummaryEvent

    /** Close the note field, discarding what was typed. */
    data object NoteEditCancelled : SessionSummaryEvent

    /** Write the note. A blank note clears the column rather than storing whitespace. */
    data object NoteSaved : SessionSummaryEvent

    /** A star was tapped: [rating] becomes the rating, or clears it if it already was. */
    data class RatingChosen(val rating: Int) : SessionSummaryEvent

    data object CaloriesEditRequested : SessionSummaryEvent

    data class CaloriesChanged(val text: String) : SessionSummaryEvent

    data object CaloriesEditCancelled : SessionSummaryEvent

    data object CaloriesSaved : SessionSummaryEvent

    /**
     * "Save as template". Raises the dialog, seeded with the session's name; writes nothing.
     *
     * The name is asked for rather than taken, because the two are not the same thing often
     * enough to assume: a session is called "Push Day · Upper" *for the 28th*, and a template
     * is called "Push Day · Upper" forever. Asking also gives the duplicate-name refusal
     * somewhere to be reported before anything is written.
     */
    data object SaveAsTemplateRequested : SessionSummaryEvent

    /** The template name field changed. Also clears a name-already-taken refusal. */
    data class TemplateNameChanged(val name: String) : SessionSummaryEvent

    /**
     * A category chip was chosen, or `null` for the `None` chip.
     *
     * Asked here rather than left to the editor, because this is the one moment the user has the
     * workout in front of them: a template categorised on the way out of the gym is one whose
     * chip means something, and one categorised in a list a week later is a guess.
     */
    data class TemplateCategoryChanged(val category: TemplateCategory?) : SessionSummaryEvent

    /** The dialog was dismissed. Nothing was written. */
    data object SaveAsTemplateDismissed : SessionSummaryEvent

    /** The dialog was confirmed. This is the one that writes. */
    data object SaveAsTemplateConfirmed : SessionSummaryEvent

    /** Open the rename dialog on whatever the session is called now. */
    data object NameEditRequested : SessionSummaryEvent

    /** The rename field changed. */
    data class NameChanged(val text: String) : SessionSummaryEvent

    /** Close the rename dialog without writing. */
    data object NameEditCancelled : SessionSummaryEvent

    /** Write the name as typed. Blank clears it, which is the only way back to unnamed. */
    data object NameConfirmed : SessionSummaryEvent

    /** The snackbar has been shown, so the message can be forgotten. */
    data object MessageShown : SessionSummaryEvent
}
