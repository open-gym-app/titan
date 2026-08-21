package com.opengym.feature.history.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.OpenGymSearchField
import com.opengym.core.ui.component.OpenGymTabRow
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R
import com.opengym.feature.history.sessions.component.CalendarPane
import com.opengym.feature.history.sessions.component.DateFilterSheet
import com.opengym.feature.history.sessions.component.HistoryList
import com.opengym.feature.history.sessions.component.SessionsHeader
import com.opengym.feature.history.sessions.component.TemplateList
import com.opengym.feature.history.sessions.component.sessionsTabLabels

/**
 * The Sessions tab, wired to its ViewModel.
 *
 * @param onSessionClick a row was tapped. The recap it opens lives in `:feature:session`, so
 *   this is hoisted all the way to `:app` — see `historyGraph`.
 * @param onStartSession the `+ New` action and the empty state's way out. Opens the
 *   new-session flow, which is also another module's.
 */
@Composable
fun SessionsRoute(
    onSessionClick: (sessionId: Long) -> Unit,
    onStartSession: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SessionsScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onSessionClick = onSessionClick,
        onStartSession = onStartSession,
        modifier = modifier,
    )
}

/**
 * Everything the user has done, in the three ways they might go looking for it.
 *
 * ### Three tabs, one destination
 *
 * History, templates and calendar are one screen rather than three, because they are three
 * readings of the same data and the user moves between them constantly — a day tapped on the
 * calendar *is* a filter on the history. As separate destinations that tap would be a
 * navigation with an argument, and the back stack would fill with a screen the user was only
 * passing through.
 *
 * ### What each tab keeps when you leave it
 *
 * Its scroll position, and — since the filters live in the ViewModel — its filters. The three
 * scroll states are remembered *here* rather than inside the panes: only one pane is composed
 * at a time, so a state remembered inside one would be discarded the moment the user switched
 * away from it, which is precisely the thing that has to survive.
 *
 * ### The chrome belongs to the history
 *
 * The search box and the date sheet narrow the history and nothing else, so they are drawn
 * only on that tab — see [SessionsHeader]. The calendar deliberately ignores them: it is a way
 * of reading a month, not a view of the current search.
 */
