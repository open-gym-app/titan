package com.titan.feature.settings.component

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.titan.core.ui.component.NotificationPermissionPrompt

/*
 * Everything platform-shaped about the notification permission, in one place because two
 * screens in this module do it.
 *
 * The settings list turns notifications on from four switches; the reminder screen turns the
 * same one on from its own. Both then have to raise a system prompt, and both have to notice
 * when the user comes back from the system settings having changed the answer. Neither of
 * those can live in a stateless screen — only something attached to an Activity can raise a
 * prompt or start an intent — so they live in the route, and the route's copy of them lives
 * here rather than in two.
 */

/**
 * The permission prompt, and the two things that can change the answer.
 *
 * Two effects that only make sense together:
 *
 * - the prompt itself, raised when the ViewModel asks for it through [request] — see
 *   [NotificationPermissionPrompt], which the active workout raises it through too;
 * - the resume, which is the only signal that a user who left for the system settings has come
 *   back. There is no callback for it.
 *
 * The prompt's own result is deliberately ignored. Granted or refused, the answer worth having
 * is the system's — the user may have answered, dismissed, or turned something off in the
 * settings behind it — so [onRecheck] fires and the ViewModel goes and asks.
 *
 * @param request the ViewModel wants the prompt raised. Read as a one-shot: [onRequestShown]
 *   clears it.
 * @param onRequestShown the prompt has been raised.
 * @param onRecheck something happened that could have changed what the system allows.
 */
@Composable
internal fun NotificationPermissionEffects(
    request: Boolean,
    onRequestShown: () -> Unit,
    onRecheck: () -> Unit,
) {
    NotificationPermissionPrompt(request = request, onRequestShown = onRequestShown, onAnswered = onRecheck)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRecheck() }
}

/**
 * Opens Android's own notification settings for this app — the screen where a refused
 * permission can still be granted.
 *
 * `ACTION_APP_NOTIFICATION_SETTINGS` rather than the app's details page, so the user lands on
 * the channels themselves rather than one level up from them. `NEW_TASK` because the launch is
 * from an application context in the general case, and starting an activity without it there
 * throws.
 *
 * Not where an exact alarm is granted — that is [startExactAlarmSettings], on a different page
 * of the system settings. The channel screen carries no alarm toggle, and a row about a late
 * reminder that opens it is a row that answers a question the user did not ask.
 *
 * Falls back to the app's details page, and then to nothing, because neither screen is
 * guaranteed to exist: a build without the notification settings activity — a stripped AOSP
 * image, a TV or Wear device — resolves nothing, and an unguarded `startActivity` there turns
 * a row the user tapped for help into an `ActivityNotFoundException` that takes the app down.
 */
internal fun Context.startAppNotificationSettings() {
    val channels = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (startActivityOrNothing(channels)) return

    startActivityOrNothing(appDetailsSettings())
}

/**
 * Opens the system page that carries this app's exact-alarm switch.
 *
 * A second destination rather than a reuse of [startAppNotificationSettings], because they are
 * two permissions on two screens: whether a notification is delivered, and whether an alarm may
 * wake the device on the minute. The reminder screen says both out loud and each row has to
 * lead to the page that can actually change the answer.
 *
 * `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` exists from Android 12, which is also the first version
 * that can withhold the permission — below it `AndroidReminderScheduler.isExact` is always true
 * and the row that leads here is never drawn.
 *
 * Falls back to the app's details page, which lists "Alarms & reminders" one tap in, and then
 * to nothing, for the reason the notification one does.
 */
internal fun Context.startExactAlarmSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val exactAlarms = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (startActivityOrNothing(exactAlarms)) return
    }

    startActivityOrNothing(appDetailsSettings())
}

/** The app's own page in system settings, which is the last stop for either permission. */
private fun Context.appDetailsSettings(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** @return whether something opened. Nothing resolved is a dead row, not a crash. */
private fun Context.startActivityOrNothing(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
