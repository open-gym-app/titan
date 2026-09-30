package com.titan.feature.settings.backup

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titan.core.domain.backup.model.ImportError
import com.titan.core.domain.model.DataFootprint
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.component.StatTile
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.format.formatCount
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.settings.R
import com.titan.feature.settings.backup.component.AutoBackupSection
import com.titan.feature.settings.backup.component.RestoreConfirmationDialog
import com.titan.core.ui.R as CoreR

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

    // Filtered to [RESTORE_MIME_TYPES] rather than to the one type the exporter writes, because
    // a backup that has been off the device comes back declaring something else. A cancelled
    // picker calls back with null and sends no event at all — which is what leaves the database
    // untouched, and is the whole of what "a cancelled picker changes nothing" needs.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onEvent(BackupEvent.RestoreFileChosen(uri.toString()))
    }

    // Where a copy of the finished export goes. `CreateDocument` is the *only* way an app can
    // put a file somewhere the user can find it: `Android/data/…`, where the export is written,
    // is off limits to file managers on Android 11 and up, and no permission exists that would
    // change that — `WRITE_EXTERNAL_STORAGE` has done nothing since Android 10. The picker
    // seeds the name field with the export's, so the file keeps its timestamp.
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
        if (uri != null) viewModel.onEvent(BackupEvent.SaveDestinationChosen(uri.toString()))
    }

    BackupScreen(
        state = state,
        appVersion = appVersion,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onPickFile = { picker.launch(RESTORE_MIME_TYPES) },
        onSaveCopy = saver::launch,
        // Straight to the platform, with no round trip through the ViewModel: a share sheet
        // leaves no state behind, changes nothing, and the file it needs is already named on
        // the screen. `FileProvider` rather than a `file://` URI, which has thrown
        // `FileUriExposedException` since Android 7 — see `shareBackup`.
        onShare = context::shareBackup,
        modifier = modifier,
    )
}

/**
 * The whole screen, stateless: it renders [state] and reports [BackupEvent]s.
 *
 * Four parts, top to bottom, in the order the wireframe puts them and the order a user needs
 * them: what is on the device, how to take a copy of it, how to have copies taken for you, and
 * how to put one back. The footprint card is above the actions because it is the sentence they
 * are all about — "184 sessions, 2,140 sets" is what is being exported and what a restore would
 * replace — and the restore is last because it is the only control here that destroys anything.
 *
 * @param onPickFile opens the system document picker. Hoisted out of the screen because
 *   launching an `ActivityResultContract` needs a launcher remembered in a composition that
 *   is *not* under test in a screen test — the route owns it, and a test drives the same
 *   button by passing its own lambda.
 * @param onSaveCopy opens the "create document" picker, seeded with the export's file name, so
 *   the user can put a copy somewhere they can actually reach. Hoisted for [onPickFile]'s
 *   reason; the copy itself is the ViewModel's, reported back as [BackupUiState.lastSave].
 * @param onShare hands the named export to the system share sheet. Hoisted rather than sent as
 *   an event because it is pure platform: nothing about the app changes when a sheet opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    state: BackupUiState,
    appVersion: String,
    onEvent: (BackupEvent) -> Unit,
    onBack: () -> Unit,
    onPickFile: () -> Unit,
    onSaveCopy: (String) -> Unit,
    onShare: (String) -> Unit,
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
                .padding(TitanTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
        ) {
            FootprintCard(state.footprint)
            ExportSection(
                state = state,
                appVersion = appVersion,
                onEvent = onEvent,
                onSaveCopy = onSaveCopy,
                onShare = onShare,
            )
            AutoBackupSection(
                schedule = state.autoBackup,
                saveFailed = state.autoBackupSaveFailed,
                onToggle = { enabled -> onEvent(BackupEvent.AutoBackupToggled(enabled)) },
            )
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

    Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
        SectionHeading(text = stringResource(R.string.backup_footprint_title))

        Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
            StatTile(
                label = stringResource(R.string.backup_stat_sessions),
                value = formatCount(footprint.sessions),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.backup_stat_sets),
                value = formatCount(footprint.sets),
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

/**
 * The export button, what it is doing, and what came of it.
 *
 * The outcome of a successful export is where the two things a user can *do* with the file
 * live, because until it exists there is nothing to do them to. "Save a copy" is first and
 * named first in the description: the file the export writes is in app-specific storage that
 * no file manager can browse, so a user who never presses it has a backup they cannot reach.
 */
