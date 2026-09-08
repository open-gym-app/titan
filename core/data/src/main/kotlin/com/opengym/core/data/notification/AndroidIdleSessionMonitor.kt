package com.opengym.core.data.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.notification.Notifier
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.session.IdleSessionMonitor
import com.opengym.core.domain.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The idle nudge, as an alarm.
 *
 * ### An inexact alarm, deliberately
 *
 * `setAndAllowWhileIdle` rather than the `setExactAndAllowWhileIdle` the rest timer uses, and
 * the contrast is the point: nobody is waiting on this. A rest timer that is two minutes late
 * has failed at the one thing it does; an idle nudge that arrives at thirty-four minutes
 * instead of thirty says exactly the same thing. Inexact also costs no exact-alarm permission,
 * which is not a permission to spend on a reminder — and it lets the system fold this wake-up
 * in with whatever else it was going to do.
 *
 * `RTC_WAKEUP` against a wall-clock deadline, because the deadline is derived from a set's
 * `completed_at`, which is wall clock. One number, read from the store, rather than a second
 * elapsed-time reading that would have to be kept in step with it.
 *
 * ### The deadline is read, never passed in
 *
 * [arm] takes no arguments and goes to the store for the session and its sets. A caller handing
 * over a timestamp it read a moment ago would be handing over one that a set logged in between
 * has already moved — and the callers are a ViewModel and a broadcast receiver, neither of
 * which should be deciding what "idle" means.
 *
 * ### Nothing arms this when a session *starts*
 *
 * Deliberately, and it is not a gap. The active session screen disarms the moment it comes on
 * screen — nobody needs telling about a workout they are looking at — and a session that has
 * just been started is a session whose screen is in front of the user. An arm at start would be
 * undone by the `ON_START` that follows it on the next frame. The first arm that survives is the
 * one on the way *out*, which is also the first moment the session could be abandoned.
 */
@Singleton
class AndroidIdleSessionMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val notifier: Notifier,
    private val clock: Clock,
) : IdleSessionMonitor {

    /**
     * Points the alarm at the session as it stands, and clears anything already said.
     *
     * The notification is taken down first and unconditionally. Either the session has moved on
     * — a set was logged, so a nudge saying nothing has happened is now false — or the user has
     * left a screen that had already cleared it, where cancelling again costs nothing.
     */
    override suspend fun arm() {
        notifier.cancel(NotificationKind.SESSION)

        val session = sessionRepository.getActive().first()
        if (session == null) {
            // Nothing in progress. Whatever was pending belongs to a workout that is over.
            cancelAlarm()
            return
        }

        val deadline = idleDeadline(session)
        if (deadline <= clock.nowMillis()) {
            // The idle period has already been announced — or has passed with the user in the
            // app the whole time, which is the same thing as far as they are concerned. This is
            // the whole of "once per idle period": the deadline stays behind us until a set
            // moves it. See `IdleSessionMonitor`.
            cancelAlarm()
            return
        }

        schedule(session.id, deadline)
    }

    override suspend fun disarm() {
        cancelAlarm()
        notifier.cancel(NotificationKind.SESSION)
    }

    /**
     * When [session] stops counting as busy: its most recent completed set, plus the threshold.
     *
     * A session with nothing logged is measured from when it was *started*, which is the case
     * the nudge matters most for — a workout opened by accident, or one abandoned before the
     * first set, is otherwise open forever with nothing to measure from.
     *
     * `completed_at` on the set rather than `last_active_at` on the session: `LogSetUseCase`
     * deliberately does not stamp the session on every set — a write to `workout_session` per
     * set would re-emit every observer of the session list for the length of a workout — and it
     * says so, naming the set's own timestamp as the record of the same fact.
     */
    private suspend fun idleDeadline(session: WorkoutSession): Long {
        val lastSetAt = sessionRepository.getSetsForSession(session.id).first()
            .filter(WorkoutSet::isCompleted)
            .maxOfOrNull(WorkoutSet::completedAt)

        return (lastSetAt ?: session.createdAt) + IdleSessionMonitor.IDLE_THRESHOLD.inWholeMilliseconds
    }

    /** Replaces whatever was pending with one wake-up at [deadline]. Best-effort; see the class note. */
    private fun schedule(
        sessionId: Long,
        deadline: Long,
    ) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, idleIntent(context, sessionId))
        }
    }

    /**
     * Takes the alarm back.
     *
     * `AlarmManager` matches a pending intent by action and component and **not** by its extras,
     * so an intent rebuilt here with no session in it cancels whichever session was scheduled.
     */
    private fun cancelAlarm() {
        val pending = idleIntent(context, sessionId = null)
        context.getSystemService(AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }
}

/**
 * The broadcast the idle alarm delivers, naming the session it is about.
 *
 * The id is all it carries. What the notification *says* — how many sets are in the session, and
 * therefore whether the second button reads "Finish now" or "Discard" — is read when the alarm
 * fires rather than baked in thirty minutes earlier, because thirty minutes is long enough for
 * the answer to have changed.
 *
 * [sessionId] is null when the intent is only being built to cancel the alarm; extras are not
 * part of the equality `AlarmManager` matches on.
 */
internal fun idleIntent(
    context: Context,
    sessionId: Long?,
): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        IDLE_SESSION_REQUEST_CODE,
        idleSessionBroadcast(context, sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** The broadcast itself, so both ends of the round trip can be exercised without an alarm between. */
internal fun idleSessionBroadcast(
    context: Context,
    sessionId: Long?,
): Intent =
    Intent(context, IdleSessionReceiver::class.java)
        .setAction(ACTION_SESSION_IDLE)
        .apply { if (sessionId != null) putExtra(EXTRA_SESSION_ID, sessionId) }

/** The session [intent] says has gone idle, or null when it says nothing this build understands. */
internal fun idleSessionOf(intent: Intent): Long? {
    if (intent.action != ACTION_SESSION_IDLE) return null
    return intent.getLongExtra(EXTRA_SESSION_ID, 0L).takeIf { it > 0 }
}

private const val ACTION_SESSION_IDLE = "com.opengym.core.data.notification.SESSION_IDLE"
private const val EXTRA_SESSION_ID = "session_id"

/**
 * One code, because at most one session runs at a time — the premise
 * `InProgressSessionRepository` is built on. `FLAG_UPDATE_CURRENT` refreshes the extras when the
 * next session reuses it.
 */
private const val IDLE_SESSION_REQUEST_CODE = 2
