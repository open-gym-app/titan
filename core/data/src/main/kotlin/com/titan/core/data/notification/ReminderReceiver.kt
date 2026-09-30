package com.titan.core.data.notification

import android.content.Context
import android.content.Intent
import com.titan.core.data.R
import com.titan.core.domain.gamification.StreakCalculator
import com.titan.core.domain.model.StreakState
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.navigation.DeepLinks
import com.titan.core.domain.notification.AppNotification
import com.titan.core.domain.notification.NotificationAction
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationTarget
import com.titan.core.domain.notification.Notifier
import com.titan.core.domain.notification.ReminderScheduler
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.time.Clock
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A scheduled reminder falling due.
 *
 * Plumbing only — the handling is [ReminderBroadcasts], reached through an entry point for the
 * reason `RestTimerReceiver` and `IdleSessionReceiver` reach theirs that way: a class the
 * system instantiates by name is the one shape a test cannot construct with its own doubles,
 * and everything worth testing is on the other side of it.
 *
 * [SuspendingBroadcastReceiver] for the `goAsync` the other two need and this one needs more of:
 * it reads three stores and then re-arms seven alarms, on a process the system may have started
 * from cold to deliver the broadcast.
 */
class ReminderReceiver : SuspendingBroadcastReceiver() {

    override suspend fun handle(
        context: Context,
        intent: Intent,
    ) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, ReminderEntryPoint::class.java)
            .reminderBroadcasts()
            .handle(intent)
    }
}

/** How [ReminderReceiver] reaches the graph from inside `onReceive`. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ReminderEntryPoint {
    fun reminderBroadcasts(): ReminderBroadcasts
}

/**
 * What [ReminderReceiver] does: say it is time to train, and set next week's alarm.
 *
 * ### Everything the notification says is read now
 *
 * The alarm was set up to a week ago and carries nothing but the day it is for. In that week
 * the user may have started a workout, finished one, or let a streak lapse — so the session and
 * the streak are read here rather than baked into the intent. It is the same rule the idle
 * nudge follows and for the same reason: a notification about a state that has moved on is
 * worse than none.
 *
 * ### A workout already in progress changes what the reminder *is*
 *
 * Not just where it goes. Somebody who is mid-session does not need telling to train; what
 * they may need is the way back into the workout they left open, so the notification says that
 * instead and its link is the active session rather than the setup flow. This is the
 * acceptance criterion that a reminder tapped during a session opens `ActiveSessionScreen`,
 * and it is answered at *post* time because that is where the honest answer is.
 *
 * A session started in the seconds between posting and tapping still lands on the setup screen,
 * which is not a bug worth chasing: that screen already offers to pick the running workout back
 * up — see `NewSessionUiState.runningSessionId` — so the user is one tap from the same place.
 *
 * ### Re-arming is a full sync
 *
 * [ReminderScheduler.sync] rather than "set this day again next week": every day is re-read
 * from the store and re-armed, so a schedule changed while the app was not running is picked up
 * by the next reminder that fires, and a re-arm missed once does not stop the feature forever.
 * It also costs nothing — six of the seven alarms are set to the value they already hold.
 */
@Singleton
class ReminderBroadcasts @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionRepository: SessionRepository,
    private val gamificationRepository: GamificationRepository,
    private val notifier: Notifier,
    private val scheduler: ReminderScheduler,
    private val clock: Clock,
) {

    suspend fun handle(intent: Intent) {
        // A broadcast this build does not understand — one left over from an upgrade, or an
        // intent that reached the receiver by some other route. Nothing to say and, more
        // importantly, nothing to re-arm from.
        reminderDayOf(intent) ?: return

        // Wrapped, and the re-arm below is outside it. The notification is what this wake-up
        // exists for, but it is also the half that reads two stores and talks to the platform,
        // and a refusal from any of them must not cost next week's alarm: an unguarded throw
        // here would skip the sync *and* escape into the receiver's own coroutine, where there
        // is nothing to catch it.
        runCatching { notifyDue() }

        // After the notification, not before: the user's reminder arriving is the thing this
        // wake-up exists for, and a store that refuses to be re-read should not be able to
        // swallow it. `sync` reads the preferences again itself, so a failure here leaves the
        // schedule as it was rather than half-applied.
        runCatching { scheduler.sync() }
    }

    private suspend fun notifyDue() {
        val active = sessionRepository.getActive().first()
        val deepLink = when (active) {
            null -> DeepLinks.NEW_SESSION
            else -> DeepLinks.activeSession(active.id)
        }

        notifier.post(
            AppNotification(
                kind = NotificationKind.REMINDERS,
                title = when (active) {
                    null -> context.getString(R.string.reminder_title)
                    else -> context.getString(R.string.reminder_title_in_progress)
                },
                body = body(active),
                // Not ongoing: this is an invitation, and one the user is allowed to decline by
                // swiping it away. A note they cannot dismiss is a note that gets the channel
                // muted instead, which costs every future reminder.
                deepLink = deepLink,
                actions = listOf(
                    NotificationAction(
                        label = when (active) {
                            null -> context.getString(R.string.reminder_action_start)
                            else -> context.getString(R.string.reminder_action_resume)
                        },
                        target = NotificationTarget.Screen(deepLink),
                    ),
                ),
            ),
        )
    }

    /**
     * The second line: the workout still open, or the streak this session would keep alive.
     *
     * The streak is the strongest thing the app can truthfully say at 18:00 on a Monday, and
     * it is said only while it is **live** — [StreakCalculator.isLive] is the same check the
     * home screen makes, and without it a reminder would tell somebody who last trained in
     * March that they are twelve days deep. A user with no live streak gets the plain
     * invitation, which is also what a fresh install gets.
     */
    private suspend fun body(active: WorkoutSession?): String {
        if (active != null) {
            return active.name ?: context.getString(R.string.reminder_body_in_progress)
        }

        val streak = gamificationRepository.observeStreak().first()
        return when {
            streak.isLive() -> context.resources.getQuantityString(
                R.plurals.reminder_body_streak,
                streak.currentStreak,
                streak.currentStreak,
            )

            else -> context.getString(R.string.reminder_body)
        }
    }

    /** Whether this streak is still standing as of now, rather than as of when it was counted. */
    private fun StreakState.isLive(): Boolean =
        currentStreak > 0 && StreakCalculator.isLive(lastCountedAt, Instant.ofEpochMilli(clock.nowMillis()))
}
