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

    /**
     * The picker is no longer up, however it went away.
     *
     * The counterpart of [ExerciseSelectionOpened], and the only thing that clears it. A
     * confirmed selection arrives as [ExercisesSelected] and would clear it anyway; a
     * *cancelled* one arrives as nothing at all, because the back arrow, the system gesture
     * and a predictive-back swipe all just pop the picker. Without this the screen would go on
     * believing the picker was open, and the quick-start action it disables while that is true
     * would never come back.
     *
     * Reported from the screen being on top again rather than from a cancel button, because
     * that is the one signal every way out of the picker has in common — see `NewSessionRoute`.
     */
    data object ExerciseSelectionClosed : NewSessionEvent

    /** A template row was tapped. Reads it out and goes to ready-to-start. */
    data class TemplateSelected(val templateId: Long) : NewSessionEvent

    /**
     * The picker's answer: the exercises chosen, in the order they were picked, which
     * becomes the order of the session.
     *
     * Never empty in practice — the picker's confirm bar is disabled until something is
     * ticked, and a cancel arrives as [ExerciseSelectionClosed] rather than as an empty
     * answer. An empty list is still handled, as the template list rather than a
     * ready-to-start panel with nothing on it.
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

    /** Open the name dialog on whatever the plan is called now. */
    data object NameEditRequested : NewSessionEvent

    /** The name field changed. */
    data class NameChanged(val text: String) : NewSessionEvent

    /** Close the name dialog without applying it. */
    data object NameEditCancelled : NewSessionEvent

    /**
     * Apply the name to the plan.
     *
     * Nothing is written: the plan lives in the ViewModel until START, so this records a
     * decision rather than renaming a session. `StartSessionUseCase` applies the same
     * blank-means-unnamed rule on the way in that `RenameSessionUseCase` applies later.
     */
    data object NameConfirmed : NewSessionEvent

    /** The snackbar has been shown, so the message can be forgotten. */
    data object MessageShown : NewSessionEvent
}
