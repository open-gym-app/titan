package com.opengym.core.data.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.opengym.core.data.R
import com.opengym.core.domain.notification.AppNotification
import com.opengym.core.domain.notification.NotificationAction
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.notification.NotificationOutcome
import com.opengym.core.domain.notification.NotificationPermission
import com.opengym.core.domain.notification.NotificationTarget
import com.opengym.core.domain.notification.Notifier
import com.opengym.core.domain.repository.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts through `NotificationManagerCompat`, after asking twice whether it should.
 *
 * ### Both gates are here, and only here
 *
 * A notification needs a preference the user set and a permission the system granted, and
 * either one missing means nothing is posted. Checking them at the call site instead would be
 * four copies of the same two `if`s — one per consumer — and the fourth copy is where one of
 * them gets forgotten. Concentrating them here is the reason [Notifier] exists as an
 * abstraction at all rather than as a helper function.
 *
 * The order is preference first: it is the user's own decision, it costs a store read either
 * way, and reporting "you turned this off" is more useful than "Android is blocking us" when
 * both are true.
 *
 * ### One id per kind
 *
 * The id is derived from the kind rather than handed in, so re-posting the same kind replaces
 * what is showing instead of stacking a second copy beside it — the behaviour all four
 * consumers want, and one a caller allocating its own ids gets wrong by picking `1` twice.
 * `tag` is how a caller that genuinely needs two at once (several scheduled reminders) says
 * so, and it keeps the two apart without either of them needing to know the other's number.
 *
 * Nothing here requests `POST_NOTIFICATIONS`. Asking is the settings screen's job, at the
 * moment the user turns a notification on — a prompt raised with no context attached is the
 * one most reliably denied, and a denial is sticky. This end only ever checks.
 */
