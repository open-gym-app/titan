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
 */
internal object NotificationCommands {

    /**
     * The action every command travels under.
     *
     * Package-qualified because an implicit broadcast on a bare name is one any app on the
     * device can send. The intents built here are explicit anyway — they name [RestTimerReceiver]
     * — so this is what distinguishes one command from another rather than what routes it.
     */
    private const val ACTION_SKIP_REST = "com.opengym.core.data.notification.SKIP_REST"

    private const val EXTRA_SESSION_ID = "session_id"

    /** The intent that carries [command] to [RestTimerReceiver]. */
    fun intent(
        context: Context,
        command: NotificationCommand,
    ): Intent =
        when (command) {
            is NotificationCommand.SkipRest ->
                Intent(context, RestTimerReceiver::class.java)
                    .setAction(ACTION_SKIP_REST)
                    .putExtra(EXTRA_SESSION_ID, command.sessionId)
        }

    /** The command [intent] carries, or **null** when it carries none this build understands. */
    fun of(intent: Intent): NotificationCommand? =
        when (intent.action) {
            ACTION_SKIP_REST ->
                intent.getLongExtra(EXTRA_SESSION_ID, NO_SESSION)
                    .takeIf { it != NO_SESSION }
                    ?.let(NotificationCommand::SkipRest)

            else -> null
        }

    /**
     * A code that keeps two simultaneously live pending intents for the same command apart.
     *
     * Derived from the command's own contents rather than from a counter, so it is the same
     * number every time the same button is rebuilt — which is what makes re-posting a
     * notification update the button it already had rather than leave a second one behind.
     */
    fun requestCode(command: NotificationCommand): Int = command.hashCode()

    /** `getLongExtra` needs a default, and no session row has this id. */
    private const val NO_SESSION = -1L
}
