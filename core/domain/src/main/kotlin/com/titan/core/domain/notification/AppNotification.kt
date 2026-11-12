package com.titan.core.domain.notification

import com.titan.core.domain.model.SessionRef

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
 * @param title the first line, and the only one a lock screen that hides sensitive content
 *   shows. So it never carries the user's own words — an exercise, a workout's name, its
 *   notes; those go in [body].
 * @param body the second. Long text is collapsed by the platform, so the first sentence has to
 *   carry the meaning on its own. Hidden on a lock screen set to hide sensitive content.
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
 * @param deepLink where a tap lands, as an `titan://` URI from
 *   [com.titan.core.domain.navigation.DeepLinks]. Null opens the app wherever it was, which
 *   is right for a notification with no particular screen behind it and wrong for every one
 *   that has one.
 * @param countdownTo epoch millis UTC the platform counts down to, drawn in place of the
 *   timestamp and ticked by the system itself. The reason a rest notification is posted **once**
 *   rather than re-posted every second: a shade that redraws its own countdown costs nothing,
 *   and a wake-up a second costs the battery of the phone in the user's pocket. Null for the
 *   ordinary case of a notification about something that already happened.
 * @param actions buttons along the bottom, in order — see [NotificationAction]. Each either
 *   opens a screen or does something with no screen at all; Android draws at most three, so a
 *   notification with more than that is asking the user to choose from a list it will truncate.
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
 * @param target what pressing it means. A value rather than a callback, because the press can
 *   arrive at a process that has been started from cold to receive it — there is no lambda from
 *   the moment the notification was posted still alive to invoke.
 */
data class NotificationAction(
    val label: String,
    val target: NotificationTarget,
)

/**
 * Where a notification's button leads.
 *
 * Two kinds, and the split is the platform's rather than a taste: opening a screen and running
 * something without one are a different sort of pending intent, and only one of them is allowed
 * to bring the app to the front from the background. A button that means "do this and stay out
 * of my way" must be the second sort — dragging somebody into a screen they did not ask for is
 * the opposite of what a notification action is for.
 */
sealed interface NotificationTarget {

    /**
     * Opens a screen, by an `titan://` URI from
     * [com.titan.core.domain.navigation.DeepLinks].
     */
    data class Screen(val deepLink: String) : NotificationTarget

    /** Carries out [command] with no screen at all. */
    data class Command(val command: NotificationCommand) : NotificationTarget
}

/**
 * Something a notification's button asks the app to do, with nothing of it on screen.
 *
 * A sealed type rather than a string id and a bag of extras: every one of these has to survive
 * a round trip out to the system and back into a process that may be starting from nothing, and
 * a typed command is the difference between that round trip being checked by the compiler and
 * being checked by whoever notices the button stopped working.
 *
 * Each of them is also a thing the user can ask for *twice* — a double tap, or a tap racing the
 * same action taken in the app — so each must be safe to carry out again. That is the session
 * lifecycle's own guarantee rather than something the receiver arranges: every one of these goes
 * through a use case that reads the session's state first and refuses a transition that has
 * already happened.
 */
sealed interface NotificationCommand {

    /**
     * End the rest running in [sessionId] now — the lifter is back under the bar and said so
     * without unlocking.
     */
    data class SkipRest(val sessionId: Long) : NotificationCommand

    /**
     * Close [session] out with what it has logged, from the idle nudge.
     *
     * Not a quiet, special-cased finish: it runs the same pipeline the END button runs, so a
     * workout closed from a notification earns exactly what its sets earn. Refused, harmlessly,
     * for a session that is no longer in progress — or that is not the one open, which a
     * [SessionRef] carrying the row's creation time is what lets the use case tell.
     */
    data class FinishSession(val session: SessionRef) : NotificationCommand

    /**
     * Throw [session] away, from the idle nudge.
     *
     * Offered in place of [FinishSession] for a session with nothing logged in it — there is no
     * history worth writing for a workout that never started, and finishing one is refused
     * anyway. The press re-checks that, since a set can be logged between the post and the tap.
     */
    data class DiscardSession(val session: SessionRef) : NotificationCommand
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
