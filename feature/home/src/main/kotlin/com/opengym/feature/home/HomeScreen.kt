package com.opengym.feature.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.DiscardSessionDialog
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.home.component.LevelHeader
import com.opengym.feature.home.component.QuickStartSection
import com.opengym.feature.home.component.RecentRecordSection
import com.opengym.feature.home.component.ResumeCard
import com.opengym.feature.home.component.VolumeSection
import com.opengym.feature.home.component.WeekSection
import kotlinx.coroutines.flow.filterNotNull

/**
 * The dashboard, wired to its ViewModel.
 *
 * Every navigation out of Home is a parameter rather than an event, because not one of the
 * destinations belongs to this module: the workout, the new-session flow and its template step
 * live in `:feature:session`, the records list and the stats tab in `:feature:progression`, and
 * `:app` is what knows that — see `homeGraph`.
 *
 * @param onResumeSession the open workout should be picked back up; the id is the session to
 *   open. Nothing is resumed here: the workout screen calls `ResumeSessionUseCase` itself, so
 *   this is a navigation and only a navigation.
 * @param onStartEmptySession the user wants a workout they will build as they go.
 * @param onStartFromTemplates the user wants to pick a template first.
 * @param onStartFromTemplate the user picked one from the quick-start strip.
 * @param onOpenSessions the week card was tapped: the user wants the sessions it is counting.
 * @param onOpenRecords the recent-PR card was tapped: the user wants every record, not the one.
 * @param onOpenStats the volume chart was tapped: the user wants the figures behind the shape.
 */
@Composable
fun HomeRoute(
    onResumeSession: (sessionId: Long) -> Unit,
    onStartEmptySession: () -> Unit,
    onStartFromTemplates: () -> Unit,
    onStartFromTemplate: (templateId: Long) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    HomeScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onResumeSession = onResumeSession,
        onStartEmptySession = onStartEmptySession,
        onStartFromTemplates = onStartFromTemplates,
        onStartFromTemplate = onStartFromTemplate,
        onOpenSessions = onOpenSessions,
        onOpenRecords = onOpenRecords,
        onOpenStats = onOpenStats,
        modifier = modifier,
    )
}

/**
 * Where the app lands, and the shortest route from opening it to logging a set.
 *
 * ### What is on it, in the order it matters
 *
 * The standing first, as a header rather than a card — see [LevelHeader]. Then a workout the
 * app is still holding open, which takes the accent, because a user who has one has exactly one
 * thing they want from this screen. Under it, the week and the streak beside it — the figures
 * that answer "am I keeping this up" without opening anything. Then the ways in: two actions,
 * and the templates as a strip for the case where the workout is already decided.
 *
 * **The two progression cards come last, and that is where they belong.** The last record
 * broken and eight weeks of volume are what the user has *done*; everything above them is what
 * they might do next, and a dashboard that opened with history would put the way into a workout
 * below the fold on the one screen the app is meant to be quickest from. Both are ways into the
 * screens that hold the whole answer — the records list and the stats tab.
 *
 * ### Nothing renders as an empty shell
 *
 * Each block is drawn only when it has something to say — #110's rule, applied to all six. A
 * first-launch user gets the quick-start actions and nothing else: no header, no banner, no
 * strip of seven blank squares, no "0 sessions", no chart of noughts. The screen is allowed to
 * be short, and it fills in one card at a time as the engines behind them have something to
 * report.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onEvent: (HomeEvent) -> Unit,
    onResumeSession: (sessionId: Long) -> Unit,
    onStartEmptySession: () -> Unit,
    onStartFromTemplates: () -> Unit,
    onStartFromTemplate: (templateId: Long) -> Unit,
    onOpenSessions: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message?.let { stringResource(it.textRes()) }

    // Marked shown *before* the snackbar is, and keyed on `Unit` rather than on the message —
    // `SettingsScreen` documents the pair at length. In short: `showSnackbar` suspends for as
    // long as it is up, so clearing afterwards would leave the message in the surviving
    // ViewModel for a rotation to show a second time, and keying on it would cancel the
    // snackbar on the frame it appeared.
    val currentMessage by rememberUpdatedState(message)
    LaunchedEffect(Unit) {
        snapshotFlow { currentMessage }
            .filterNotNull()
            .collect { text ->
                onEvent(HomeEvent.MessageShown)
                snackbarHostState.showSnackbar(text)
            }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // The tab is inside the shell's own `Scaffold`, which has already inset the content for
        // the system bars and padded the `NavHost` by it. Asking again would push Home down by
        // a status bar it has already been moved clear of.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(OpenGymTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
        ) {
            // Nothing at all until the reads land — the actions included, though they need no
            // data to draw. Drawing them alone puts two full-width buttons at the top of an
            // otherwise empty screen and then inserts a banner and a week *above* them a frame
            // later, moving them the height of both under a thumb already on its way down.
            // A screen that stays empty for the frame the queries take is the cheaper half of
            // that trade.
            if (!state.isLoading) {
                state.level?.let { level ->
                    LevelHeader(level = level)
                }
                state.resume?.let { resume ->
                    ResumeCard(
                        resume = resume,
                        onResume = { onResumeSession(resume.sessionId) },
                        onDiscard = { onEvent(HomeEvent.DiscardRequested) },
                    )
                }
                state.week?.let { week ->
                    WeekSection(week = week, onClick = onOpenSessions)
                }

                QuickStartSection(
                    templates = state.templates,
                    onStartEmptySession = onStartEmptySession,
                    onStartFromTemplates = onStartFromTemplates,
                    onStartFromTemplate = onStartFromTemplate,
                    // A workout is already open, and the flow these lead into would refuse to
                    // start a second one — `ResumeSessionDialog` is where the user finds that
                    // out today, a screen later. Home knows now, and the banner above says so
                    // and offers both ways out of it.
                    enabled = state.resume == null,
                    // No week means nothing has ever been finished — see HomeUiState.week.
                    isFirstLaunch = state.week == null,
                )

                state.recentRecord?.let { record ->
                    RecentRecordSection(record = record, onClick = onOpenRecords)
                }
                if (state.volume.isNotEmpty()) {
                    VolumeSection(weeks = state.volume, onClick = onOpenStats)
                }
            }
        }
    }

    if (state.confirmingDiscard) {
        // Captured when the question is asked, not read while it is up. The row is deleted
        // before the dialog closes — `getActive()` re-emits null the moment it goes, and the
        // flag is only cleared once the whole delete returns — so a live read flips "11 logged
        // sets will be deleted" to "nothing will be lost" while the cascade is still running,
        // which is a lie about a destructive action, told at the worst moment.
        val loggedSets = remember { state.resume?.loggedSets ?: 0 }

        DiscardSessionDialog(
            loggedSets = loggedSets,
            onConfirm = { onEvent(HomeEvent.DiscardConfirmed) },
            onDismiss = { onEvent(HomeEvent.DiscardDismissed) },
            isDiscarding = state.isDiscarding,
        )
    }
}

/** What a message says. Resolved in the screen, so the ViewModel never holds a `Context`. */
@StringRes
internal fun HomeMessage.textRes(): Int =
    when (this) {
        HomeMessage.DISCARD_FAILED -> R.string.home_discard_failed
    }

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Home", showBackground = true, heightDp = 720)
@Composable
fun HomeScreenPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        HomePreview(state = previewHome())
    }
}