@Composable
private fun ExportSection(
    state: BackupUiState,
    appVersion: String,
    onEvent: (BackupEvent) -> Unit,
    onSaveCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
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

        when (val phase = state.phase) {
            is BackupPhase.Exporting -> ProgressRow(
                label = stringResource(R.string.backup_exporting),
                completed = phase.rowsWritten,
                total = phase.rowsTotal,
            )

            // Indeterminate, and honestly so: a copy has no row count of its own, and the
            // bytes it moves are not the number the export just finished reporting.
            BackupPhase.Saving -> ProgressRow(label = stringResource(R.string.backup_saving))

            else -> Unit
        }

        when (val outcome = state.lastExport) {
            null -> Unit

            is ExportOutcome.Succeeded -> ExportedCard(
                text = stringResource(R.string.backup_export_done, outcome.fileName, outcome.rowsWritten),
                isBusy = state.isBusy,
                onSaveCopy = { onSaveCopy(outcome.fileName) },
                onShare = { onShare(outcome.fileName) },
                onDismiss = { onEvent(BackupEvent.OutcomeDismissed) },
            )

            ExportOutcome.Failed -> OutcomeCard(
                text = stringResource(R.string.backup_export_failed),
                isError = true,
                onDismiss = { onEvent(BackupEvent.OutcomeDismissed) },
            )
        }

        when (state.lastSave) {
            null -> Unit

            SaveOutcome.Succeeded -> OutcomeCard(
                text = stringResource(R.string.backup_save_done),
                onDismiss = { onEvent(BackupEvent.OutcomeDismissed) },
            )

            SaveOutcome.Failed -> OutcomeCard(
                text = stringResource(R.string.backup_save_failed),
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
    Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
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
 *
 * And it speaks *as the run goes*: the row is a polite live region, which is the only way a
 * value that changes without a tap reaches a screen reader at all. Ten times a run, not once a
 * page — see [progressPercent] for the cadence and why.
 */
@Composable
private fun ProgressRow(
    label: String,
    completed: Long = 0,
    total: Long = 0,
) {
    val spoken = if (total > 0) {
        stringResource(CoreR.string.figure_with_unit, label, progressPercent(completed, total))
    } else {
        label
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
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
 * The successful export: what it wrote, and the two things that can be done with it.
 *
 * Its own composable rather than an `actions` slot on [OutcomeCard], because it is a different
 * shape and not a variant of one. Every other outcome is a sentence with a way to dismiss it,
 * and fits on one line beside its button; this one is a sentence over a row of three, and
 * folding both into one function would be a conditional layout pretending to be a parameter.
 *
 * All three are text buttons, of equal weight, in the order they are wanted. "Save a copy" is
 * not emphasised into a filled button even though it is the one that matters most: three
 * controls at three weights inside a card that is itself a footnote is more hierarchy than a
 * card deserves, and a filled button next to `Share` would read as a commitment rather than a
 * choice between two ways of keeping the file.
 *
 * @param isBusy whether the copy is already running — the buttons go inert while it is, since
 *   a second destination picked mid-write would have two copies reading one file.
 */
@Composable
private fun ExportedCard(
    text: String,
    isBusy: Boolean,
    onSaveCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    TitanCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(
                start = TitanTheme.spacing.card,
                top = TitanTheme.spacing.sm,
                bottom = TitanTheme.spacing.sm,
                end = TitanTheme.spacing.sm,
            ),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSaveCopy, enabled = !isBusy) {
                    Text(text = stringResource(R.string.backup_save_copy_action))
                }
                TextButton(onClick = onShare, enabled = !isBusy) {
                    Text(text = stringResource(R.string.backup_share_action))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.backup_dismiss))
                }
            }
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
    TitanCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(
                start = TitanTheme.spacing.card,
                top = TitanTheme.spacing.sm,
                bottom = TitanTheme.spacing.sm,
                end = TitanTheme.spacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
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
    TitanTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(footprint = SAMPLE_FOOTPRINT),
            onEvent = {},
            onBack = {},
            onPickFile = {},
            onSaveCopy = {},
            onShare = {},
        )
    }
}