@Composable
fun SessionsScreen(
    state: SessionsUiState,
    onEvent: (SessionsEvent) -> Unit,
    onSessionClick: (sessionId: Long) -> Unit,
    onStartSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Which controls are open is the screen's own business — nothing outside it acts on the
    // answer, and the ViewModel would be carrying two booleans it could never do anything
    // with. `rememberSaveable` is what makes them survive a rotation anyway.
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var datesOpen by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    // Deliberately *not* saveable, which is the whole of its job: it means "the user just
    // asked for the box", which is only ever true in the composition that handled the tap.
    // See `ExerciseLibraryScreen`, where the same pair is documented at length.
    var searchFocusRequested by remember { mutableStateOf(false) }

    val historyListState = rememberLazyListState()
    val templateListState = rememberLazyListState()
    val calendarScrollState = rememberScrollState()

    LaunchedEffect(searchFocusRequested) {
        if (searchFocusRequested) {
            searchFocus.requestFocus()
            searchFocusRequested = false
        }
    }

    // Collapsing clears the query rather than hiding a box that still holds one, matching the
    // library: the alternative strands the user with a list narrowed to four rows, nothing on
    // screen saying by what, and the control that would say so now closed.
    val onToggleSearch = {
        if (searchExpanded && state.query.isNotEmpty()) {
            onEvent(SessionsEvent.QueryChanged(""))
        }
        searchExpanded = !searchExpanded
        searchFocusRequested = searchExpanded
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // The tab is inside the shell's own `Scaffold`, which has already inset the content
        // for the system bars and padded the `NavHost` by it. Asking again would push the
        // header down twice.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            Column {
                SessionsHeader(
                    state = state,
                    searchExpanded = searchExpanded,
                    onToggleSearch = onToggleSearch,
                    onOpenDates = { datesOpen = true },
                    onNewSession = onStartSession,
                )
                OpenGymTabRow(
                    labels = sessionsTabLabels(),
                    selectedIndex = state.tab.ordinal,
                    onSelect = { index ->
                        onEvent(SessionsEvent.TabSelected(SessionsTab.entries[index]))
                    },
                )
                // Below the tab strip's rule rather than above it: the box belongs to the
                // list it is narrowing, and pushing those rows down is what says so.
                AnimatedVisibility(visible = searchExpanded && state.tab == SessionsTab.HISTORY) {
                    OpenGymSearchField(
                        query = state.query,
                        onQueryChange = { onEvent(SessionsEvent.QueryChanged(it)) },
                        placeholder = stringResource(R.string.sessions_search_placeholder),
                        focusRequester = searchFocus,
                        modifier = Modifier.padding(
                            horizontal = OpenGymTheme.spacing.screen,
                            vertical = OpenGymTheme.spacing.md,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        val content = Modifier.padding(innerPadding)

        when (state.tab) {
            SessionsTab.HISTORY -> HistoryList(
                state = state,
                listState = historyListState,
                onSessionClick = onSessionClick,
                onStartSession = onStartSession,
                onClearFilters = { onEvent(SessionsEvent.SearchAndFiltersCleared) },
                modifier = content,
            )

            SessionsTab.TEMPLATES -> TemplateList(
                state = state,
                listState = templateListState,
                modifier = content,
            )

            SessionsTab.CALENDAR -> CalendarPane(
                page = state.calendar,
                scrollState = calendarScrollState,
                onDayClick = { onEvent(SessionsEvent.CalendarDaySelected(it)) },
                onMonthChange = { onEvent(SessionsEvent.CalendarMonthChanged(it)) },
                modifier = content,
            )
        }
    }

    if (datesOpen) {
        DateFilterSheet(
            filter = state.dateFilter,
            onFilterSelected = { onEvent(SessionsEvent.DateFilterSelected(it)) },
            onDismiss = { datesOpen = false },
        )
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Sessions - history", showBackground = true, heightDp = 720)
@Composable
fun SessionsScreenPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        SessionsScreen(
            state = previewState(),
            onEvent = {},
            onSessionClick = {},
            onStartSession = {},
        )
    }
}

@Preview(name = "Sessions - history, dark", showBackground = true, heightDp = 720)
@Composable
fun SessionsScreenDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        SessionsScreen(
            state = previewState(),
            onEvent = {},
            onSessionClick = {},
            onStartSession = {},
        )
    }
}

@Preview(name = "Sessions - templates", showBackground = true, heightDp = 720)
@Composable
fun SessionsScreenTemplatesPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        SessionsScreen(
            state = previewState(tab = SessionsTab.TEMPLATES),
            onEvent = {},
            onSessionClick = {},
            onStartSession = {},
        )
    }
}

@Preview(name = "Sessions - calendar", showBackground = true, heightDp = 720)
@Composable
fun SessionsScreenCalendarPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        SessionsScreen(
            state = previewState(tab = SessionsTab.CALENDAR),
            onEvent = {},
            onSessionClick = {},
            onStartSession = {},
        )
    }
}

@Preview(name = "Sessions - nothing logged", showBackground = true, heightDp = 720)
@Composable
fun SessionsScreenEmptyPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        SessionsScreen(
            state = SessionsUiState(isLoading = false, isLoadingTemplates = false),
            onEvent = {},
            onSessionClick = {},
            onStartSession = {},
        )
    }
}

@Preview(name = "Sessions - nothing matches", showBackground = true, heightDp = 720)
@Composable
fun SessionsScreenNoResultsPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        SessionsScreen(
            state = previewState().copy(query = "deadlift", months = emptyList(), matchCount = 0),
            onEvent = {},
            onSessionClick = {},
            onStartSession = {},
        )
    }
}

@Preview(name = "Sessions - no templates", showBackground = true, heightDp = 720)
@Composable
fun SessionsScreenNoTemplatesPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        SessionsScreen(
            state = previewState(tab = SessionsTab.TEMPLATES).copy(templates = emptyList()),
            onEvent = {},
            onSessionClick = {},
            onStartSession = {},
        )
    }
}
