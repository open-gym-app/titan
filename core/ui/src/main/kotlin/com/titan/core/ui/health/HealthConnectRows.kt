package com.titan.core.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.health.connect.client.PermissionController
import com.titan.core.domain.model.HealthAvailability
import com.titan.core.domain.model.HealthConnectStatus
import com.titan.core.domain.model.HealthPermissions
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.R
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.PreferenceGroup
import com.titan.core.ui.component.PreferenceRow
import com.titan.core.ui.component.PreferenceSwitchRow
import com.titan.core.ui.theme.TitanTheme

/**
 * The Health Connect switch, and under it the import row once sync is on.
 *
 * Draws nothing where Health Connect is not available: a switch that can never be turned on is
 * not a setting, and the placeholder this replaces was only ever honest because the feature
 * was coming. [dividerAfter] is the caller's because the rows sit inside a group whose other
 * rows the caller owns — and a divider with nothing drawn before it would be a stray line.
 */
@Composable
fun HealthConnectRows(
    state: HealthConnectUiState,
    onEvent: (HealthConnectEvent) -> Unit,
    modifier: Modifier = Modifier,
    dividerAfter: Boolean = false,
) {
    if (!state.visible) return

    PreferenceSwitchRow(
        label = stringResource(R.string.health_connect_label),
        checked = state.syncOn,
        onCheckedChange = { onEvent(HealthConnectEvent.SyncToggled(it)) },
        supporting = syncSupporting(state),
        modifier = modifier,
    )
    if (state.syncOn) {
        HairlineDivider()
        PreferenceRow(
            label = stringResource(R.string.health_connect_import_label),
            supporting = importSupporting(state),
            enabled = !state.importing,
            onClick = { onEvent(HealthConnectEvent.ImportTapped) },
        )
    }
    if (dividerAfter) HairlineDivider()

    if (state.rationaleVisible) {
        AlertDialog(
            onDismissRequest = { onEvent(HealthConnectEvent.RationaleDismissed) },
            title = { Text(stringResource(R.string.health_connect_rationale_title)) },
            text = { Text(stringResource(R.string.health_connect_rationale_body)) },
            confirmButton = {
                TextButton(onClick = { onEvent(HealthConnectEvent.RationaleConfirmed) }) {
                    Text(stringResource(R.string.health_connect_rationale_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(HealthConnectEvent.RationaleDismissed) }) {
                    Text(stringResource(R.string.health_connect_rationale_not_now))
                }
            },
        )
    }
}

/** The rows in a titled group of their own, for a screen with no other settings to put them among. */
@Composable
fun HealthConnectGroup(
    title: String,
    state: HealthConnectUiState,
    onEvent: (HealthConnectEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    PreferenceGroup(title = title, modifier = modifier) {
        HealthConnectRows(state = state, onEvent = onEvent)
    }
}

/**
 * Raises the system permission prompt when the state asks for one, and reports what came back.
 *
 * The result is only ever read as "did we get what we need", by asking Health Connect again —
 * the contract's own result is the set it granted, which says nothing about permissions held
 * from an earlier grant.
 */
@Composable
fun HealthConnectPermissionEffect(
    state: HealthConnectUiState,
    onEvent: (HealthConnectEvent) -> Unit,
) {
    val currentOnEvent by rememberUpdatedState(onEvent)
    val launcher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { currentOnEvent(HealthConnectEvent.PermissionResult) }

    LaunchedEffect(state.launchPermission) {
        if (state.launchPermission) {
            currentOnEvent(HealthConnectEvent.PermissionRequestLaunched)
            launcher.launch(HealthPermissions.REQUIRED)
        }
    }
}

@Composable
private fun syncSupporting(state: HealthConnectUiState): String =
    when (state.message) {
        HealthConnectMessage.PermissionDenied -> stringResource(R.string.health_connect_denied)

        HealthConnectMessage.Unreachable -> stringResource(R.string.health_connect_unreachable)

        else -> stringResource(
            if (state.syncOn) R.string.health_connect_detail_on else R.string.health_connect_detail_off,
        )
    }

@Composable
private fun importSupporting(state: HealthConnectUiState): String {
    val message = state.message
    return when {
        state.importing -> stringResource(R.string.health_connect_importing)

        message is HealthConnectMessage.Imported && message.count > 0 ->
            pluralStringResource(R.plurals.health_connect_imported, message.count, message.count)

        message is HealthConnectMessage.Imported -> stringResource(R.string.health_connect_nothing_new)

        else -> stringResource(R.string.health_connect_import_detail)
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private const val PREVIEW_IMPORTED = 3

private fun previewState(
    enabled: Boolean,
    permitted: Boolean,
    message: HealthConnectMessage? = null,
) = HealthConnectUiState(
    status = HealthConnectStatus(HealthAvailability.AVAILABLE, enabled = enabled, permitted = permitted),
    message = message,
)

@Preview(name = "Health Connect - off", showBackground = true, widthDp = 360)
@Composable
fun HealthConnectRowsOffPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        HealthConnectGroup(title = "Health Connect", state = previewState(false, false), onEvent = {})
    }
}

@Preview(name = "Health Connect - on", showBackground = true, widthDp = 360)
@Composable
fun HealthConnectRowsOnPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        HealthConnectGroup(
            title = "Health Connect",
            state = previewState(true, true, HealthConnectMessage.Imported(PREVIEW_IMPORTED)),
            onEvent = {},
        )
    }
}

@Preview(name = "Health Connect - denied, dark", showBackground = true, widthDp = 360)
@Composable
fun HealthConnectRowsDeniedDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        HealthConnectGroup(
            title = "Health Connect",
            state = previewState(false, false, HealthConnectMessage.PermissionDenied),
            onEvent = {},
        )
    }
}