@Singleton
class AndroidNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val permission: NotificationPermission,
) : Notifier {

    override suspend fun post(notification: AppNotification): NotificationOutcome {
        val preferences = userPreferencesRepository.observe().first().notifications
        if (notification.kind !in preferences) return NotificationOutcome.SUPPRESSED_BY_PREFERENCE
        if (!permission.areNotificationsAllowed()) return NotificationOutcome.SUPPRESSED_BY_PERMISSION

        return try {
            NotificationManagerCompat.from(context)
                .notify(notification.tag, notification.kind.notificationId, build(notification))
            NotificationOutcome.POSTED
        } catch (_: SecurityException) {
            // The check above is a read of a value that can change between the two lines: a
            // user revoking the permission from the notification shade while the app is
            // posting is unlikely and entirely possible. The same answer either way — nothing
            // was delivered — and it is not worth a crash for a notification.
            //
            // It also happens to be what Android lint asks for at this call, which cannot see
            // that the guard above is a permission check because the check is behind
            // `NotificationPermission`. That is the right place for it, and this is the right
            // handling for the race, so neither moves for the other.
            NotificationOutcome.SUPPRESSED_BY_PERMISSION
        }
    }

    override suspend fun cancel(
        kind: NotificationKind,
        tag: String?,
    ) {
        NotificationManagerCompat.from(context).cancel(tag, kind.notificationId)
    }

    private fun build(notification: AppNotification): Notification =
        NotificationCompat.Builder(context, notification.kind.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            // The collapsed line truncates at roughly forty characters; expanding is what
            // makes a body that says something useful readable at all.
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setCategory(notification.kind.category)
            .setOngoing(notification.ongoing)
            // Silence is per-notification and the channel's loudness is per-kind, which is what
            // lets one countdown be re-posted quietly on a channel whose whole job is to be
            // heard across a gym. `setOnlyAlertOnce` would not do: it silences everything after
            // the *first* post on an id, which is the wrong one — the first post is the quiet
            // update and the last is the announcement.
            .setSilent(notification.silent)
            .apply {
                notification.deepLink?.let { setContentIntent(context.open(it)) }
                notification.countdownTo?.let { deadline ->
                    // Ticked by the system, from `when` down to zero. The platform redraws it
                    // in place; nothing here wakes up to do it. See `AppNotification`.
                    setWhen(deadline)
                    setShowWhen(true)
                    setUsesChronometer(true)
                    setChronometerCountDown(true)
                }
                notification.actions.forEach { addAction(NO_ACTION_ICON, it.label, context.press(it)) }
            }
            // An ongoing note is cleared by the app when the thing it describes is over, so
            // dismissing it on a tap would leave the session running with nothing showing.
            .setAutoCancel(!notification.ongoing)
            .build()
}

/**
 * A tap on the notification: the deep link, as an intent the launcher activity answers.
 *
 * Implicit — an `ACTION_VIEW` on the URI, restricted to this app's own package — rather than
 * naming `MainActivity`, which lives in `:app` and is a class this module may not see. The
 * manifest's `opengym://` filter is what resolves it, which is the same filter that makes the
 * link work from anywhere else, so there is one route in rather than two.
 *
 * `FLAG_UPDATE_CURRENT` because the extras are what differ between two links to the same
 * screen, and extras are not part of the equality `PendingIntent` matches on: without it, the
 * second session's notification would quietly reuse the first's target.
 */
private fun Context.open(deepLink: String): PendingIntent =
    PendingIntent.getActivity(
        this,
        DEEP_LINK_REQUEST_CODE,
        Intent(Intent.ACTION_VIEW, deepLink.toUri()).setPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/**
 * A press on one of the notification's buttons.
 *
 * The two targets are two different sorts of pending intent, and the distinction is the
 * platform's: only an activity may bring the app to the front from the background, and only a
 * broadcast may do something *without* doing that. A "skip rest" or a "finish now" pressed on a
 * lock screen must be the second sort — dragging the user into a screen they did not ask for is
 * the opposite of what the button is for.
 *
 * `FLAG_IMMUTABLE`, as everything here is: nothing outside this app has any business editing
 * the intent, and on Android 12 and up a mutable one without a declared reason is refused
 * outright.
 */
private fun Context.press(action: NotificationAction): PendingIntent =
    when (val target = action.target) {
        is NotificationTarget.Screen -> open(target.deepLink)

        is NotificationTarget.Command -> PendingIntent.getBroadcast(
            this,
            NotificationCommands.requestCode(target.command),
            NotificationCommands.intent(this, target.command),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

/**
 * The one request code every deep link shares.
 *
 * Safe because of `FLAG_UPDATE_CURRENT` above: the codes only have to keep *simultaneously
 * live* pending intents apart, and this app posts one notification per kind with one link on
 * it. A code per kind would be four constants standing for a distinction nothing makes.
 */
private const val DEEP_LINK_REQUEST_CODE = 0

/**
 * `addAction`'s icon slot, left empty.
 *
 * Android has not drawn action icons since API 24 — the buttons are text — and passing a real
 * drawable would ship an asset nothing renders. Zero is the documented "no icon".
 */
private const val NO_ACTION_ICON = 0

/**
 * The system id this kind's notification occupies — one slot each, so a re-post replaces.
 *
 * Explicit numbers rather than `ordinal`, for the reason the channel ids are explicit: they
 * are live references to something already on a user's screen, and reordering the enum would
 * silently make a new post land on an old notification's slot.
 */
internal val NotificationKind.notificationId: Int
    get() = when (this) {
        NotificationKind.REMINDERS -> 1
        NotificationKind.REST_TIMER -> 2
        NotificationKind.SESSION -> 3
        NotificationKind.PROGRESSION -> 4
    }

/**
 * What the system should treat this as when it is deciding what may interrupt.
 *
 * `CATEGORY_ALARM` for the rest timer is the load-bearing one: it is the end of a countdown
 * the user started, and it has to reach them under the same rules an alarm does rather than
 * being held back with the rest of the noise.
 */
internal val NotificationKind.category: String
    get() = when (this) {
        NotificationKind.REMINDERS -> NotificationCompat.CATEGORY_REMINDER
        NotificationKind.REST_TIMER -> NotificationCompat.CATEGORY_ALARM
        NotificationKind.SESSION -> NotificationCompat.CATEGORY_REMINDER
        NotificationKind.PROGRESSION -> NotificationCompat.CATEGORY_STATUS
    }
