package com.opengym.feature.session.summary

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

    /**
     * "Save as template". Raises the dialog, seeded with the session's name; writes nothing.
     *
     * The name is asked for rather than taken, because the two are not the same thing often
     * enough to assume: a session is called "Push Day · Upper" *for the 28th*, and a template
     * is called "Push Day · Upper" forever. Asking also gives the duplicate-name refusal
     * somewhere to be reported before anything is written.
     */
    data object SaveAsTemplateRequested : SessionSummaryEvent

    /** The template name field changed. */
    data class TemplateNameChanged(val name: String) : SessionSummaryEvent

    /** The dialog was dismissed. Nothing was written. */
    data object SaveAsTemplateDismissed : SessionSummaryEvent

    /** The dialog was confirmed. This is the one that writes. */
    data object SaveAsTemplateConfirmed : SessionSummaryEvent

    /** The snackbar has been shown, so the message can be forgotten. */
    data object MessageShown : SessionSummaryEvent
}
