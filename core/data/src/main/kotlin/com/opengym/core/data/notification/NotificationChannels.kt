package com.opengym.core.data.notification

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.opengym.core.data.R
import com.opengym.core.domain.notification.NotificationKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's notification channels, and the only place they are created.
 *
 * ### One channel per kind
 *
 * Four channels rather than one, because a channel is the *only* place Android lets a user
 * say how loud a kind of notification may be. Behind a single channel, silencing a loot drop
 * silences the rest timer too — and the rest timer is the one notification somebody is
 * standing in a gym waiting for. The split is what makes "buzz for this, stay quiet for that"
 * expressible at all, and it has to exist before the first notification is posted: a channel's
 * importance is fixed at creation and the system ignores every later attempt to raise it.
 *
 * | Kind | Importance | Why |
 * | --- | --- | --- |
 * | [NotificationKind.REMINDERS] | Default | A nudge. Worth a sound, not worth interrupting. |
 * | [NotificationKind.REST_TIMER] | High, with sound and vibration | The phone is in a pocket. |
 * | [NotificationKind.SESSION] | Low, silent | A standing note, not an event. |
 * | [NotificationKind.PROGRESSION] | Default | Good news; it can wait for the phone to be picked up. |
 *
 * ### Registered at every start, not once
 *
 * [register] runs on every launch from `OpenGymApplication`. Creating a channel that already
 * exists updates its name and description and leaves everything the *user* has changed — the
 * importance, the sound, whether it vibrates — exactly as they left it. So this is idempotent
 * by design, and running it unconditionally is what keeps a channel added in a later version
 * from needing a "have I done this yet" flag.
 *
 * ### The ids are a persisted format
 *
 * A channel id is how the system recognises the channel the user has already configured.
 * Renaming one abandons their settings and silently creates a second, default-importance
 * channel beside the one they tuned — so the ids are literals in an exhaustive `when`, never
 * derived from an enum name a refactor would happily change.
 */
@Singleton
class NotificationChannels @Inject constructor(@param:ApplicationContext private val context: Context) {

    /** Creates or updates every channel. Safe to call on each launch; see the class note. */
    fun register() {
        NotificationManagerCompat.from(context)
            .createNotificationChannelsCompat(NotificationKind.entries.map(::channelFor))
    }

    private fun channelFor(kind: NotificationKind): NotificationChannelCompat =
        NotificationChannelCompat.Builder(kind.channelId, kind.importance)
            .setName(context.getString(kind.nameRes))
            .setDescription(context.getString(kind.descriptionRes))
            .setVibrationEnabled(kind == NotificationKind.REST_TIMER)
            .apply {
                // Every channel is given a sound explicitly, including the one that is given
                // none: a channel left alone takes the device's default notification tone
                // whatever its importance, so "silent" has to be said rather than implied by
                // being quiet.
                when (kind) {
                    NotificationKind.REST_TIMER -> setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder()
                            // `USAGE_ALARM`, not `USAGE_NOTIFICATION`: this is the end of a
                            // countdown the user started, and it has to be audible under the
                            // same rules an alarm is — including through a silenced ringer.
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )

                    // A standing note about a session that is still open. It is on screen for
                    // as long as the session runs, and a sound would be an interruption
                    // announcing that nothing has happened.
                    NotificationKind.SESSION -> setSound(null, null)

                    NotificationKind.REMINDERS, NotificationKind.PROGRESSION -> Unit
                }
            }
            .build()
}

/**
 * The system channel this kind is posted to.
 *
 * Here rather than on the enum, because `:core:domain` has no business knowing what a channel
 * is — the same rule that keeps `ImageVector` out of it. See the class note above on why these
 * are literals.
 */
internal val NotificationKind.channelId: String
    get() = when (this) {
        NotificationKind.REMINDERS -> "reminders"
        NotificationKind.REST_TIMER -> "rest_timer"
        NotificationKind.SESSION -> "session"
        NotificationKind.PROGRESSION -> "progression"
    }

/** How hard this kind is allowed to interrupt. See the table on [NotificationChannels]. */
internal val NotificationKind.importance: Int
    get() = when (this) {
        NotificationKind.REMINDERS -> NotificationManagerCompat.IMPORTANCE_DEFAULT
        NotificationKind.REST_TIMER -> NotificationManagerCompat.IMPORTANCE_HIGH
        NotificationKind.SESSION -> NotificationManagerCompat.IMPORTANCE_LOW
        NotificationKind.PROGRESSION -> NotificationManagerCompat.IMPORTANCE_DEFAULT
    }

/** What the channel is called in system settings — the user's name for it, so a real string. */
internal val NotificationKind.nameRes: Int
    get() = when (this) {
        NotificationKind.REMINDERS -> R.string.notification_channel_reminders
        NotificationKind.REST_TIMER -> R.string.notification_channel_rest_timer
        NotificationKind.SESSION -> R.string.notification_channel_session
        NotificationKind.PROGRESSION -> R.string.notification_channel_progression
    }

/** The line under the name in system settings, saying what turning it off would cost. */
internal val NotificationKind.descriptionRes: Int
    get() = when (this) {
        NotificationKind.REMINDERS -> R.string.notification_channel_reminders_description
        NotificationKind.REST_TIMER -> R.string.notification_channel_rest_timer_description
        NotificationKind.SESSION -> R.string.notification_channel_session_description
        NotificationKind.PROGRESSION -> R.string.notification_channel_progression_description
    }
