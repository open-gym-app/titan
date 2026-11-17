package com.titan.feature.settings.importer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.settings.R
import com.titan.feature.settings.importer.component.ActiveWorkoutDialog
import com.titan.feature.settings.importer.component.CancelImportDialog
import com.titan.feature.settings.importer.component.ImportFailurePanel
import com.titan.feature.settings.importer.component.ImportGuide
import com.titan.feature.settings.importer.component.ImportInterruptedPanel
import com.titan.feature.settings.importer.component.ImportProgressPanel
import com.titan.feature.settings.importer.component.ImportReportPanel
import com.titan.feature.settings.importer.component.ImportSourceList
import com.titan.feature.settings.importer.component.ImportStoppedPanel

/**
 * The import from another app, wired to its ViewModel, the system file picker and the browser.
 *
 * @param onOpenExerciseLibrary opens the Library tab, for reviewing the custom exercises an
 *   import created. Hoisted to `:app`: the library is another feature's screen.
 */
@Composable
fun ImportRoute(
    onBack: () -> Unit,
    onOpenExerciseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    // Filtered loosely on purpose: a CSV that has been through a drive or an email comes back
    // declaring any of these, and a filter of `text/csv` alone hides the file the user is
    // looking for. The parser decides what the file is, by its contents. A cancelled picker
    // calls back with null and sends nothing.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onEvent(ImportEvent.FileChosen(uri.toString()))
    }

    // Back from a guide is back to the list of apps, not out of the screen: the guide is a
    // step inside it, as the onboarding steps are inside theirs.
    BackHandler(enabled = state.stage is ImportStage.Guide) {
        viewModel.onEvent(ImportEvent.GuideClosed)
    }

    ImportScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onChooseFile = { picker.launch(IMPORT_MIME_TYPES) },
        // Not navigation: it leaves the app for the browser, like the GitHub row in settings.
        onOpenFormat = { uriHandler.openUri(PLAIN_CSV_FORMAT_URL) },
        onOpenExerciseLibrary = onOpenExerciseLibrary,
        modifier = modifier,
    )
}

/**
 * The whole screen, stateless: it renders [state] and reports [ImportEvent]s.
 *
 * One stage at a time, top to bottom in a scrolling column, under a bar whose back arrow means
 * what the system back gesture means: from a guide, the list of apps; anywhere else, out.
 *
 * @param onChooseFile opens the system document picker. Hoisted for the reason the backup
 *   screen hoists its own: launching a contract needs a launcher remembered in a composition a
 *   screen test does not have.
 */
@Composable
fun ImportScreen(
    state: ImportUiState,
    onEvent: (ImportEvent) -> Unit,
    onBack: () -> Unit,
    onChooseFile: () -> Unit,
    onOpenFormat: () -> Unit,
    onOpenExerciseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stage = state.stage

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ImportTopBar(
                onBack = if (stage is ImportStage.Guide) {
                    { onEvent(ImportEvent.GuideClosed) }
                } else {
                    onBack
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(TitanTheme.spacing.screen),
        ) {
            StageBody(
                stage = stage,
                onEvent = onEvent,
                onChooseFile = onChooseFile,
                onOpenFormat = onOpenFormat,
                onOpenExerciseLibrary = onOpenExerciseLibrary,
            )
        }
    }

    if (state.pendingRequest != null) {
        ActiveWorkoutDialog(
            onConfirm = { onEvent(ImportEvent.StartConfirmed) },
            onDismiss = { onEvent(ImportEvent.StartDismissed) },
        )
    }

    if (state.confirmingCancel && stage is ImportStage.Running) {
        CancelImportDialog(
            storingPhotos = stage.progress is HistoryImportProgress.StoringPhotos,
            onConfirm = { onEvent(ImportEvent.CancelConfirmed) },
            onDismiss = { onEvent(ImportEvent.CancelDismissed) },
        )
    }
}

/** Which stage is drawn. Exhaustive, so a new stage is a compile error rather than a blank page. */
@Composable
private fun StageBody(
    stage: ImportStage,
    onEvent: (ImportEvent) -> Unit,
    onChooseFile: () -> Unit,
    onOpenFormat: () -> Unit,
    onOpenExerciseLibrary: () -> Unit,
) {
    val fill = Modifier.fillMaxWidth()
    when (stage) {
        ImportStage.Loading -> Unit

        ImportStage.ChooseSource -> ImportSourceList(
            onChoose = { onEvent(ImportEvent.SourceChosen(it)) },
            modifier = fill,
        )

        is ImportStage.Guide -> ImportGuide(
            source = stage.source,
            onChooseFile = onChooseFile,
            onOpenFormat = onOpenFormat,
            modifier = fill,
        )

        is ImportStage.Running -> ImportProgressPanel(
            source = stage.source,
            progress = stage.progress,
            onCancel = { onEvent(ImportEvent.CancelRequested) },
            modifier = fill,
        )

        is ImportStage.Completed -> ImportReportPanel(
            source = stage.source,
            report = stage.report,
            onOpenExerciseLibrary = onOpenExerciseLibrary,
            onDone = { onEvent(ImportEvent.OutcomeDismissed) },
            modifier = fill,
        )

        is ImportStage.Failed -> ImportFailurePanel(
            source = stage.source,
            error = stage.error,
            onTryAgain = { onEvent(ImportEvent.TryAgain) },
            onChooseAnotherApp = { onEvent(ImportEvent.ChooseAnotherApp) },
            modifier = fill,
        )

        is ImportStage.Interrupted -> ImportInterruptedPanel(
            source = stage.request.source,
            onRunAgain = { onEvent(ImportEvent.RunAgain) },
            onNotNow = { onEvent(ImportEvent.OutcomeDismissed) },
            modifier = fill,
        )

        is ImportStage.Stopped -> ImportStoppedPanel(
            source = stage.source,
            stop = stage.stop,
            onDone = { onEvent(ImportEvent.OutcomeDismissed) },
            modifier = fill,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportTopBar(onBack: () -> Unit) {
    Column {
        TopAppBar(
            title = {
                Text(text = stringResource(R.string.import_title), style = MaterialTheme.typography.titleLarge)
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

/**
 * What the picker offers. Every type a CSV is declared as somewhere — by a drive, an email
 * client, a spreadsheet's save — and plain text, which several exporters write.
 */
private val IMPORT_MIME_TYPES = arrayOf(
    "text/*",
    "application/csv",
    "application/vnd.ms-excel",
    "application/octet-stream",
)
