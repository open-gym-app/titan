package com.titan.core.data.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.ReminderScheduler
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.time.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The workout reminder, as up to seven standing alarms.
 *
 * ### `AlarmManager`, not `WorkManager`
 *
 * A reminder is an appointment: "Monday at 18:00". `WorkManager` guarantees that a job
 * eventually runs, not that it runs *then* — and a nudge to train that arrives at 19:40
 * because Doze batched it is worse than no nudge at all, because the user is home. #181's
 * weekly backup is the opposite case, does not care when in the day it runs, and uses
 * `WorkManager` for exactly that reason. The two are deliberately different and neither should
 * be changed to match the other.
 *
 * ### One alarm per weekday, not one repeating alarm
 *
 * `setRepeating` is inexact above API 19 and repeats on a fixed interval, which a weekday
 * schedule is not: Monday–Wednesday–Friday is two gaps of two days and one of three. Seven
 * separate wall-clock alarms — each pointed at its own next occurrence, each with its own
 * request code — are what let the set be any subset of the week, and what let clearing
 * Wednesday leave Friday alone.
 *
 * Each one re-arms itself for the following week when it fires; see [ReminderBroadcasts],
 * which calls [sync] rather than scheduling one alarm, so a missed re-arm is repaired by the
 * next reminder that does fire rather than stopping the feature forever.
 *
 * ### The exactness is checked, and its absence is reported
 *
 * `setExactAndAllowWhileIdle` is the only API that fires on the minute through Doze. It needs
 * a permission that Android 14 does not grant by default — the manifest records which of the
 * two spellings this app asks for and why — and calling it without one throws. So [isExact] is
 * asked first, the fallback is `setAndAllowWhileIdle`, and the settings screen reads [isExact]
 * to say that delivery may be delayed.
 *
 * The fallback is `setAndAllowWhileIdle` rather than the `setWindowExactAndAllowWhileIdle` the
 * issue asked for, because there is no such API: `setWindow` has no idle-piercing variant, and
 * a windowed alarm that Doze holds until the next maintenance window is hours late rather than
 * the fifteen minutes that was wanted. `setAndAllowWhileIdle` is the one inexact call that
 * still wakes the device, and the platform's own Doze batching is what bounds how late it is.
 */
@Singleton
class AndroidReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val clock: Clock,
) : ReminderScheduler {

    override suspend fun sync() {
        val preferences = userPreferencesRepository.observe().first()
        val wanted = when {
            // The switch in settings, which is the same gate `Notifier` would apply at the far
            // end. Checked here as well so a phone is not woken for a notification that would
            // then be dropped — the one combination with no value at all.
            NotificationKind.REMINDERS !in preferences.notifications -> emptyMap()

            else -> preferences.reminders.nextOccurrences(clock.nowMillis(), ZoneId.systemDefault())
        }

        // Asked once for the whole pass rather than once inside each of seven `schedule`
        // calls: it is a binder call into the system, and the revocation that could land
        // halfway through is the same race `schedule` already catches.
        val exact = isExact()

        // Every day is visited, not only the wanted ones: a day the user has just cleared is
        // a day with an alarm still standing, and this is where it comes down. That is also
        // what makes `sync` safe to call after any change at all — there is nothing to cancel
        // separately and so nothing to forget.
        DayOfWeek.entries.forEach { day ->
            when (val at = wanted[day]) {
                null -> cancel(day)
                else -> schedule(day, at, exact)
            }
        }
    }

    override fun isExact(): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    /**
     * Sets [day]'s alarm for [at], as exactly as this device will allow.
     *
     * `RTC_WAKEUP` because the deadline is a moment on the wall clock rather than a delay:
     * an elapsed-time alarm set eight hours out is eight hours out however the clock is
     * corrected in the meantime, where this one tracks `System.currentTimeMillis`.
     *
     * It does *not* follow the time zone — [at] is an absolute instant, and 18:00 Paris stays
     * 17:00 in Lisbon. `BootReceiver` is what re-arms the week when the zone moves; nothing
     * `AlarmManager` offers would do it here.
     *
     * `runCatching` around the call itself for the race the check cannot close — the user
     * revoking the exact-alarm permission between [isExact] and here — and for the OEM that
     * refuses a wake-up outright. A reminder that could not be set is not worth taking the
     * settings screen down for.
     *
     * @param exact what [isExact] answered for this pass. Passed in rather than asked again per
     *   day, which would be seven binder calls for one answer.
     */
    private fun schedule(
        day: DayOfWeek,
        at: Long,
        exact: Boolean,
    ) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = reminderIntent(context, day)

        runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }

    /**
     * Takes [day]'s alarm back, and the pending intent with it.
     *
     * Both halves matter. Cancelling the alarm stops it firing; cancelling the [PendingIntent]
     * is what makes `dumpsys alarm` stop listing this app — and it is what the acceptance
     * criterion for turning the reminder off is actually checking.
     */
    private fun cancel(day: DayOfWeek) {
        val pending = reminderIntent(context, day)
        context.getSystemService(AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }
}

/**
 * The broadcast [day]'s alarm delivers.
 *
 * Unlike the rest timer's and the idle nudge's, this one carries nothing: what the reminder
 * *says* — whether a workout is already running, and how long the streak is — is read when it
 * fires rather than written into an intent up to a week earlier, by which time every one of
 * those answers may have changed.
 *
 * The day is still on the intent, as an extra and in the request code. The request code is
 * what keeps seven simultaneously live alarms apart, since `AlarmManager` matches a pending
 * intent by request code, action and component and ignores extras; the extra is there so a
 * receiver, a test or an `adb shell dumpsys alarm` can say which day it is looking at.
 */
internal fun reminderIntent(
    context: Context,
    day: DayOfWeek,
): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        REMINDER_REQUEST_CODE_BASE + day.value,
        reminderBroadcast(context, day),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** The broadcast itself, so both ends of the round trip can be exercised with no alarm between. */
internal fun reminderBroadcast(
    context: Context,
    day: DayOfWeek,
): Intent =
    Intent(context, ReminderReceiver::class.java)
        .setAction(ACTION_REMINDER_DUE)
        .putExtra(EXTRA_DAY, day.value)

/** Whether [intent] is a reminder falling due, and which day's if it is. */
internal fun reminderDayOf(intent: Intent): DayOfWeek? {
    if (intent.action != ACTION_REMINDER_DUE) return null
    return intent.getIntExtra(EXTRA_DAY, 0)
        .takeIf { it in DayOfWeek.MONDAY.value..DayOfWeek.SUNDAY.value }
        ?.let(DayOfWeek::of)
}

private const val ACTION_REMINDER_DUE = "com.titan.core.data.notification.REMINDER_DUE"
private const val EXTRA_DAY = "iso_day_of_week"

/**
 * Where the reminders' request codes start.
 *
 * `DayOfWeek.value` is 1–7, so this reserves 11–17 and leaves the three single codes the other
 * notification alarms hold — the deep link's 0, the rest timer's 1 and the idle nudge's 2 —
 * untouched, with room between for whatever takes the next one.
 */
private const val REMINDER_REQUEST_CODE_BASE = 10
