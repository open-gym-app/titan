package com.titan.core.data.notification

import android.content.Context
import android.content.Intent
import com.titan.core.domain.model.SessionRef
import com.titan.core.domain.notification.NotificationCommand

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
    private const val ACTION_SKIP_REST = "com.titan.core.data.notification.SKIP_REST"
    private const val ACTION_FINISH_SESSION = "com.titan.core.data.notification.FINISH_SESSION"
    private const val ACTION_DISCARD_SESSION = "com.titan.core.data.notification.DISCARD_SESSION"

    private const val EXTRA_SESSION_ID = "session_id"
    private const val EXTRA_SESSION_CREATED_AT = "session_created_at"

    /** The intent that carries [command] to the receiver that handles it. */
    fun intent(
        context: Context,
        command: NotificationCommand,
    ): Intent =
        when (command) {
            is NotificationCommand.SkipRest ->
                broadcast<RestTimerReceiver>(context, ACTION_SKIP_REST, command.session.sessionId)
                    .putSessionToken(command.session)

            is NotificationCommand.FinishSession ->
                broadcast<IdleSessionReceiver>(context, ACTION_FINISH_SESSION, command.session.sessionId)
                    .putSessionToken(command.session)

            is NotificationCommand.DiscardSession ->
                broadcast<IdleSessionReceiver>(context, ACTION_DISCARD_SESSION, command.session.sessionId)
                    .putSessionToken(command.session)
        }

    /**
     * The command [intent] carries, or **null** when it carries none this build understands.
     *
     * The two commands that end a workout are read back only with the session's creation time
     * beside its id — see [SessionRef]. One without it names a session by its number alone, which
     * is not enough to act on from outside the process, so it is dropped like any other intent
     * this build cannot read.
     *
     * "Skip rest" is read back without one too, as a [SessionRef] that matches on the id alone. It
     * ends a countdown and deletes nothing, and a note posted by an earlier build, or before the
     * workout screen had read its session, carries none: dropping the press would leave an
     * ongoing note the user cannot dismiss.
     */
    fun of(intent: Intent): NotificationCommand? {
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, NO_SESSION).takeIf { it != NO_SESSION }
            ?: return null

        return when (intent.action) {
            ACTION_SKIP_REST -> NotificationCommand.SkipRest(sessionRefOf(intent, sessionId) ?: SessionRef(sessionId))
            ACTION_FINISH_SESSION -> sessionRefOf(intent, sessionId)?.let(NotificationCommand::FinishSession)
            ACTION_DISCARD_SESSION -> sessionRefOf(intent, sessionId)?.let(NotificationCommand::DiscardSession)
            else -> null
        }
    }

    /**
     * Writes [session]'s creation time beside the id [broadcast] already put on this intent, when
     * it has one. Read back by [sessionRefOf].
     */
    private fun Intent.putSessionToken(session: SessionRef): Intent =
        apply { session.createdAt?.let { putExtra(EXTRA_SESSION_CREATED_AT, it) } }

    /** The session [intent] names, with its creation time, or null when the time is missing. */
    private fun sessionRefOf(
        intent: Intent,
        sessionId: Long,
    ): SessionRef? {
        if (!intent.hasExtra(EXTRA_SESSION_CREATED_AT)) return null
        return SessionRef(sessionId = sessionId, createdAt = intent.getLongExtra(EXTRA_SESSION_CREATED_AT, 0L))
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
