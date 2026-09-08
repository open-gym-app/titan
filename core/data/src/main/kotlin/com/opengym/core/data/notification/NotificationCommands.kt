package com.opengym.core.data.notification

import android.content.Context
import android.content.Intent
import com.opengym.core.domain.notification.NotificationCommand

/**
 * A [NotificationCommand] on its way out to the system and back, as an [Intent].
 *
 * ### Why the round trip needs its own file
 *
 * A notification button's press can arrive at a process that was started from nothing to
 * receive it — the app may have been dead for an hour when the user taps "Skip rest" on their
 * lock screen. Nothing from the moment the notification was posted survives that except the
 * intent, so the command has to be written down as extras and read back by [of].
 *
 * The two halves are here together on purpose. Split across the builder and the receiver they
 * are two lists of string keys that have to agree, and the failure when they stop agreeing is a
 * button that silently does nothing.
 *
 * ### The routing table
 *
 * Which receiver each command goes to is decided in [intent] and nowhere else, so a command's
 * home is one line rather than a fact spread across a manifest and a `when`. [of] decodes by
 * *action* and ignores the component, which is what lets a receiver read a command without
 * having to know it was the one addressed.
 */
internal object NotificationCommands {

    /**
     * The action each command travels under.
     *
     * Package-qualified because an implicit broadcast on a bare name is one any app on the
     * device can send. The intents built here are explicit anyway — each names its receiver —
     * so these distinguish one command from another rather than routing it.
     */
    private const val ACTION_SKIP_REST = "com.opengym.core.data.notification.SKIP_REST"
    private const val ACTION_FINISH_SESSION = "com.opengym.core.data.notification.FINISH_SESSION"
    private const val ACTION_DISCARD_SESSION = "com.opengym.core.data.notification.DISCARD_SESSION"

    private const val EXTRA_SESSION_ID = "session_id"

    /** The intent that carries [command] to the receiver that handles it. */
    fun intent(
        context: Context,
        command: NotificationCommand,
    ): Intent =
        when (command) {
            is NotificationCommand.SkipRest ->
                broadcast<RestTimerReceiver>(context, ACTION_SKIP_REST, command.sessionId)

            is NotificationCommand.FinishSession ->
                broadcast<IdleSessionReceiver>(context, ACTION_FINISH_SESSION, command.sessionId)

            is NotificationCommand.DiscardSession ->
                broadcast<IdleSessionReceiver>(context, ACTION_DISCARD_SESSION, command.sessionId)
        }

    /** The command [intent] carries, or **null** when it carries none this build understands. */
    fun of(intent: Intent): NotificationCommand? {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, NO_SESSION).takeIf { it != NO_SESSION }
            ?: return null

        return when (intent.action) {
            ACTION_SKIP_REST -> NotificationCommand.SkipRest(sessionId)
            ACTION_FINISH_SESSION -> NotificationCommand.FinishSession(sessionId)
            ACTION_DISCARD_SESSION -> NotificationCommand.DiscardSession(sessionId)
            else -> null
        }
    }

    /**
     * A code that keeps two simultaneously live pending intents for the same command apart.
     *
     * Derived from the command's own contents rather than from a counter, so it is the same
     * number every time the same button is rebuilt — which is what makes re-posting a
     * notification update the button it already had rather than leave a second one behind.
     */
    fun requestCode(command: NotificationCommand): Int = command.hashCode()

    private inline fun <reified T> broadcast(
        context: Context,
        action: String,
        sessionId: Long,
    ): Intent =
        Intent(context, T::class.java)
            .setAction(action)
            .putExtra(EXTRA_SESSION_ID, sessionId)

    /** `getLongExtra` needs a default, and no session row has this id. */
    private const val NO_SESSION = -1L
}
