package com.opengym.core.domain.notification

/**
 * The one way anything in this app speaks to a user who is not looking at it.
 *
 * Every notification the app posts goes through here, and the point of that is the two checks
 * [post] makes before anything reaches the platform: the kind's stored preference, and the
 * permission. Both are easy to forget at a call site and impossible to forget here, which is
 * why no ViewModel, use case or worker builds a `NotificationCompat` of its own — the
 * implementation in `:core:data` is the only thing in the app that imports the notification
 * APIs at all.
 *
 * The interface is declared here rather than in `:core:data` so a use case can depend on it,
 * and so a test can hand one in without an Android runtime.
 */
interface Notifier {

    /**
     * Posts [notification], unless the user's preferences or the system say otherwise.
     *
     * Returns rather than throws for a refusal: neither "the user turned this off" nor "the
     * permission is missing" is exceptional, and both are the normal state of most kinds on
     * most installs. A caller that has something else to do about it — an in-app alert, say —
     * reads the [NotificationOutcome]; the ordinary caller ignores it.
     */
    suspend fun post(notification: AppNotification): NotificationOutcome

    /**
     * Takes back a notification of [kind], if one is showing.
     *
     * Ungated: cancelling is how an ongoing session note is cleared when the session ends, and
     * a preference the user turned *off* in the meantime must not be what leaves it stuck on
     * screen. [tag] must match the one it was posted with.
     */
    suspend fun cancel(
        kind: NotificationKind,
        tag: String? = null,
    )
}
