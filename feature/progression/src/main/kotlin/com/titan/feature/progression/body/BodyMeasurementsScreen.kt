package com.titan.feature.progression.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.component.EmptyState
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.format.formatSessionDate
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R
import com.titan.feature.progression.body.component.BodyWeightCard
import com.titan.feature.progression.body.component.CompositionTiles
import com.titan.feature.progression.body.component.HealthConnectGroup
import com.titan.feature.progression.body.component.MeasurementFormSheet
import com.titan.feature.progression.body.component.MeasurementHistoryRow
import com.titan.feature.progression.body.component.MeasurementsCard
import com.titan.feature.progression.body.component.ProgressPhotosGroup
import com.titan.feature.progression.stats.component.TimeframeSelector

/**
 * The body screen, wired.
 *
 * Takes its way out as an ordinary parameter rather than reaching for a `NavController`, the
 * rule every screen in this codebase follows. There is only one way out: logging is a sheet
 * over this screen rather than a destination — see [BodyMeasurementsViewModel].
 */
@Composable
fun BodyMeasurementsRoute(
    onBack: () -> Unit,
    onOpenPhotos: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BodyMeasurementsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BodyMeasurementsScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onOpenPhotos = onOpenPhotos,
        modifier = modifier,
    )
}

/**
 * What the user weighs, what they measure, and everything they have recorded.
 *
 * ### A lazy list, unlike the other two progression screens
 *
 * The hub and the per-exercise drill-down are fixed handfuls of sections and scroll as ordinary
 * columns. This one ends in a history with no ceiling on it — a user weighing themselves weekly
 * for a decade has five hundred rows — so the whole screen is a [LazyColumn] and the fixed
 * sections above the history are items in it. Laying five hundred rows out eagerly to show the
 * six that fit is the cost that made the choice.
 *
 * ### The empty state replaces the readings and not the screen
 *
 * A user who has logged nothing gets the line that says so and the way to start, and the Health
 * Connect row stays under it — they are exactly the person deciding between typing measurements
 * in and waiting for the sync. The timeframe selector goes with the readings: a window over an
 * empty history is a control with nothing to do.
 *
 * ### One empty state, not three
 *
 * The other progression screens tell a quiet window apart from a first run, because every
 * figure on them is a sum over the window. Here the hero shows the last weight recorded
 * whenever it was taken, the tiles show the last reading of each figure, and only the chart is
 * window-bound — and it says "nothing to plot yet" in its own placeholder. So there is nothing
 * a second empty state would be covering for. See [BodyMeasurementsUiState.isEmpty].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyMeasurementsScreen(
    state: BodyMeasurementsUiState,
    onEvent: (BodyMeasurementsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPhotos: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    // The undo, which is what lets a row go on a short drag with no question asked — see
    // `SwipeToDelete`. Keyed on the entry's id, so a second row swiped away while the first
    // offer is up replaces that offer rather than queueing behind it: two identical buttons in
    // a row would mean two different entries. `Long` for the reason the workout's set undo is:
    // this is the window in which a mistake can be taken back, not a notification.
    val deletedText = state.deleted?.let {
        stringResource(R.string.body_history_deleted, formatSessionDate(it.createdAt))
    }
    val undoLabel = stringResource(R.string.body_history_undo)

    LaunchedEffect(state.deleted?.id) {
        if (deletedText == null) return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = deletedText,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Long,
        )
        onEvent(
            when (result) {
                SnackbarResult.ActionPerformed -> BodyMeasurementsEvent.DeleteUndone
                SnackbarResult.Dismissed -> BodyMeasurementsEvent.DeleteDismissed
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Outside the tab scaffold, like the other drill-downs, so the bar spends the system
        // bars' space and the body below it does not spend it again.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.body_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.progression_detail_back),
                        )
                    }
                },
                actions = {
                    // In the bar rather than as a floating button, which this app has none of:
                    // the screen is a readout with one thing to do to it, and a FAB would sit
                    // over the history it is meant to add to.
                    IconButton(onClick = { onEvent(BodyMeasurementsEvent.LogRequested) }) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.body_log_action),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(TitanTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
        ) {
            // Nothing under the bar while the read is in flight. A skeleton would be grey boxes
            // that turn into an empty state for the user most likely to be waiting on a cold
            // database — the argument the drill-down makes for the same blank.
            if (state.isLoading) return@LazyColumn

            if (state.isEmpty) {
                item { EmptyBody(onEvent = onEvent) }
            } else {
                readings(state = state, onEvent = onEvent)
            }

            item { ProgressPhotosGroup(onOpen = onOpenPhotos) }

            item { HealthConnectGroup() }

            if (state.history.isNotEmpty()) {
                history(state = state, onEvent = onEvent)
            }
        }
    }

    state.form?.let { entry ->
        MeasurementFormSheet(
            entry = entry,
            onSave = { onEvent(BodyMeasurementsEvent.Saved(it)) },
            onDismiss = { onEvent(BodyMeasurementsEvent.FormDismissed) },
            isSaving = state.isSaving,
        )
    }
}

/** The window, the weight, the parts and the composition. */
private fun LazyListScope.readings(
    state: BodyMeasurementsUiState,
    onEvent: (BodyMeasurementsEvent) -> Unit,
) {
    item {
        TimeframeSelector(
            selected = state.timeframe,
            onSelect = { onEvent(BodyMeasurementsEvent.TimeframeSelected(it)) },
        )
    }
    item {
        BodyWeightCard(
            weights = state.weights,
            latest = state.readings.weight,
            change = state.weightChange,
        )
    }
    // Only once something has been taped. Four dashes beside an unlit figure is a card telling
    // a user who tracks their weight alone that they are missing four measurements.
    if (state.readings.hasCircumference) {
        item { MeasurementsCard(readings = state.readings) }
    }
    item { CompositionTiles(readings = state.readings) }
}

