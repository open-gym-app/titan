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
import com.opengym.feature.home.component.QuickStartSection
import com.opengym.feature.home.component.ResumeCard
import com.opengym.feature.home.component.WeekSection
import kotlinx.coroutines.flow.filterNotNull

/**
 * The dashboard, wired to its ViewModel.
 *
 * Every navigation out of Home is a parameter rather than an event, because none of the four
 * destinations belongs to this module: the workout, the new-session flow and its template step
 * all live in `:feature:session`, and `:app` is what knows that — see `homeGraph`.
 *
 * @param onResumeSession the open workout should be picked back up; the id is the session to
 *   open. Nothing is resumed here: the workout screen calls `ResumeSessionUseCase` itself, so
 *   this is a navigation and only a navigation.
 * @param onStartEmptySession the user wants a workout they will build as they go.
 * @param onStartFromTemplates the user wants to pick a template first.
 * @param onStartFromTemplate the user picked one from the quick-start strip.
 */
@Composable
fun HomeRoute(
    onResumeSession: (sessionId: Long) -> Unit,
    onStartEmptySession: () -> Unit,
    onStartFromTemplates: () -> Unit,
    onStartFromTemplate: (templateId: Long) -> Unit,
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
        modifier = modifier,
    )
}

/**
 * Where the app lands, and the shortest route from opening it to logging a set.
 *
 * ### What is on it, in the order it matters
 *
 * A workout the app is still holding open comes first and takes the accent, because a user
 * who has one has exactly one thing they want from this screen. Under it, the week — the one
 * figure that answers "am I keeping this up" without opening anything. Then the ways in: two
 * actions, and the templates as a strip for the case where the workout is already decided.
 *
 * ### Nothing renders as an empty shell
 *
 * Each block is drawn only when it has something to say — the rule #110 states for the cards it
 * adds, applied here to the two that exist. A first-launch user gets the quick-start actions
 * and nothing else: no banner, no strip of seven blank squares, no "0 sessions". The screen is
 * allowed to be short.
 *
 * ### The wireframe's other three cards
 *
 * Variant A also draws a streak, a recent PR and eight weeks of volume. All three need engines
 * that do not exist in M4 — #34, #30 and #31 — and #110 adds them once they do. They are
 * absent rather than stubbed, for the reason above.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onEvent: (HomeEvent) -> Unit,
    onResumeSession: (sessionId: Long) -> Unit,
    onStartEmptySession: () -> Unit,
    onStartFromTemplates: () -> Unit,
    onStartFromTemplate: (templateId: Long) -> Unit,
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
            // Nothing above the actions until the reads land. The alternative is a banner and a
            // week strip that appear a frame later and push the buttons down under a thumb
            // already on its way to them.
            if (!state.isLoading) {
                state.resume?.let { resume ->
                    ResumeCard(
                        resume = resume,
                        onResume = { onResumeSession(resume.sessionId) },
                        onDiscard = { onEvent(HomeEvent.DiscardRequested) },
                    )
                }
                state.week?.let { week -> WeekSection(week = week) }
            }

            QuickStartSection(
                templates = state.templates,
                onStartEmptySession = onStartEmptySession,
                onStartFromTemplates = onStartFromTemplates,
                onStartFromTemplate = onStartFromTemplate,
                // No week means nothing has ever been finished — see HomeUiState.week. The
                // reads have to have landed first: the line is about an empty database, not
                // about a query that has not answered.
                isFirstLaunch = !state.isLoading && state.week == null,
            )
        }
    }

    if (state.confirmingDiscard) {
        DiscardSessionDialog(
            loggedSets = state.resume?.loggedSets ?: 0,
            onConfirm = { onEvent(HomeEvent.DiscardConfirmed) },
            onDismiss = { onEvent(HomeEvent.DiscardDismissed) },
            isDiscarding = state.isDiscarding,
        )
    }
}

/** What a message says. Resolved in the screen, so the ViewModel never holds a `Context`. */
@StringRes
internal fun HomeMessage.textRes(): Int = when (this) {
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

/** The screen with its four navigations tied off, so a preview says only what it is showing. */
@Composable
private fun HomePreview(state: HomeUiState) {
    HomeScreen(
        state = state,
        onEvent = {},
        onResumeSession = {},
        onStartEmptySession = {},
        onStartFromTemplates = {},
        onStartFromTemplate = {},
    )
}
