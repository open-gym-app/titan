package com.opengym.feature.progression.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseProgression
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.EmptyState
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.component.label
import com.opengym.core.ui.component.labelOrNull
import com.opengym.core.ui.test.OpenGymTestTags
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.exercise.component.ExerciseChooser
import com.opengym.feature.progression.exercise.component.ExerciseRecordCards
import com.opengym.feature.progression.exercise.component.ExerciseTrendCards
import com.opengym.feature.progression.exercise.component.RecentAttemptsTable
import com.opengym.feature.progression.stats.StatsTimeframe
import com.opengym.feature.progression.stats.component.TimeframeSelector
import com.opengym.core.ui.R as CoreR

/**
 * The per-exercise drill-down, wired.
 *
 * Takes its way out as an ordinary parameter rather than reaching for a `NavController`, the
 * rule every screen in this codebase follows. There is only one: which lift is being read is a
 * value the screen owns rather than a destination, so switching is an event and not a push —
 * see [ExerciseProgressionViewModel].
 */
@Composable
fun ExerciseProgressionRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExerciseProgressionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ExerciseProgressionScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * One lift: its bests, where it is going, and what it has done session by session.
 *
 * ### Two screens behind one bar
 *
 * With no lift chosen this is the chooser; with one it is the drill-down, and the bar's
 * switcher moves between them. They are one destination because the chooser is also the
 * switcher — see [ExerciseProgressionUiState].
 *
 * They are also two *layouts*, and the body picks one rather than nesting them. The drill-down
 * is a fixed handful of sections and scrolls as an ordinary column; the chooser is a row per
 * lift the user has ever trained, with no cap on it, so it is a lazy list and has to own the
 * scroll to be one — see [ExerciseChooser].
 *
 * ### Four states below the selector, and only one of them is a chart
 *
 * While the reads are in flight the screen draws nothing under its selector. A skeleton would
 * be grey boxes that turn into an empty state for the user most likely to be waiting on a cold
 * database, and the records above the charts are the part somebody opened this for. The
 * selector itself stays — [DrillDown] says why.
 *
 * A lift never logged, a window with nothing in it, and a window holding exactly one session
 * each get their own line — see [ExerciseEmptiness] for why one flag could not tell them
 * apart. Only the last of the four draws the charts, which is #102's criterion that one
 * session never becomes a bar filling its own track.
 *
 * ### The records stay, whatever the window says
 *
 * They are all-time figures and are drawn above every empty state but the first: narrowing the
 * timeframe onto a quiet fortnight does not take a lifter's best bench away, and hiding it
 * would suggest it had.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseProgressionScreen(
    state: ExerciseProgressionUiState,
    onEvent: (ExerciseProgressionEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // Outside the tab scaffold, like the exercise detail and the plate calculator, so the
        // bar spends the system bars' space and the body below it does not spend it again.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            TopAppBar(
                title = { ExerciseTitle(exercise = state.exercise) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.progression_detail_back),
                        )
                    }
                },
                actions = {
                    // Drawn only when there is something to switch *away from*. On the chooser
                    // itself it would be a control that puts up the screen already on show.
                    if (state.exercise != null) {
                        IconButton(onClick = { onEvent(ExerciseProgressionEvent.ExerciseCleared) }) {
                            Icon(
                                imageVector = Icons.Outlined.SwapHoriz,
                                contentDescription = stringResource(R.string.exercise_switch),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        val body = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        // The chooser only once the reads have answered: `choices` is empty while they are in
        // flight, and the branch for a user with nothing logged would otherwise be flashed at
        // every user with something.
        if (state.exercise == null && !state.isLoading) {
            ChooserBody(state = state, onEvent = onEvent, modifier = body)
        } else {
            DrillDown(state = state, onEvent = onEvent, modifier = body)
        }
    }
}

/**
 * The bar's title: the lift's name over what it is, or the screen's own name on the chooser.
 *
 * The second line is the wireframes' subtitle — `FREE WEIGHT · BARBELL` — in the eyebrow role,
 * which is what the app uses for a line that qualifies rather than names.
 */
@Composable
private fun ExerciseTitle(exercise: Exercise?) {
    if (exercise == null) {
        Text(text = stringResource(R.string.exercise_progression_title), maxLines = 1)
        return
    }

    val qualifiers = listOfNotNull(exercise.equipmentType?.labelOrNull(), exercise.type.label())

    Column {
        Text(text = exercise.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Eyebrow(text = qualifiers.joinToString(stringResource(CoreR.string.exercise_meta_separator)))
    }
}

/**
 * The chooser, or the line for a user who has nothing to choose from.
 *
 * The empty case is reachable — the hub's explore row is drawn for a brand-new user on purpose,
 * because two of the five destinations behind it have no other entrance — so it says what to do
 * rather than showing an empty heading over nothing.
 */
@Composable
private fun ChooserBody(
    state: ExerciseProgressionUiState,
    onEvent: (ExerciseProgressionEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.choices.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.exercise_empty_nothing_title),
            description = stringResource(R.string.exercise_empty_nothing_body),
            glyph = stringResource(R.string.exercise_empty_glyph),
            modifier = modifier.padding(OpenGymTheme.spacing.screen),
        )
        return
    }

    ExerciseChooser(
        choices = state.choices,
        onSelect = { onEvent(ExerciseProgressionEvent.ExerciseSelected(it)) },
        // Tagged so `:benchmark` can pick a lift without reading its name — see
        // `OpenGymTestTags`.
        modifier = modifier.testTag(OpenGymTestTags.EXERCISE_CHOOSER),
        // The margin the drill-down spends on its column, spent inside the scroll instead:
        // the same inset, on a list that has to be able to scroll its last row past it.
        contentPadding = PaddingValues(OpenGymTheme.spacing.screen),
    )
}

/**
 * The lift: its window, and whatever the window has to say.
 *
 * One scrolling column rather than a lazy one, because what it holds is a fixed few sections
 * — the selector, four record cards, two charts and a table capped at eight rows — and a lazy
 * list of a known handful buys nothing for the state it costs.
 *
 * ### The selector sits above the loading check, unlike everything under it
 *
 * The segments answer at the speed of a thumb where the readings answer at the speed of a
 * query, and a selector that vanished for the length of the read its own tap started would be
 * the screen taking away the control the user is still using. [ExerciseProgressionUiState]'s
 * `isExerciseSelected` is what carries it across that gap.
 */
@Composable
private fun DrillDown(
    state: ExerciseProgressionUiState,
    onEvent: (ExerciseProgressionEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        if (state.isExerciseSelected) {
            TimeframeSelector(
                selected = state.timeframe,
                onSelect = { onEvent(ExerciseProgressionEvent.TimeframeSelected(it)) },
            )
        }

        val progression = state.progression
        if (!state.isLoading && progression != null) {
            Readings(state = state, progression = progression, onEvent = onEvent)
        }
    }
}

/** The cards, whatever comes after them, and the table. */
@Composable
private fun Readings(
    state: ExerciseProgressionUiState,
    progression: ExerciseProgression,
    onEvent: (ExerciseProgressionEvent) -> Unit,
) {
    if (state.emptiness == ExerciseEmptiness.NEVER_TRAINED) {
        EmptyState(
            title = stringResource(R.string.exercise_empty_never_title),
            description = stringResource(R.string.exercise_empty_never_body),
            glyph = stringResource(R.string.exercise_empty_glyph),
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section)) {
        ExerciseRecordCards(progression = progression)

        when (state.emptiness) {
            ExerciseEmptiness.QUIET_PERIOD -> EmptyState(
                title = stringResource(R.string.exercise_empty_quiet_title),
                description = stringResource(R.string.exercise_empty_quiet_body),
                glyph = stringResource(R.string.exercise_empty_glyph),
                actionLabel = stringResource(R.string.exercise_empty_quiet_action),
                action = { onEvent(ExerciseProgressionEvent.TimeframeSelected(StatsTimeframe.ALL)) },
            )

            ExerciseEmptiness.SINGLE_SESSION -> EmptyState(
                title = stringResource(R.string.exercise_empty_single_title),
                description = stringResource(R.string.exercise_empty_single_body),
                glyph = stringResource(R.string.exercise_empty_glyph),
            )

            // The chart pair #42 puts a 200ms budget on. The tag is what says it is drawn.
            else -> ExerciseTrendCards(
                progression = progression,
                modifier = Modifier.testTag(OpenGymTestTags.EXERCISE_PROGRESSION_CHART),
            )
        }

        if (state.recentAttempts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
                SectionHeading(text = stringResource(R.string.exercise_attempts_heading))
                RecentAttemptsTable(
                    attempts = state.recentAttempts,
                    // The one attempt a record was broken in, which by construction is the most
                    // recent one on screen — see `ExerciseProgression.newRecords`.
                    recordSessionId = progression.attempts
                        .lastOrNull()
                        ?.sessionId
                        ?.takeIf { progression.newRecords.isNotEmpty() },
                )
            }
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Exercise progression", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
fun ExerciseProgressionScreenPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseProgressionScreen(state = previewState(), onEvent = {}, onBack = {})
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
    name = "Exercise progression - twice the font",
    showBackground = true,
    widthDp = 360,
    heightDp = 1800,
    fontScale = 2f,
)
@Composable
fun ExerciseProgressionScreenLargeFontPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseProgressionScreen(state = previewState(), onEvent = {}, onBack = {})
    }
}

@Preview(name = "Exercise progression - dark", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
fun ExerciseProgressionScreenDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        ExerciseProgressionScreen(state = previewState(), onEvent = {}, onBack = {})
    }
}

@Preview(name = "Exercise progression - the chooser", showBackground = true, widthDp = 360, heightDp = 500)
@Composable
fun ExerciseProgressionChooserPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseProgressionScreen(state = previewChooserState(), onEvent = {}, onBack = {})
    }
}

@Preview(name = "Exercise progression - one session", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun ExerciseProgressionSingleSessionPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseProgressionScreen(state = previewSingleSessionState(), onEvent = {}, onBack = {})
    }
}

@Preview(name = "Exercise progression - a quiet window", showBackground = true, widthDp = 360, heightDp = 700)
@Composable
fun ExerciseProgressionQuietPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseProgressionScreen(state = previewQuietState(), onEvent = {}, onBack = {})
    }
}

@Preview(name = "Exercise progression - nothing logged", showBackground = true, widthDp = 360, heightDp = 500)
@Composable
fun ExerciseProgressionNothingLoggedPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseProgressionScreen(
            state = ExerciseProgressionUiState(isLoading = false),
            onEvent = {},
            onBack = {},
        )
    }
}
