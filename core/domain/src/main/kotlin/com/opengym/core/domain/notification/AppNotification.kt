package com.opengym.core.domain.notification

/**
 * One thing to say, and which kind of thing it is.
 *
 * ### The text arrives resolved
 *
 * [title] and [body] are the words themselves, not resource ids. `:core:domain` has no
 * `Context` and no `R`, and giving it either to carry a notification would be the first crack
 * in the rule that makes it testable without an Android runtime. The caller resolves its own
 * strings from its own `strings.xml` — which is also where a translator will look for them,
 * and where #41's conventions apply — and a caller that has no `Context` to resolve them with
 * is a caller that should not be composing a notification.
 *
 * ### There is no id here
 *
 * A notification's numeric id is a platform detail, and one the caller has no way to allocate
 * safely: two features picking `1` overwrite each other silently. `:core:data` derives it from
 * [kind], so each kind has its own slot and re-posting the same kind *replaces* rather than
 * stacks — which is what all four consumers want. [tag] is the escape hatch for a kind that
 * needs more than one at a time (several scheduled reminders, say); the same tag replaces, a
 * different one sits alongside.
 *
 * @param kind what this is, which decides the channel it goes to, the preference that gates it
 *   and the id it takes.
 * @param title the first line.
 * @param body the second. Long text is collapsed by the platform, so the first sentence has to
 *   carry the meaning on its own.
 * @param tag distinguishes two live notifications of the same [kind]. Null for the usual case
 *   of one at a time.
 * @param ongoing the user cannot swipe it away, and it stays until the app cancels it. For a
 *   note about something the app is still doing — a session left running, a rest counting down
 *   — rather than for an announcement: dismissing it would not end the thing it describes.
 * @param silent posted without a sound, a buzz or a heads-up, however loud its channel is. For
 *   a notification that is *updated* rather than announced — the rest countdown is re-posted
 *   every time the user adjusts it, and a channel loud enough to be heard across a gym would
 *   otherwise announce each of those. The announcement that follows is a separate post with
 *   this off.
 * @param deepLink where a tap lands, as an `opengym://` URI from
 *   [com.opengym.core.domain.navigation.DeepLinks]. Null opens the app wherever it was, which
 *   is right for a notification with no particular screen behind it and wrong for every one
 *   that has one.
 * @param countdownTo epoch millis UTC the platform counts down to, drawn in place of the
 *   timestamp and ticked by the system itself. The reason a rest notification is posted **once**
 *   rather than re-posted every second: a shade that redraws its own countdown costs nothing,
 *   and a wake-up a second costs the battery of the phone in the user's pocket. Null for the
 *   ordinary case of a notification about something that already happened.
 * @param actions buttons along the bottom, in order. Each is a [NotificationCommand] the app
 *   can carry out with no screen of it alive — see [NotificationAction].
 */
data class AppNotification(
    val kind: NotificationKind,
    val title: String,
    val body: String,
    val tag: String? = null,
    val ongoing: Boolean = false,
    val silent: Boolean = false,
    val deepLink: String? = null,
    val countdownTo: Long? = null,
    val actions: List<NotificationAction> = emptyList(),
)

/**
 * A button on a notification: what it says, and what it does.
 *
 * @param label the word on the button. Resolved text, for the reason [AppNotification.title] is.
 * @param command what pressing it means. A value rather than a callback, because the press can
 *   arrive at a process that has been started from cold to receive it — there is no lambda from
 *   the moment the notification was posted still alive to invoke.
 */
data class NotificationAction(
    val label: String,
    val command: NotificationCommand,
)

/**
 * Something a notification's button asks the app to do.
 *
 * A sealed type rather than a string id and a bag of extras: every one of these has to survive
 * a round trip out to the system and back into a process that may be starting from nothing, and
 * a typed command is the difference between that round trip being checked by the compiler and
 * being checked by whoever notices the button stopped working.
 */
sealed interface NotificationCommand {

    /**
     * End the rest running in [sessionId] now — the lifter is back under the bar and said so
     * without unlocking.
     */
    data class SkipRest(val sessionId: Long) : NotificationCommand
}

/** What became of a [AppNotification] handed to a [Notifier]. */
enum class NotificationOutcome {

    /** Handed to the system. Whether it is *seen* is between the system and the user. */
    POSTED,

    /** The user turned this kind off. Not an error, and not worth telling them about. */
    SUPPRESSED_BY_PREFERENCE,

    /**
     * The permission is missing, so the system would drop it — or throw.
     *
     * Worth distinguishing from the preference case: this one the app can offer to fix, and a
     * caller with an in-app fallback (the rest timer's on-screen alert) may want to use it.
     */
    SUPPRESSED_BY_PERMISSION,
}
