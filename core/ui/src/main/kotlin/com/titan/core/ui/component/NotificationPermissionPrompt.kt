package com.titan.core.ui.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * The system's notification permission prompt, raised when a ViewModel asks for it.
 *
 * Shared because more than one feature raises it — the settings switches, the reminder screen
 * and the first rest of a workout — and each copy of the launcher would be one more place to
 * forget the ordering below. Whether to raise it at all is
 * `RequestNotificationPermissionUseCase`'s answer; this is only the half that needs an
 * Activity, which is why it lives in a route rather than in a stateless screen.
 *
 * The request is acknowledged **before** the prompt goes up: `launch` does not suspend until
 * the user answers, and a flag left set would raise a second prompt on the next recomposition.
 *
 * @param request the ViewModel wants the prompt raised. Read as a one-shot: [onRequestShown]
 *   clears it.
 * @param onRequestShown the prompt has been raised.
 * @param onAnswered the user answered or dismissed it. Which answer is not passed on: the one
 *   worth having is the system's, which a caller re-reads rather than trusting the prompt's.
 */
@Composable
fun NotificationPermissionPrompt(
    request: Boolean,
    onRequestShown: () -> Unit,
    onAnswered: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { onAnswered() },
    )

    LaunchedEffect(request) {
        if (!request) return@LaunchedEffect
        onRequestShown()
        launcher.launch(POST_NOTIFICATIONS)
    }
}

/**
 * The permission the prompt asks for. The constant exists on every version; only *holding* it is
 * Android 13+, and below that the system answers the request without showing anything.
 */
private const val POST_NOTIFICATIONS = android.Manifest.permission.POST_NOTIFICATIONS
