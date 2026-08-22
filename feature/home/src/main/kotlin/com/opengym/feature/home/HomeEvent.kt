package com.opengym.feature.home

/**
 * Everything the home screen can report.
 *
 * Navigation is *not* here. Resuming the open workout, starting an empty session, opening the
 * template list and starting from one of the quick-start cards all leave the screen, and a
 * feature does not own the `NavController` — those are parameters of the screen rather than
 * events, the same split `SessionsEvent` makes. What is left is the one thing Home does on its
 * own: throwing the open workout away.
 */
sealed interface HomeEvent {

    /** The banner's Discard was tapped. Asks the question; nothing is deleted yet. */
    data object DiscardRequested : HomeEvent

    /** The question was answered yes. This is the one that deletes the workout. */
    data object DiscardConfirmed : HomeEvent

    /** The question was dismissed — the back gesture, the scrim, or "Keep training". */
    data object DiscardDismissed : HomeEvent

    /** The snackbar has been shown, so the message can be dropped. */
    data object MessageShown : HomeEvent
}
