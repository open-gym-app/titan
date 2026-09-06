package com.opengym.core.domain.notification

/**
 * Keeps the platform's alarms pointed at the user's [ReminderPreferences].
 *
 * ### Why this is an interface at all
 *
 * Because the settings ViewModel is what has just changed the schedule, and it is the wrong
 * place to import `AlarmManager` from. Everything on the other side of this — request codes,
 * `RTC_WAKEUP`, whether the device will honour an exact alarm — is `:core:data`'s, and a
 * feature module that could reach any of it would be a feature module that has to be tested
 * against a scheduler.
 *
 * ### One method, and it takes nothing
 *
 * [sync] re-reads the stored preferences rather than being handed them. Three callers set
 * reminders — the settings screen, the boot receiver, and the alarm itself as it re-arms for
 * next week — and only one of them has the preferences in hand. A `schedule(preferences)`
 * would make the other two read a store so they could pass its contents straight back, and
 * would let a caller schedule something that is not what is saved.
 *
 * It is also idempotent and total: it cancels every alarm it does not want, so calling it
 * after any change at all is correct, and there is no `cancel()` for somebody to forget.
 */
interface ReminderScheduler {

    /**
     * Points the alarms at the stored reminder, replacing whatever was pending.
     *
     * Nothing is scheduled when [NotificationKind.REMINDERS] is switched off or when no day is
     * chosen — see [ReminderPreferences.isSchedulable]. Waking a phone to post a notification
     * that `Notifier` would refuse is the one outcome with no value at all.
     */
    suspend fun sync()

    /**
     * Whether this device will let the app fire an alarm on the minute.
     *
     * False where the exact-alarm permission has been revoked, which leaves the reminder
     * scheduled and *late* rather than absent. Read by the settings screen, which says so on
     * the row — a reminder that arrives at 18:11 with no explanation reads as a broken app.
     *
     * A function rather than a value: the answer changes in system settings while the app is
     * not running, so there is no version of it that can be held and still be true.
     */
    fun isExact(): Boolean
}
