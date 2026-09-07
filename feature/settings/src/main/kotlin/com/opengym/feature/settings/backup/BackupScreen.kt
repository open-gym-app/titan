package com.opengym.feature.settings.backup

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.backup.model.ImportError
import com.opengym.core.domain.model.DataFootprint
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.component.StatTile
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.settings.R
import com.opengym.feature.settings.backup.component.RestoreConfirmationDialog

/**
 * Backup and restore, wired to its ViewModel and to the two system pickers it needs.
 *
 * @param appVersion the running build's version name, stamped into the export's envelope for
 *   diagnostics. It comes from `:app` down through navigation, as the About row's does: a
 *   feature module's own `BuildConfig` carries the *library's* version.
 */
@Composable
fun BackupRoute(
    appVersion: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Filtered to JSON, which is what the exporter writes. A cancelled picker calls back with
    // null and sends no event at all — which is what leaves the database untouched, and is
    // the whole of what "a cancelled picker changes nothing" needs.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onEvent(BackupEvent.RestoreFileChosen(uri.toString()))
    }

    // Handed the file the moment an export completes, then acknowledged so a rotation cannot
    // open a second sheet. `FileProvider` rather than a `file://` URI: passing one of those to
    // another app has thrown `FileUriExposedException` since Android 7.
    LaunchedEffect(state.share) {
        val fileName = state.share ?: return@LaunchedEffect
        context.shareBackup(fileName)
        viewModel.onEvent(BackupEvent.ShareHandled)
    }

    BackupScreen(
        state = state,
        appVersion = appVersion,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onPickFile = { picker.launch(arrayOf(BACKUP_MIME_TYPE)) },
        modifier = modifier,
    )
}

/**
 * The whole screen, stateless: it renders [state] and reports [BackupEvent]s.
 *
 * Three parts, top to bottom, in the order the wireframe puts them and the order a user needs
 * them: what is on the device, how to take a copy of it, and how to put one back. The
 * footprint card is above both actions because it is the sentence both of them are about —
 * "184 sessions, 2,140 sets" is what is being exported and what a restore would replace.
 *
 * @param onPickFile opens the system document picker. Hoisted out of the screen because
 *   launching an `ActivityResultContract` needs a launcher remembered in a composition that
 *   is *not* under test in a screen test — the route owns it, and a test drives the same
 *   button by passing its own lambda.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    state: BackupUiState,
    appVersion: String,
    onEvent: (BackupEvent) -> Unit,
    onBack: () -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { BackupTopBar(onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(OpenGymTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
        ) {
            FootprintCard(state.footprint)
            ExportSection(state = state, appVersion = appVersion, onEvent = onEvent)
            RestoreSection(state = state, onEvent = onEvent, onPickFile = onPickFile)
        }
    }

    if (state.pendingRestoreUri != null || state.phase.isRestoring) {
        RestoreConfirmationDialog(
            footprint = state.footprint,
            isRestoring = state.phase.isRestoring,
            onConfirm = { onEvent(BackupEvent.RestoreConfirmed) },
            onDismiss = { onEvent(BackupEvent.RestoreDismissed) },
        )
    }
}

/**
 * What is on the device: two counts, when it started, and roughly how much space it takes.
 *
 * Two tiles rather than six. Templates, records, custom exercises and body measurements are in
 * the confirmation dialog's list instead — a card of six tiles is a dashboard, and this is a
 * caption for the two buttons under it. Two also happens to be what fits: `numericLarge` at a
 * third of the measure truncates a five-glyph figure, and `2,1…` sets is worse than no tile.
 */
@Composable
private fun FootprintCard(footprint: DataFootprint) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
        SectionHeading(text = stringResource(R.string.backup_footprint_title))

        Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
            StatTile(
                label = stringResource(R.string.backup_stat_sessions),
                value = footprint.sessions.grouped(),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.backup_stat_sets),
                value = footprint.sets.grouped(),
                modifier = Modifier.weight(1f),
            )
        }

        // The platform's own formatter for the size, so `2.4 MB` follows the device's locale
        // and the same unit convention the system storage screen uses.
        val size = stringResource(
            R.string.backup_footprint_size,
            Formatter.formatShortFileSize(context, footprint.databaseBytes),
        )

        Text(
            text = footprint.trainingSince() + SUMMARY_SEPARATOR + size,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportSection(
    state: BackupUiState,
    appVersion: String,
    onEvent: (BackupEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
        SectionHeading(text = stringResource(R.string.backup_export_title))
        Text(
            text = stringResource(R.string.backup_export_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { onEvent(BackupEvent.ExportRequested(appVersion)) },
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.backup_export_action))
        }

        val exporting = state.phase as? BackupPhase.Exporting
        if (exporting != null) {
            ProgressRow(
                label = stringResource(R.string.backup_exporting),
                completed = exporting.rowsWritten,
                total = exporting.rowsTotal,
            )
        }

        when (val outcome = state.lastExport) {
            null -> Unit

            is ExportOutcome.Succeeded -> OutcomeCard(
                text = stringResource(R.string.backup_export_done, outcome.fileName, outcome.rowsWritten),
                onDismiss = { onEvent(BackupEvent.OutcomeDismissed) },
            )

            ExportOutcome.Failed -> OutcomeCard(
                text = stringResource(R.string.backup_export_failed),
                isError = true,
                onDismiss = { onEvent(BackupEvent.OutcomeDismissed) },
            )
        }
    }
}

