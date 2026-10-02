package com.titan.core.domain.model

/**
 * Where a finish was asked for — which is the same question as "is anything on screen going to
 * celebrate what it earned".
 *
 * [com.titan.core.domain.usecase.FinishSessionUseCase] closes the session out identically
 * either way: this decides nothing about the row, the draft, the state machine or the cascade.
 * It exists for [com.titan.core.domain.notification.ProgressionAnnouncer], whose whole rule is
 * to say the payoff out loud only when nothing else will — and which cannot work that out for
 * itself.
 *
 * ### Why the caller has to say
 *
 * The obvious reading, "is the app in the foreground", answers a different question and gets the
 * two interesting cases the wrong way round. A workout closed out from the shade while the user
 * is on some other tab is foregrounded and has no recap coming, so the payoff would be announced
 * nowhere at all. The finish that *is* celebrated on screen, meanwhile, is celebrated by a recap
 * that has not been navigated to yet when the announcement is composed — so the foreground
 * reading is not even observing the thing it stands in for.
 *
 * The caller knows for certain, because the answer is a property of the code path rather than of
 * the process: one of these two navigates to a recap and the other cannot.
 */
enum class FinishOrigin {

    /**
     * The workout screen's own END button.
     *
     * The recap opens as the finish returns, carrying this same progression to it through
     * `SessionProgressionHandoff`, and celebrates the streak save with a banner and the drop
     * with a card. A notification about either would be the app talking over itself, which is
     * the fastest way to get the channel muted — and muting it costs the announcements that are
     * worth having.
     */
    WORKOUT_SCREEN,

    /**
     * The idle nudge's "Finish now", pressed in the notification shade.
     *
     * No recap is ever opened for this one. `IdleSessionBroadcasts` has no screen to navigate
     * and nowhere to hand a progression, whether or not the app happens to be running — so
     * whatever the session earned is announced here or nowhere. That is #178's case and the
     * whole reason this distinction is drawn.
     */
    NOTIFICATION_ACTION,
}
