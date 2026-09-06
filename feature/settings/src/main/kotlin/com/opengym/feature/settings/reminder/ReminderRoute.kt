package com.opengym.feature.settings.reminder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.feature.settings.component.NotificationPermissionEffects
import com.opengym.feature.settings.component.startAppNotificationSettings

/**
 * The reminder screen, wired to its ViewModel.
 *
 * Everything platform-shaped is here rather than in [ReminderScreen], which stays a function
 * of its state — see [NotificationPermissionEffects], which the settings list uses for the
 * same three effects.
 */
@Composable
fun ReminderRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReminderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    NotificationPermissionEffects(
        request = state.permissionRequest,
        onRequestShown = { viewModel.onEvent(ReminderEvent.PermissionRequestShown) },
        onRecheck = { viewModel.onEvent(ReminderEvent.PermissionRechecked) },
    )

    ReminderScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onOpenNotificationSettings = { context.startAppNotificationSettings() },
        modifier = modifier,
    )
}
