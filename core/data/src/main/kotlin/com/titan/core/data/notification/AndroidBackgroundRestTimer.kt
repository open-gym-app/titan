package com.titan.core.data.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import com.titan.core.data.R
import com.titan.core.domain.navigation.DeepLinks
import com.titan.core.domain.notification.AppNotification
import com.titan.core.domain.notification.NotificationAction
import com.titan.core.domain.notification.NotificationCommand
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationOutcome
import com.titan.core.domain.notification.NotificationTarget
import com.titan.core.domain.notification.Notifier
import com.titan.core.domain.timer.BackgroundRest
import com.titan.core.domain.timer.BackgroundRestTimer
import com.titan.core.domain.timer.RestAlertStyle
import com.titan.core.domain.timer.RestTimerSnapshot
import com.titan.core.domain.timer.RingerMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rest timer as the operating system keeps it: a note in the shade, and an alarm at the
 * deadline.
 *
 * ### An alarm *and* a notification, because neither is the other's job
 *
 * The notification is what a lifter reads. `setUsesChronometer` hands the countdown to the
 * platform, which redraws it in place from the deadline it was given — so the rest is posted
 * **once** and ticks for ninety seconds without this process waking up at all. Re-posting every
 * second would give the same picture at the cost of ninety wake-ups and a shade that flickers.
 *
 * The alarm is what makes the deadline *happen*. A notification counting down to zero is a
 * drawing; nothing is scheduled behind it, so a phone in a gym bag would learn the rest was
 * over whenever it next happened to wake, which in Doze is minutes.
 * `setExactAndAllowWhileIdle` is the one API that fires on time through Doze, and it is why
 * this is not a `WorkManager` job: the whole value of the feature is being on time.
 *
 * ### No foreground service
 *
 * The reflex would be a foreground service for the length of the workout. Android 14's
 * `FOREGROUND_SERVICE_TYPE` rules make a rest timer hard to justify, and it buys nothing an
 * exact alarm does not already give — with a service lifecycle to get wrong on top. Recorded
 * here because "why is there no service" is the first question this file invites.
 *
 * ### Announcing the expiry exactly once
 *
 * Two things can notice the rest ending: this alarm, and `RestTimerController` in a process
 * that is still running. Both land in [expire], and it takes the decision against
 * [AppForegroundState] rather than against whether an activity exists — the activity behind a
 * backgrounded workout is alive and its countdown is still ticking.
 *
 * With the app in front of the user, the in-app alert says it and the note is taken *down*;
 * with the app away, the note says it. And when *both* arrive, the second is dropped — which is
 * not a theoretical case but the headline one: a phone in Doze does not run the coroutine
 * `delay` behind the countdown, so the alarm fires on time and the in-process timer catches up
 * seconds later when the device wakes. Two identical posts to one id alert twice, so [announced]
 * remembers what was said.
 */
