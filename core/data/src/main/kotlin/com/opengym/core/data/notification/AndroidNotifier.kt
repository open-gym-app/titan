package com.opengym.core.data.notification

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.opengym.core.data.R
import com.opengym.core.domain.notification.AppNotification
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.notification.NotificationOutcome
import com.opengym.core.domain.notification.NotificationPermission
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
            // An ongoing note is cleared by the app when the thing it describes is over, so
            // dismissing it on a tap would leave the session running with nothing showing.
            .setAutoCancel(!notification.ongoing)
            .build()
}

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
        NotificationKind.SESSION -> NotificationCompat.CATEGORY_PROGRESS
        NotificationKind.PROGRESSION -> NotificationCompat.CATEGORY_STATUS
    }