/** Every entry, newest first, each one editable and removable. */
private fun LazyListScope.history(
    state: BodyMeasurementsUiState,
    onEvent: (BodyMeasurementsEvent) -> Unit,
) {
    item { SectionHeading(text = stringResource(R.string.body_history_heading)) }
    items(items = state.history, key = { it.id }) { entry ->
        MeasurementHistoryRow(
            measurement = entry,
            onEdit = { onEvent(BodyMeasurementsEvent.EditRequested(entry)) },
            onDelete = { onEvent(BodyMeasurementsEvent.Deleted(entry.id)) },
        )
    }
}

/** The line for a user who has measured nothing, and the button that starts them off. */
@Composable
private fun EmptyBody(onEvent: (BodyMeasurementsEvent) -> Unit) {
    EmptyState(
        title = stringResource(R.string.body_empty_title),
        description = stringResource(R.string.body_empty_body),
        glyph = stringResource(R.string.body_empty_glyph),
        actionLabel = stringResource(R.string.body_empty_action),
        action = { onEvent(BodyMeasurementsEvent.LogRequested) },
    )
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Body measurements", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
fun BodyMeasurementsScreenPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        BodyMeasurementsScreen(state = previewBodyState(), onEvent = {}, onBack = {})
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
@Preview(
    name = "Body measurements - twice the font",
    showBackground = true,
    widthDp = 360,
    heightDp = 1800,
    fontScale = 2f,
)
@Composable
fun BodyMeasurementsScreenLargeFontPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        BodyMeasurementsScreen(state = previewBodyState(), onEvent = {}, onBack = {})
    }
}

@Preview(name = "Body measurements - dark", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
fun BodyMeasurementsScreenDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        BodyMeasurementsScreen(state = previewBodyState(), onEvent = {}, onBack = {})
    }
}

@Preview(name = "Body measurements - weight only", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun BodyMeasurementsWeightOnlyPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        BodyMeasurementsScreen(state = previewWeightOnlyState(), onEvent = {}, onBack = {})
    }
}

@Preview(name = "Body measurements - nothing logged", showBackground = true, widthDp = 360, heightDp = 600)
@Composable
fun BodyMeasurementsEmptyPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        BodyMeasurementsScreen(
            state = BodyMeasurementsUiState(isLoading = false),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview(name = "Body measurements - the form", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
fun BodyMeasurementsFormPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        val state = previewBodyState()
        BodyMeasurementsScreen(
            state = state.copy(form = state.history.first().copy(id = NEW_ENTRY)),
            onEvent = {},
            onBack = {},
        )
    }
}

private fun previewBodyState(): BodyMeasurementsUiState {
    val history = previewHistory()
    return BodyMeasurementsUiState(
        isLoading = false,
        history = history,
        window = history.asReversed(),
    )
}

private fun previewWeightOnlyState(): BodyMeasurementsUiState {
    val history = previewHistory().map { it.copy(chest = null, waist = null, arm = null, thigh = null, bodyFat = null) }
    return BodyMeasurementsUiState(
        isLoading = false,
        history = history,
        window = history.asReversed(),
    )
}

/** Nine weekly weigh-ins over a cut, newest first: a kilo and a half off the top. */
private val PREVIEW_WEIGHTS_G = listOf(78_400L, 78_600L, 79_100L, 78_800L, 79_000L, 79_500L, 80_100L, 79_800L, 80_000L)

/** Those weigh-ins as entries, with the tape out on the most recent. */
private fun previewHistory(): List<BodyMeasurement> =
    PREVIEW_WEIGHTS_G
        .mapIndexed { index, grams ->
            BodyMeasurement(
                id = index + 1L,
                weight = grams * MILLIGRAMS_PER_GRAM,
                chest = if (index == 0) 1_020 else null,
                waist = if (index == 0) 820 else null,
                arm = if (index == 0) 360 else null,
                thigh = if (index == 0) 580 else null,
                bodyFat = if (index == 0) 142 else null,
                createdAt = PREVIEW_INSTANT - index * PREVIEW_WEEK,
                updatedAt = PREVIEW_INSTANT - index * PREVIEW_WEEK,
            )
        }

/** What a gram is, in the milligrams a weight is stored in. */
private const val MILLIGRAMS_PER_GRAM = 1_000L

/** A fixed instant, so the previews do not move with the wall clock. */
private const val PREVIEW_INSTANT = 1_777_000_000_000L

private const val PREVIEW_WEEK = 7 * 86_400_000L
