package com.titan.feature.settings.healthconnect

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titan.core.domain.healthconnect.HealthConnectAccess
import com.titan.core.domain.healthconnect.HealthConnectAvailability
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.PreferenceGroup
import com.titan.core.ui.component.PreferenceInfoRow
import com.titan.core.ui.component.PreferenceRow
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.settings.R
import kotlinx.coroutines.flow.filterNotNull

/**
 * The Health Connect screen, wired to its ViewModel and to the system permission dialog.
 *
 * The dialog's launcher and the intent into Health Connect's settings live here rather than in
 * [HealthConnectScreen], which stays a function of its state — the split every settings screen
 * with a system prompt behind it makes.
 */
@Composable
fun HealthConnectRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HealthConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        viewModel.onEvent(HealthConnectEvent.PermissionResult(it))
    }

    // Cleared before launching, so a recomposition while the dialog is up cannot launch it twice.
    LaunchedEffect(state.permissionRequest) {
        if (!state.permissionRequest) return@LaunchedEffect
        viewModel.onEvent(HealthConnectEvent.PermissionRequestLaunched)
        try {
            launcher.launch(state.permissions)
        } catch (_: ActivityNotFoundException) {
            viewModel.onEvent(HealthConnectEvent.PermissionRequestFailed)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onEvent(HealthConnectEvent.Resumed) }

    HealthConnectScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onManageAccess = { context.startHealthConnectSettings() },
        modifier = modifier,
    )
}

/**
 * The whole screen, stateless: it renders [state] and reports [HealthConnectEvent]s.
 *
 * Two layouts, split on whether anything is granted. Not connected, it is one row that starts the
 * conversation — or, after a refusal, the way to Health Connect's settings, since its dialog will
 * not come back. Connected, it says what is shared, offers the import, and the two ways out.
 *
 * @param onManageAccess opens Health Connect's own settings for this app. Hoisted for the reason
 *   the backup screen's pickers are: starting an activity is the route's business.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthConnectScreen(
    state: HealthConnectUiState,
    onEvent: (HealthConnectEvent) -> Unit,
    onBack: () -> Unit,
    onManageAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message?.text()

    // Marked shown before it is shown, keyed on Unit — see `SettingsScreen` for both halves.
    val currentMessage by rememberUpdatedState(message)
    LaunchedEffect(Unit) {
        snapshotFlow { currentMessage }
            .filterNotNull()
            .collect { text ->
                onEvent(HealthConnectEvent.MessageShown)
                snackbarHostState.showSnackbar(text)
            }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HealthConnectTopBar(onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(TitanTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
        ) {
            Text(
                text = stringResource(R.string.health_connect_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                state.availability != HealthConnectAvailability.AVAILABLE -> Text(
                    text = stringResource(R.string.health_connect_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )

                // The first answer has not arrived: draw nothing rather than a "Connect" that
                // flips to "connected" a frame later.
                state.granted == null -> Unit

                state.isConnected -> {
                    WorkoutsGroup(state = state, onEvent = onEvent)
                    AccessGroup(onEvent = onEvent, onManageAccess = onManageAccess)
                }

                else -> ConnectGroup(refused = state.refused, onEvent = onEvent, onManageAccess = onManageAccess)
            }
        }
    }

    if (state.rationaleOpen) {
        HealthConnectRationaleDialog(
            onContinue = { onEvent(HealthConnectEvent.RationaleAccepted) },
            onDismiss = { onEvent(HealthConnectEvent.RationaleDismissed) },
        )
    }
}

@Composable
private fun ConnectGroup(
    refused: Boolean,
    onEvent: (HealthConnectEvent) -> Unit,
    onManageAccess: () -> Unit,
) {
    PreferenceGroup(title = stringResource(R.string.health_connect_group_connection)) {
        if (refused) {
            PreferenceRow(
                label = stringResource(R.string.health_connect_refused),
                supporting = stringResource(R.string.health_connect_refused_detail),
                external = true,
                onClick = onManageAccess,
            )
        } else {
            PreferenceRow(
                label = stringResource(R.string.health_connect_connect),
                supporting = stringResource(R.string.health_connect_connect_detail),
                onClick = { onEvent(HealthConnectEvent.ConnectRequested) },
            )
        }
    }
}

/**
 * What is shared and what can be brought in, each saying what is missing when its half of the
 * grant is — a user can allow writing and not reading, and the row that cannot work says why
 * rather than disappearing.
 */