@Singleton
class AndroidBackgroundRestTimer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val notifier: Notifier,
    private val foreground: AppForegroundState,
) : BackgroundRestTimer {

    /**
     * The expiry already announced, so the same one is not announced twice.
     *
     * Compared by value rather than by a flag, because a session expires a rest per set: the
     * duplicate to suppress is byte-for-byte the same rest, and the next one differs by its set
     * number. Cleared whenever a rest starts, is held or goes away, so a rest that somehow
     * repeats itself exactly can still be announced again.
     *
     * Not synchronised. Both writers reach it from the main dispatcher — the ViewModel's scope
     * and the receiver's `Dispatchers.Main.immediate` — and the cost of losing the race would be
     * the duplicate alert this exists to avoid, not a broken state.
     */
    private var announced: BackgroundRest? = null

    override suspend fun set(rest: BackgroundRest?) {
        if (rest == null) return clear()

        when (val snapshot = rest.rest) {
            is RestTimerSnapshot.Running -> countDown(rest, snapshot.deadline)
            is RestTimerSnapshot.Paused -> hold(rest)
            RestTimerSnapshot.Finished -> expire(rest)
            RestTimerSnapshot.Idle -> clear()
        }
    }

    /**
     * A rest in flight: the ticking note, and the alarm that ends it.
     *
     * The alarm is scheduled only if the note was actually posted. A user who has turned rest
     * notifications off, or never granted the permission, gets neither — waking the device for
     * an announcement that would be dropped is the one combination with no value at all, and
     * `Notifier` is the single place that knows which it is.
     */
    private suspend fun countDown(
        rest: BackgroundRest,
        deadline: Long,
    ) {
        announced = null
        val posted = notifier.post(
            AppNotification(
                kind = NotificationKind.REST_TIMER,
                title = context.getString(R.string.rest_notification_resting),
                body = rest.nextUp(),
                ongoing = true,
                // Quiet, on a channel whose whole job is to be heard: this note is *updated*
                // every time the rest is adjusted, and the announcement it builds towards is
                // the separate post in [expire]. See `AppNotification.silent`.
                silent = true,
                deepLink = DeepLinks.activeSession(rest.sessionId),
                countdownTo = deadline,
                actions = listOf(
                    NotificationAction(
                        label = context.getString(R.string.rest_notification_skip),
                        target = NotificationTarget.Command(NotificationCommand.SkipRest(rest.sessionId)),
                    ),
                ),
            ),
        )

        if (posted == NotificationOutcome.POSTED) {
            schedule(rest, deadline)
        } else {
            cancelAlarm()
        }
    }

    /**
     * A rest the user has paused: the note stays, and the alarm goes.
     *
     * The cancellation is the part that matters. A paused countdown consumes no time, so an
     * alarm left standing announces the end of a rest the user stopped — which is the worst
     * thing this class can do, and the reason [set] takes the whole state every time rather
     * than a `pause()` somebody can forget to call.
     *
     * The note carries no chronometer, because there is nothing for one to count to: what is
     * left does not move. It says so in its title instead.
     */
    private suspend fun hold(rest: BackgroundRest) {
        announced = null
        cancelAlarm()
        notifier.post(
            AppNotification(
                kind = NotificationKind.REST_TIMER,
                title = context.getString(R.string.rest_notification_paused),
                body = rest.nextUp(),
                ongoing = true,
                silent = true,
                deepLink = DeepLinks.activeSession(rest.sessionId),
                actions = listOf(
                    NotificationAction(
                        label = context.getString(R.string.rest_notification_skip),
                        target = NotificationTarget.Command(NotificationCommand.SkipRest(rest.sessionId)),
                    ),
                ),
            ),
        )
    }

    /**
     * The rest is over. Say so, unless the user is already looking at the app saying so.
     *
     * The alarm is cancelled either way, because [expire] is reached from two directions and
     * only one of them is the alarm firing: a rest *shortened past zero* finishes early and
     * leaves one pending for the deadline it would have had.
     *
     * A phone that has been silenced gets the note without the noise. The system already
     * applies that rule to a channel's own sound, and this states it a second time for the
     * cases where it does not — a Do Not Disturb override the user granted the app, an OEM
     * that treats a high-importance channel as exempt. It is the same rule the in-app alert
     * follows, out of the same function: see [RestAlertStyle].
     */
    private suspend fun expire(rest: BackgroundRest) {
        cancelAlarm()

        if (foreground.isInForeground()) {
            // `RestExpiryAlert` is ringing on the screen the user is holding. A notification
            // beside it is the second half of a double alert.
            announced = null
            notifier.cancel(NotificationKind.REST_TIMER)
            return
        }

        // Already said, by whichever of the alarm and the countdown got here first.
        if (announced == rest) return
        announced = rest

        notifier.post(
            AppNotification(
                kind = NotificationKind.REST_TIMER,
                title = context.getString(R.string.rest_notification_over),
                body = rest.nextUp(),
                silent = !RestAlertStyle.forRingerMode(context.ringerMode()).isAudible,
                deepLink = DeepLinks.activeSession(rest.sessionId),
            ),
        )
    }

    /** No rest at all: the note and the alarm both come down. */
    private suspend fun clear() {
        announced = null
        cancelAlarm()
        notifier.cancel(NotificationKind.REST_TIMER)
    }

    /**
     * Sets the alarm for [deadline], as exactly as this device will allow.
     *
     * `RTC_WAKEUP` against a wall-clock deadline rather than `ELAPSED_REALTIME_WAKEUP`, so that
     * the alarm and the draft are set from the *same number* — `RestTimerSnapshot` records why
     * a persisted deadline has to be wall clock, and computing a second elapsed-time deadline
     * here would be a second answer to when the rest ends.
     *
     * The exactness is checked rather than assumed. `canScheduleExactAlarms` is false for an
     * app whose exact-alarm permission the user has revoked, and calling the exact API anyway
     * throws `SecurityException` — so the fallback is an inexact wake-up that still pierces
     * Doze, which is late rather than absent. `runCatching` covers the rest: an alarm that
     * cannot be set is not worth taking a workout down for.
     */
    private fun schedule(
        rest: BackgroundRest,
        deadline: Long,
    ) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = expiryIntent(context, rest)

        runCatching {
            if (manager.canScheduleExactAlarmsCompat()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pending)
            }
        }
    }

    /**
     * Takes the alarm back, if one is set.
     *
     * `AlarmManager` matches the pending intent by action and component and **not** by its
     * extras, so an intent rebuilt here with no rest in it cancels whichever rest was
     * scheduled. That is why [expiryIntent] takes a nullable rest.
     */
    private fun cancelAlarm() {
        val pending = expiryIntent(context, rest = null)
        context.getSystemService(AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }

    /** "Bench press · set 3" — what the glance is for, or just the set where the name is gone. */
    private fun BackgroundRest.nextUp(): String =
        when (exerciseName) {
            null -> context.getString(R.string.rest_notification_next_set, setNumber)
            else -> context.getString(R.string.rest_notification_next_up, exerciseName, setNumber)
        }
}

