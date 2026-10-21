package com.titan.core.data.notification

import android.content.Context
import android.content.Intent
import com.titan.core.domain.notification.ReminderScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The four events that leave the alarm table wrong: a reboot, an update, and the two ways the
 * wall clock moves under a standing appointment.
 *
 * `AlarmManager` holds nothing across a restart or an app update. A phone restarted on Sunday
 * night has no Monday reminder left, and neither does one that took an app update on Sunday
 * afternoon — and in both cases nothing tells the user, because from their side the setting is
 * still switched on. This receiver is the only thing standing between that and a feature that
 * quietly stops working for anybody who restarts their phone.
 *
 * The other two are the reason a wall-clock appointment is not an instant. An alarm is set in
 * epoch millis, which is an absolute moment; "18:00 on Monday" is not. A lifter who flies to
 * Lisbon has a Monday alarm still pointed at 18:00 Paris, which is 17:00 where they are now,
 * and a user who corrects a phone's clock by an hour has moved every one of the seven. Neither
 * changes what is *stored*, so re-running [ReminderScheduler.sync] against the new zone is the
 * whole repair.
 *
 * All four are handled by one receiver because they are one event as far as this app is
 * concerned: what is scheduled no longer matches what is stored, and [ReminderScheduler.sync]
 * puts back exactly what the stored preferences ask for. `sync` cancels what it does not want,
 * so running it against an alarm table that already happens to be right is a no-op rather than
 * a duplicate.
 *
 * ### Only the reminders come back
 *
 * The rest timer's alarm and the idle nudge are deliberately not re-armed here. Both describe
 * something the app was *in the middle of*, and a reboot ends it: a rest that was counting down
 * an hour ago is over, and a nudge about a session left open will be re-armed by the session
 * screen the next time it is looked at. A reminder is the only one of the three that is an
 * appointment rather than a continuation, and an appointment survives a restart.
 *
 * ### Not direct-boot aware
 *
 * `BOOT_COMPLETED` arrives after the user unlocks, which is what this wants: the preferences
 * are in credential-encrypted storage and are unreadable before then. A direct-boot-aware
 * receiver would run earlier and find nothing to schedule from.
 */
class BootReceiver : SuspendingBroadcastReceiver() {

    /**
     * `goAsync` through [SuspendingBroadcastReceiver], because scheduling reads the preference
     * store — and this runs during a boot, where the system is least inclined to let a process
     * linger past `onReceive`.
     */
    override suspend fun handle(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in HANDLED_ACTIONS) return

        val scheduler = EntryPointAccessors
            .fromApplication(context.applicationContext, ReminderSchedulerEntryPoint::class.java)
            .reminderScheduler()

        // Wrapped rather than allowed to throw: an unreadable store at boot is not worth a
        // crash dialog on a phone the user has just turned on, and the next change in settings
        // re-syncs anyway.
        runCatching { scheduler.sync() }
    }

    private companion object {

        /**
         * A restart, an update of this app, and the two ways the wall clock moves.
         *
         * `MY_PACKAGE_REPLACED` rather than `PACKAGE_REPLACED`: the first is delivered only to
         * the app that was replaced and needs no permission, while the second is every package
         * on the device and would have this receiver woken by unrelated updates all day.
         *
         * `ACTION_TIMEZONE_CHANGED` and `ACTION_TIME_CHANGED` are both exempt from the
         * background limits on implicit broadcasts, so a manifest receiver still hears them.
         * They matter for the reason the class note gives: the alarms hold an absolute instant
         * and the schedule is a local time, so the two only agree until the zone moves.
         *
         * Checked rather than trusted from the manifest filter, because a receiver declared in
         * the manifest is one anything holding the permission can be made to invoke, and
         * re-arming on an intent this app did not mean is a wake-up nobody asked for.
         */
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
        )
    }
}

/** How [BootReceiver] reaches the graph from inside `onReceive`. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ReminderSchedulerEntryPoint {
    fun reminderScheduler(): ReminderScheduler
}