/**
 * Every label at twice its size — the bar #40 holds every screen to, on the pattern Home set.
 *
 * Recorded rather than argued about: the reference image is the evidence, it is a screenshot
 * test like any other, and a change that starts clipping or overlapping something at 2x fails
 * the build. Robolectric's font metrics are not a phone's, so the device walk in
 * docs/ACCESSIBILITY.md is still the result; this is what keeps it from rotting.
 */
@Preview(name = "Backup - twice the font", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP * 2, fontScale = 2f)
@Composable
fun BackupScreenLargeFontPreview() {
    TitanTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(footprint = SAMPLE_FOOTPRINT),
            onEvent = {},
            onBack = {},
            onPickFile = {},
            onSaveCopy = {},
            onShare = {},
        )
    }
}

@Preview(name = "Backup - nothing logged", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenEmptyPreview() {
    TitanTheme {
        BackupScreen(
            state = BackupUiState(),
            appVersion = PREVIEW_APP_VERSION,
            onEvent = {},
            onBack = {},
            onPickFile = {},
            onSaveCopy = {},
            onShare = {},
        )
    }
}

@Preview(name = "Backup - exporting", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenExportingPreview() {
    TitanTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                phase = BackupPhase.Exporting(rowsWritten = 31_400, rowsTotal = 52_180),
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
            onSaveCopy = {},
            onShare = {},
        )
    }
}

@Preview(name = "Backup - exported", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenExportedPreview() {
    TitanTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                lastExport = ExportOutcome.Succeeded(
                    fileName = "titan-backup-2026-09-06-1432.json",
                    rowsWritten = 52_180,
                ),
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
            onSaveCopy = {},
            onShare = {},
        )
    }
}

/**
 * The copy in flight: an indeterminate bar, and the card's own buttons inert behind it.
 *
 * Worth a reference of its own because it is the one state where a card that is *finished*
 * has to look unavailable — a user who taps "Save a copy" twice would otherwise have two
 * writes reading one file.
 */
@Preview(name = "Backup - saving a copy", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenSavingPreview() {
    TitanTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                phase = BackupPhase.Saving,
                lastExport = ExportOutcome.Succeeded(
                    fileName = "titan-backup-2026-09-06-1432.json",
                    rowsWritten = 52_180,
                ),
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
            onSaveCopy = {},
            onShare = {},
        )
    }
}

@Preview(name = "Backup - restore confirmation", showBackground = true)
@Composable
fun BackupScreenRestoreConfirmationPreview() {
    TitanTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                pendingRestoreUri = "content://downloads/titan-backup.json",
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
            onSaveCopy = {},
            onShare = {},
        )
    }
}

@Preview(name = "Backup - restoring", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenRestoringPreview() {
    TitanTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                phase = BackupPhase.Importing(rowsWritten = 12_000, rowsTotal = 52_180),
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
            onSaveCopy = {},
            onShare = {},
        )
    }
}

@Preview(name = "Backup - restored", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenRestoredPreview() {
    TitanTheme {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                lastRestore = RestoreOutcome.Succeeded(rowsRestored = 52_180),
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
            onSaveCopy = {},
            onShare = {},
        )
    }
}

@Preview(name = "Backup - unsupported version", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenUnsupportedVersionPreview() {
    TitanTheme {
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
            onSaveCopy = {},
            onShare = {},
        )
    }
}

@Preview(name = "Backup - malformed file", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun BackupScreenMalformedPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        BackupScreen(
            appVersion = PREVIEW_APP_VERSION,
            state = BackupUiState(
                footprint = SAMPLE_FOOTPRINT,
                lastRestore = RestoreOutcome.Failed(ImportError.MalformedJson("Unexpected EOF at 4211")),
            ),
            onEvent = {},
            onBack = {},
            onPickFile = {},
            onSaveCopy = {},
            onShare = {},
        )
    }
}