@Composable
private fun WorkoutsGroup(
    state: HealthConnectUiState,
    onEvent: (HealthConnectEvent) -> Unit,
) {
    PreferenceGroup(title = stringResource(R.string.health_connect_group_workouts)) {
        if (state.canWrite) {
            PreferenceInfoRow(
                label = stringResource(R.string.health_connect_write_on),
                supporting = stringResource(R.string.health_connect_write_on_detail),
            )
        } else {
            PreferenceInfoRow(
                label = stringResource(R.string.health_connect_write_off),
                supporting = stringResource(R.string.health_connect_write_off_detail),
            )
        }
        HairlineDivider()
        PreferenceRow(
            label = stringResource(R.string.health_connect_import),
            supporting = when {
                state.isImporting -> stringResource(R.string.health_connect_importing)
                state.canRead -> stringResource(R.string.health_connect_import_detail)
                else -> stringResource(R.string.health_connect_import_blocked_detail)
            },
            enabled = state.canRead && !state.isImporting,
            onClick = { onEvent(HealthConnectEvent.ImportRequested) },
        )
    }
}

@Composable
private fun AccessGroup(
    onEvent: (HealthConnectEvent) -> Unit,
    onManageAccess: () -> Unit,
) {
    PreferenceGroup(title = stringResource(R.string.health_connect_group_connection)) {
        PreferenceRow(
            label = stringResource(R.string.health_connect_manage),
            supporting = stringResource(R.string.health_connect_manage_detail),
            external = true,
            onClick = onManageAccess,
        )
        HairlineDivider()
        PreferenceRow(
            label = stringResource(R.string.health_connect_disconnect),
            supporting = stringResource(R.string.health_connect_disconnect_detail),
            destructive = true,
            onClick = { onEvent(HealthConnectEvent.DisconnectRequested) },
        )
    }
}

/**
 * What Titan will read and write, said before Health Connect's own dialog asks — see
 * [HealthConnectViewModel] for why it always comes first.
 */
@Composable
internal fun HealthConnectRationaleDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.health_connect_rationale_title)) },
        text = {
            Text(
                text = stringResource(R.string.health_connect_rationale_body),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(text = stringResource(R.string.health_connect_rationale_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.health_connect_rationale_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthConnectTopBar(onBack: () -> Unit) {
    Column {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.settings_health_connect),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun HealthConnectMessage.text(): String =
    when (this) {
        is HealthConnectMessage.Imported -> if (count == 0) {
            stringResource(R.string.health_connect_imported_none)
        } else {
            pluralStringResource(R.plurals.health_connect_imported, count, count)
        }

        HealthConnectMessage.ImportFailed -> stringResource(R.string.health_connect_import_failed)

        HealthConnectMessage.DisconnectFailed -> stringResource(R.string.health_connect_disconnect_failed)

        HealthConnectMessage.RequestFailed -> stringResource(R.string.health_connect_request_failed)
    }

/**
 * Health Connect's page for this app's permissions, or nothing if it cannot be opened.
 *
 * Nothing rather than a crash: the row that calls this is only drawn while Health Connect is
 * available, so the one way to get here without it is an uninstall between the draw and the tap.
 */
private fun Context.startHealthConnectSettings() {
    try {
        startActivity(HealthConnectClient.getHealthConnectManageDataIntent(this))
    } catch (_: ActivityNotFoundException) {
        // Gone between the draw and the tap; the resume that follows redraws without the row.
    }
}

// --- previews -------------------------------------------------------------------------

@Preview(name = "Health Connect - not connected", showBackground = true)
@Composable
fun HealthConnectNotConnectedPreview() {
    TitanTheme {
        HealthConnectScreen(
            state = HealthConnectUiState(granted = emptySet()),
            onEvent = {},
            onBack = {},
            onManageAccess = {},
        )
    }
}

@Preview(name = "Health Connect - connected", showBackground = true)
@Composable
fun HealthConnectConnectedPreview() {
    TitanTheme {
        HealthConnectScreen(
            state = HealthConnectUiState(granted = HealthConnectAccess.entries.toSet()),
            onEvent = {},
            onBack = {},
            onManageAccess = {},
        )
    }
}

@Preview(name = "Health Connect - write only, dark", showBackground = true)
@Composable
fun HealthConnectWriteOnlyPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        HealthConnectScreen(
            state = HealthConnectUiState(granted = setOf(HealthConnectAccess.WRITE)),
            onEvent = {},
            onBack = {},
            onManageAccess = {},
        )
    }
}

@Preview(name = "Health Connect - refused", showBackground = true)
@Composable
fun HealthConnectRefusedPreview() {
    TitanTheme {
        HealthConnectScreen(
            state = HealthConnectUiState(granted = emptySet(), refused = true),
            onEvent = {},
            onBack = {},
            onManageAccess = {},
        )
    }
}

@Preview(name = "Health Connect - twice the font", showBackground = true, heightDp = 1400, fontScale = 2f)
@Composable
fun HealthConnectLargeFontPreview() {
    TitanTheme {
        HealthConnectScreen(
            state = HealthConnectUiState(granted = HealthConnectAccess.entries.toSet()),
            onEvent = {},
            onBack = {},
            onManageAccess = {},
        )
    }
}

@Preview(name = "Health Connect - rationale", showBackground = true)
@Composable
fun HealthConnectRationalePreview() {
    TitanTheme {
        HealthConnectRationaleDialog(onContinue = {}, onDismiss = {})
    }
}
