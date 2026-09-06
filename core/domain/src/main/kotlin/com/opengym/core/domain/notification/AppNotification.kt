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
 * @param ongoing the user cannot swipe it away, and it stays until the app cancels it. For
 *   [NotificationKind.SESSION] alone — a session that is still running is a fact about the
 *   app's state, not an announcement, and dismissing the note would not end the session.
 */
data class AppNotification(
    val kind: NotificationKind,
    val title: String,
    val body: String,
    val tag: String? = null,
    val ongoing: Boolean = false,
)

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