@Preview(name = "Home, dark", showBackground = true, heightDp = 720)
@Composable
fun HomeScreenDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        HomePreview(state = previewHome())
    }
}

@Preview(name = "Home - nothing running", showBackground = true, heightDp = 720)
@Composable
fun HomeScreenIdlePreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        HomePreview(state = previewHome(resume = null))
    }
}

@Preview(name = "Home - a paused workout", showBackground = true, heightDp = 720)
@Composable
fun HomeScreenPausedPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        HomePreview(state = previewHome(resume = PREVIEW_PAUSED))
    }
}

/**
 * A user a fortnight in: a level, a week and a streak, and no progression cards at all.
 *
 * The middle of the three states #110 asks for, and the one a screenshot is worth most on —
 * "fully populated" and "brand new" are both easy to get right by accident, and this is where a
 * card that renders an empty shell instead of disappearing would show up.
 */
@Preview(name = "Home - a fortnight in", showBackground = true, heightDp = 720)
@Composable
fun HomeScreenPartialPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        HomePreview(state = previewPartialHome())
    }
}

@Preview(name = "Home - first launch", showBackground = true, heightDp = 720)
@Composable
fun HomeScreenFirstLaunchPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        // Nothing logged, nothing saved, nothing running: the quick-start actions alone, which
        // is the whole screen for a user on their first morning with the app.
        HomePreview(state = HomeUiState(isLoading = false))
    }
}

@Preview(name = "Home - discarding", showBackground = true, heightDp = 720)
@Composable
fun HomeScreenDiscardPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        HomePreview(state = previewHome().copy(confirmingDiscard = true))
    }
}

/**
 * Every label at twice its size, which is the bar #40 holds the app to.
 *
 * The banner is the case worth looking at: its clock, its title and two buttons side by side
 * are what a font scale breaks first. Recorded rather than argued about — the reference image
 * is the evidence, and it is a screenshot test like any other, so a change that starts
 * clipping something at 2x fails the build.
 */
@Preview(name = "Home - twice the font", showBackground = true, heightDp = 1000, fontScale = 2f)
@Composable
fun HomeScreenLargeFontPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        HomePreview(state = previewHome())
    }
}

/** The screen with its navigations tied off, so a preview says only what it is showing. */
@Composable
private fun HomePreview(state: HomeUiState) {
    HomeScreen(
        state = state,
        onEvent = {},
        onResumeSession = {},
        onStartEmptySession = {},
        onStartFromTemplates = {},
        onStartFromTemplate = {},
        onOpenSessions = {},
        onOpenRecords = {},
        onOpenStats = {},
    )
}
