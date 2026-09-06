package com.opengym.core.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opengym.core.domain.notification.ReminderScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The two events that silently empty an app's alarm table: a reboot, and an update.
 *
 * `AlarmManager` holds nothing across either. A phone restarted on Sunday night has no Monday
 * reminder left, and neither does one that took an app update on Sunday afternoon — and in both
 * cases nothing tells the user, because from their side the setting is still switched on. This
 * receiver is the only thing standing between that and a feature that quietly stops working
 * for anybody who restarts their phone.
 *
 * Both actions are handled by one receiver because they are one event as far as this app is
 * concerned: whatever was scheduled is gone, and [ReminderScheduler.sync] puts back exactly
 * what the stored preferences ask for. `sync` cancels what it does not want, so running it
 * against an alarm table that already happens to be right is a no-op rather than a duplicate.
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
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in HANDLED_ACTIONS) return

        val scheduler = EntryPointAccessors
            .fromApplication(context.applicationContext, ReminderSchedulerEntryPoint::class.java)
            .reminderScheduler()

        // `goAsync`, because scheduling reads the preference store — and this runs during a
        // boot, where the system is least inclined to let a process linger past `onReceive`.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                // Wrapped rather than allowed to throw: an unreadable store at boot is not
                // worth a crash dialog on a phone the user has just turned on, and the next
                // change in settings re-syncs anyway.
                runCatching { scheduler.sync() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {

        /**
         * A restart, and an update of this app.
         *
         * `MY_PACKAGE_REPLACED` rather than `PACKAGE_REPLACED`: the first is delivered only to
         * the app that was replaced and needs no permission, while the second is every package
         * on the device and would have this receiver woken by unrelated updates all day.
         *
         * Checked rather than trusted from the manifest filter, because a receiver declared in
         * the manifest is one anything holding the permission can be made to invoke, and
         * re-arming on an intent this app did not mean is a wake-up nobody asked for.
         */
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}

/** How [BootReceiver] reaches the graph from inside `onReceive`. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ReminderSchedulerEntryPoint {
    fun reminderScheduler(): ReminderScheduler
}
