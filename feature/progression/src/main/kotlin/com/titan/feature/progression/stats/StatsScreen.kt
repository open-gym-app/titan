package com.titan.feature.progression.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.component.EmptyState
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.test.TitanTestTags
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R
import com.titan.feature.progression.stats.component.ExploreSection
import com.titan.feature.progression.stats.component.MuscleMapCard
import com.titan.feature.progression.stats.component.StatsExploreActions
import com.titan.feature.progression.stats.component.StatsSummaryGrid
import com.titan.feature.progression.stats.component.TimeframeSelector
import com.titan.feature.progression.stats.component.VolumeHeroCard

/**
 * The Stats tab, wired.
 *
 * Takes its navigation as an ordinary parameter rather than reaching for a `NavController`,
 * which is the rule every screen in this codebase follows — see [StatsExploreActions].
 */
@Composable
fun StatsRoute(
    actions: StatsExploreActions,
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    StatsScreen(
        state = state,
        onEvent = viewModel::onEvent,
        actions = actions,
        modifier = modifier,
    )
}

/**
 * What the training added up to, over a window the user chooses.
 *
 * ### The selector is the screen's one control, and it changes everything under it
 *
 * Every figure below the selector is read over the window it names, including the muscle map
 * and the two comparisons — see [StatsViewModel], where one snapped period is handed to all
 * five reads. The selection is shared with the per-exercise drill-down, so opening an exercise
 * from here shows the same window rather than a second answer to the same question.
 *
 * ### Three states, and only one of them is a chart
 *
 * While the reads are in flight the screen draws its selector and nothing else. A skeleton of
 * the cards would be four grey boxes that turn into an empty state for the user most likely to
 * be waiting on a cold database — somebody who has just installed the app — and a chart of
 * zeros is what #32 asks this screen never to show.
 *
 * A window with no sessions in it draws an [EmptyState] instead of the cards, and *which* one
 * depends on whether there is history behind it: a lifter of three years who took six months
 * off must not be told to log their first session. See [StatsEmptiness], and
 * [com.titan.core.domain.model.ConsistencyTrend.hasEverTrained] for why no amount of looking
 * at the window can answer that.
 *
 * ### Explore stays, whatever the window says
 *
 * The rows are drawn in every state, including the first-run one. Two of the five destinations
 * have no other entrance in the app — the bottom bar is four tabs on purpose — so hiding them
 * behind "has trained" would make the quest log unreachable for exactly the user it is meant
 * to be motivating.
 */
@Composable
fun StatsScreen(
    state: StatsUiState,
    onEvent: (StatsEvent) -> Unit,
    actions: StatsExploreActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(TitanTheme.spacing.screen)
            // What `:benchmark` waits for to know the hub is on screen. The hero below carries
            // the tag that says it has finished — see `TitanTestTags`.
            .testTag(TitanTestTags.STATS_OVERVIEW),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        TimeframeSelector(
            selected = state.timeframe,
            onSelect = { onEvent(StatsEvent.TimeframeSelected(it)) },
        )

        if (!state.isLoading) {
            when (state.emptiness) {
                StatsEmptiness.FIRST_RUN -> FirstRunState()

                StatsEmptiness.QUIET_PERIOD -> QuietPeriodState(
                    onWidenTimeframe = { onEvent(StatsEvent.TimeframeSelected(StatsTimeframe.ALL)) },
                )

                null -> Readings(state = state)
            }

            ExploreSection(actions = actions)
        }
    }
}

/** The hero, the grid and the map — the screen when there is training to report. */
@Composable
private fun Readings(
    state: StatsUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        state.volume?.let { volume ->
            VolumeHeroCard(
                state = volume,
                modifier = Modifier.testTag(TitanTestTags.STATS_VOLUME_HERO),
            )
        }
        state.summary?.let { summary -> StatsSummaryGrid(state = summary) }

        if (state.muscles.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
                SectionHeading(text = stringResource(R.string.stats_muscles_heading))
                MuscleMapCard(muscles = state.muscles)
            }
        }
    }
}

/**
 * Nothing, ever.
 *
 * The one state that says "log your first session", and the reason the empty case is two
 * states rather than one.
 */
@Composable
private fun FirstRunState(modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.stats_empty_first_run_title),
        description = stringResource(R.string.stats_empty_first_run_body),
        glyph = stringResource(R.string.stats_empty_glyph),
        modifier = modifier,
    )
}

/**
 * Nothing in *this* window.
 *
 * The way out is the widest window rather than a way into a workout: the user has trained, and
 * what they are looking at is a window that happens to miss it. Telling somebody with three
 * years of history to start training would be the app failing to read its own data.
 */
@Composable
private fun QuietPeriodState(
    onWidenTimeframe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = stringResource(R.string.stats_empty_quiet_title),
        description = stringResource(R.string.stats_empty_quiet_body),
        glyph = stringResource(R.string.stats_empty_glyph),
        actionLabel = stringResource(R.string.stats_empty_quiet_action),
        action = onWidenTimeframe,
        modifier = modifier,
    )
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private val previewActions = StatsExploreActions(
    onOpenRecords = {},
    onOpenExerciseProgression = {},
    onOpenQuestLog = {},
    onOpenMeasurements = {},
    onOpenCalendar = {},
)

private fun previewState(
    timeframe: StatsTimeframe = StatsTimeframe.DEFAULT,
    emptiness: StatsEmptiness? = null,
): StatsUiState =
    StatsUiState(
        timeframe = timeframe,
        isLoading = false,
        emptiness = emptiness,
        volume = if (emptiness == null) previewVolume() else null,
        summary = if (emptiness == null) previewSummary() else null,
        muscles = if (emptiness == null) previewMuscles() else emptyList(),
    )

@Preview(name = "Stats", showBackground = true, widthDp = 360, heightDp = 1200)
@Composable
fun StatsScreenPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        StatsScreen(state = previewState(), onEvent = {}, actions = previewActions)
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
@Preview(name = "Stats - twice the font", showBackground = true, widthDp = 360, heightDp = 2000, fontScale = 2f)
@Composable
fun StatsScreenLargeFontPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        StatsScreen(state = previewState(), onEvent = {}, actions = previewActions)
    }
}

@Preview(name = "Stats - dark", showBackground = true, widthDp = 360, heightDp = 1200)
@Composable
fun StatsScreenDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        StatsScreen(state = previewState(), onEvent = {}, actions = previewActions)
    }
}

@Preview(name = "Stats - a week", showBackground = true, widthDp = 360, heightDp = 1200)
@Composable
fun StatsScreenWeekPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        StatsScreen(
            state = previewState(timeframe = StatsTimeframe.WEEK),
            onEvent = {},
            actions = previewActions,
        )
    }
}

@Preview(name = "Stats - nothing logged yet", showBackground = true, widthDp = 360, heightDp = 700)
@Composable
fun StatsScreenFirstRunPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        StatsScreen(
            state = previewState(emptiness = StatsEmptiness.FIRST_RUN),
            onEvent = {},
            actions = previewActions,
        )
    }
}

@Preview(name = "Stats - a quiet window", showBackground = true, widthDp = 360, heightDp = 700)
@Composable
fun StatsScreenQuietPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        StatsScreen(
            state = previewState(timeframe = StatsTimeframe.MONTH, emptiness = StatsEmptiness.QUIET_PERIOD),
            onEvent = {},
            actions = previewActions,
        )
    }
}

@Preview(name = "Stats - loading", showBackground = true, widthDp = 360, heightDp = 400)
@Composable
fun StatsScreenLoadingPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        StatsScreen(state = StatsUiState(), onEvent = {}, actions = previewActions)
    }
}
