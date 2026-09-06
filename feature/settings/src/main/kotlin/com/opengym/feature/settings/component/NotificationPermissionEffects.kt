package com.opengym.feature.settings.component

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

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
 * Three effects that only make sense together:
 *
 * - the launcher, which is the only thing that can put the system prompt on screen;
 * - the request, raised when the ViewModel asks for it through [request] and acknowledged
 *   **before** the prompt goes up — `launch` does not suspend until the user answers, and
 *   leaving the flag set would raise a second prompt on the next recomposition;
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
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { onRecheck() },
    )

    LaunchedEffect(request) {
        if (!request) return@LaunchedEffect
        onRequestShown()
        launcher.launch(POST_NOTIFICATIONS)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRecheck() }
}

/**
 * Opens Android's own notification settings for this app — the screen where a refused
 * permission can still be granted, and where an exact-alarm one is reached from.
 *
 * `ACTION_APP_NOTIFICATION_SETTINGS` rather than the app's details page, so the user lands on
 * the channels themselves rather than one level up from them. `NEW_TASK` because the launch is
 * from an application context in the general case, and starting an activity without it there
 * throws.
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

    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivityOrNothing(details)
}

/** @return whether something opened. Nothing resolved is a dead row, not a crash. */
private fun Context.startActivityOrNothing(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

/**
 * The permission the switches are backed by, named here because this module is the only place
 * the app asks for it.
 *
 * A literal rather than `Manifest.permission.POST_NOTIFICATIONS` would be the mistake; the
 * constant exists on every version, and only *holding* it is Android 13+.
 */
private const val POST_NOTIFICATIONS = android.Manifest.permission.POST_NOTIFICATIONS