@Composable
private fun RestoreSection(
    state: BackupUiState,
    onEvent: (BackupEvent) -> Unit,
    onPickFile: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
        SectionHeading(text = stringResource(R.string.backup_restore_title))
        Text(
            text = stringResource(R.string.backup_restore_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = onPickFile,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.backup_restore_action))
        }

        when (val phase = state.phase) {
            BackupPhase.Validating -> ProgressRow(label = stringResource(R.string.backup_validating))

            is BackupPhase.Importing -> ProgressRow(
                label = stringResource(R.string.backup_importing),
                completed = phase.rowsWritten,
                total = phase.rowsTotal,
            )

            else -> Unit
        }

        when (val outcome = state.lastRestore) {
            null -> Unit

            is RestoreOutcome.Succeeded -> OutcomeCard(
                text = stringResource(R.string.backup_restore_done, outcome.rowsRestored),
                onDismiss = { onEvent(BackupEvent.OutcomeDismissed) },
            )

            is RestoreOutcome.Failed -> OutcomeCard(
                text = outcome.error.message(),
                isError = true,
                onDismiss = { onEvent(BackupEvent.OutcomeDismissed) },
            )
        }
    }
}

/**
 * A determinate bar and the count under it, or an indeterminate one while the total is not yet
 * known.
 *
 * Determinate is the point — both use cases count their rows before writing any, precisely so
 * this can be a bar rather than a spinner on a 50,000-set history. The indeterminate form is
 * for the validation step alone, which is over in well under a second.
 *
 * The whole row speaks as one node: a progress bar and a pair of numbers read separately are
 * three announcements for one fact.
 */
@Composable
private fun ProgressRow(
    label: String,
    completed: Long = 0,
    total: Long = 0,
) {
    val spoken = if (total > 0) "$label ${percentOf(completed, total)}%" else label

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (total > 0) {
            LinearProgressIndicator(
                progress = { completed.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The sentence left behind by the last export or restore, with a way to put it away.
 *
 * A card that stays rather than a snackbar that goes: a successful export names a file the
 * user may want to find again, and a failed restore has to keep saying which failure it was
 * and that the data is intact. Four seconds is not long enough for either.
 */
@Composable
private fun OutcomeCard(
    text: String,
    onDismiss: () -> Unit,
    isError: Boolean = false,
) {
    OpenGymCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(
                start = OpenGymTheme.spacing.card,
                top = OpenGymTheme.spacing.sm,
                bottom = OpenGymTheme.spacing.sm,
                end = OpenGymTheme.spacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.backup_dismiss))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupTopBar(onBack: () -> Unit) {
    Column {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.settings_backup),
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

// --- platform edges -------------------------------------------------------------------

/** Whether the phase is one the restore confirmation should stay up and inert for. */
private val BackupPhase.isRestoring: Boolean
    get() = this is BackupPhase.Validating || this is BackupPhase.Importing

private const val SUMMARY_SEPARATOR = " · "

// --- previews -------------------------------------------------------------------------

private const val PREVIEW_APP_VERSION = "1.0"

/**
 * Tall enough to capture the screen whole.
 *
 * The outcome cards sit under the restore section, which is already past the fold on a phone,
 * so a preview at the device height would record a reference that says nothing about the state
 * it is named for. The dialog previews keep the default height: a dialog is centred and the
 * page behind it is not what they are about.
 */
private const val FULL_SCREEN_HEIGHT_DP = 1000

private val SAMPLE_FOOTPRINT = DataFootprint(
    sessions = 184,
    sets = 2_140,
    templates = 6,
    personalRecords = 23,
    customExercises = 2,
    bodyMeasurements = 61,
    firstSessionAt = 1_711_929_600_000,
    databaseBytes = 2_411_724,
)

@Preview(name = "Backup - idle", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenPreview() {
    OpenGymTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(footprint = SAMPLE_FOOTPRINT),
            onEvent = {},
            onBack = {},
            onPickFile = {},
        )
    }
}

@Preview(name = "Backup - nothing logged", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenEmptyPreview() {
    OpenGymTheme {
        BackupScreen(
            state = BackupUiState(),
            appVersion = PREVIEW_APP_VERSION,
            onEvent = {},
            onBack = {},
            onPickFile = {},
        )
    }
}

@Preview(name = "Backup - exporting", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenExportingPreview() {
    OpenGymTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                phase = BackupPhase.Exporting(rowsWritten = 31_400, rowsTotal = 52_180),
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
        )
    }
}

@Preview(name = "Backup - exported", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenExportedPreview() {
    OpenGymTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                lastExport = ExportOutcome.Succeeded(
                    fileName = "opengym-backup-2026-09-06-1432.json",
                    rowsWritten = 52_180,
                ),
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
        )
    }
}

@Preview(name = "Backup - restore confirmation", showBackground = true)
@Composable
fun BackupScreenRestoreConfirmationPreview() {
    OpenGymTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                pendingRestoreUri = "content://downloads/opengym-backup.json",
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
        )
    }
}

@Preview(name = "Backup - restoring", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenRestoringPreview() {
    OpenGymTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                phase = BackupPhase.Importing(rowsWritten = 12_000, rowsTotal = 52_180),
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
        )
    }
}

@Preview(name = "Backup - restored", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenRestoredPreview() {
    OpenGymTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                lastRestore = RestoreOutcome.Succeeded(rowsRestored = 52_180),
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
        )
    }
}

@Preview(name = "Backup - unsupported version", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenUnsupportedVersionPreview() {
    OpenGymTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                lastRestore = RestoreOutcome.Failed(
                    ImportError.UnsupportedSchemaVersion(found = 2, supported = 1),
                ),
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
        )
    }
}

@Preview(name = "Backup - malformed file", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenMalformedPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                lastRestore = RestoreOutcome.Failed(ImportError.MalformedJson("Unexpected EOF at 4211")),
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
        )
    }
}