/**
 * Whether this device will let the app set an exact alarm.
 *
 * True below Android 12, where exactness needs no permission. Above it the answer depends on a
 * permission the user can take away, and on Android 13 and up on which of the two exact-alarm
 * permissions the app holds — see the manifest, which records why this one asks for
 * `USE_EXACT_ALARM`.
 */
private fun AlarmManager.canScheduleExactAlarmsCompat(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms()

/**
 * The device's ringer switch, as the domain names it.
 *
 * An unreadable audio service reads as [RingerMode.NORMAL]: the alert is the point of the
 * feature, and a phone that cannot say whether it has been silenced has not been silenced.
 */
private fun Context.ringerMode(): RingerMode =
    when ((getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
        else -> RingerMode.NORMAL
    }

/**
 * The broadcast the alarm delivers when the rest runs out, carrying the rest with it.
 *
 * The extras are the whole of what survives: the alarm can fire at a process that has been dead
 * for the length of the rest, so what the notification says has to be written into the intent
 * rather than read off anything still in memory.
 *
 * [rest] is null when the intent is only being built to *cancel* the alarm — `AlarmManager`
 * compares action and component and ignores extras, so an empty one matches whatever is set.
 */
internal fun expiryIntent(
    context: Context,
    rest: BackgroundRest?,
): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        REST_EXPIRY_REQUEST_CODE,
        restExpiredBroadcast(context, rest),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/**
 * The broadcast itself, separated from the pending intent that carries it so both ends of the
 * round trip can be exercised without an `AlarmManager` in between — see [expiredRestOf].
 */
internal fun restExpiredBroadcast(
    context: Context,
    rest: BackgroundRest?,
): Intent =
    Intent(context, RestTimerReceiver::class.java)
        .setAction(ACTION_REST_EXPIRED)
        .apply {
            if (rest != null) {
                putExtra(EXTRA_SESSION_ID, rest.sessionId)
                putExtra(EXTRA_EXERCISE_NAME, rest.exerciseName)
                putExtra(EXTRA_SET_NUMBER, rest.setNumber)
            }
        }

/** The rest [intent] describes, already [RestTimerSnapshot.Finished], or null if it describes none. */
internal fun expiredRestOf(intent: Intent): BackgroundRest? {
    if (intent.action != ACTION_REST_EXPIRED) return null
    val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, 0L).takeIf { it > 0 } ?: return null
    val setNumber = intent.getIntExtra(EXTRA_SET_NUMBER, 0).takeIf { it > 0 } ?: return null

    return BackgroundRest(
        sessionId = sessionId,
        exerciseName = intent.getStringExtra(EXTRA_EXERCISE_NAME),
        setNumber = setNumber,
        rest = RestTimerSnapshot.Finished,
    )
}

private const val ACTION_REST_EXPIRED = "com.titan.core.data.notification.REST_EXPIRED"
private const val EXTRA_SESSION_ID = "session_id"
private const val EXTRA_EXERCISE_NAME = "exercise_name"
private const val EXTRA_SET_NUMBER = "set_number"

/**
 * One code, because there is one rest at a time — the same premise `InProgressSessionRepository`
 * is built on. `FLAG_UPDATE_CURRENT` is what refreshes the extras when a second rest reuses it.
 */
private const val REST_EXPIRY_REQUEST_CODE = 1
