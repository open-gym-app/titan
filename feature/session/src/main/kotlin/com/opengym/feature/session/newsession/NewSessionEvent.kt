package com.opengym.feature.session.newsession

/**
 * Everything the new-session screen can report.
 *
 * Navigation is not here, with one deliberate seam: opening the exercise picker and entering
 * the started session both leave the screen, and a feature does not own the `NavController`.
 * Those are parameters of the screen — see `NewSessionScreen`. [ExerciseSelectionOpened] is
 * the event that goes *with* one of them, so the ViewModel knows the picker is up; the push
 * itself is `:app`'s.
 */
sealed interface NewSessionEvent {

    /**
     * The quick-start action. Records that the picker is being opened.
     *
     * Named for what happened rather than for what it opens, like every other event here: the
     * ViewModel does not know that choosing exercises is a separate destination, only that
     * the user is now choosing them.
     */
    data object ExerciseSelectionOpened : NewSessionEvent

    /** A template row was tapped. Reads it out and goes to ready-to-start. */
    data class TemplateSelected(val templateId: Long) : NewSessionEvent

    /**
     * The picker's answer: the exercises chosen, in the order they were picked, which
     * becomes the order of the session.
     *
     * An empty list is the picker having been backed out of, and returns the screen to the
     * template list rather than to an empty ready-to-start panel.
     */
    data class ExercisesSelected(val exerciseIds: List<Long>) : NewSessionEvent

    /**
     * An exercise was dropped from the ready-to-start list.
     *
     * By **index**, not by exercise id: the same exercise may legitimately appear twice in
     * one session, and an id would remove whichever came first. Removing the last one puts
     * the screen back to the template list — a review panel with nothing on it is a dead end
     * whose only way out is the one the back gesture already offers.
     */
    data class ExerciseRemoved(val index: Int) : NewSessionEvent

    /**
     * The user backed out of ready-to-start.
     *
     * Discards the plan and returns to the template list. Nothing has been written, so there
     * is nothing to undo — which is the whole reason the plan is held here and not in the
     * database.
     */
    data object PlanDiscarded : NewSessionEvent

    /** START. Writes the session and its exercises, then reports the id. */
    data object SessionStartRequested : NewSessionEvent

    /** The snackbar has been shown, so the message can be forgotten. */
    data object MessageShown : NewSessionEvent
}
