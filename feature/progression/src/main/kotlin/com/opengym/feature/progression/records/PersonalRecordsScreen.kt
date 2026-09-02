package com.opengym.feature.progression.records

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.label
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.records.component.PersonalRecordEntryRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun PersonalRecordsRoute(
    onBack: () -> Unit,
    onOpenExercise: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PersonalRecordsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    PersonalRecordsScreen(
        state = state,
        records = viewModel.records,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onOpenExercise = onOpenExercise,
        modifier = modifier,
    )
}

/**
 * Every personal record, newest first, filterable by kind.
 *
 * ### The list is paged and the state is not
 *
 * The rows arrive as [PagingData] and everything around them as a plain [PersonalRecordsUiState].
 * They are separate parameters rather than one object because a stream of loads is not a value —
 * see [PersonalRecordsUiState], which says the same thing from the other side.
 *
 * ### Two empty states, because they mean different things
 *
 * A user with no records at all is told to go and train. A user whose *filter* matches nothing is
 * told to try another filter — they have records, just not of that kind, and sending them to the
 * gym over a chip they can tap would be the screen misreading its own list. The first is decided
 * by the total, the second by the loaded list; see [PersonalRecordsUiState.hasNoRecords].
 *
 * Neither is drawn while the first page is still loading: a blank screen and a claim that nothing
 * has ever been achieved are different statements, and only one of them is safe before the store
 * has answered.
 *
 * ### And a third state that is neither
 *
 * A failed read is not an empty list. `PersonalRecordsPagingSource` reports a failure as
 * `LoadResult.Error` rather than throwing, precisely so the screen can offer the retry that makes
 * that worth doing — without one, the deliberate choice not to crash only buys a blank page. So a
 * failed refresh draws [RecordsErrorState], and a failed *append* draws a footer under the rows
 * that did load, which are still true and stay on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalRecordsScreen(
    state: PersonalRecordsUiState,
    records: Flow<PagingData<PersonalRecordEntry>>,
    onEvent: (PersonalRecordsEvent) -> Unit,
    onBack: () -> Unit,
    onOpenExercise: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = records.collectAsLazyPagingItems()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // Outside the tab scaffold, like the other progression drill-downs, so the bar spends the
        // system bars' space and the body below does not spend it again.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.records_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.progression_detail_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            RecordsHeader(state = state)
            FilterChips(selected = state.filter, onSelect = { onEvent(PersonalRecordsEvent.FilterSelected(it)) })
            HairlineDivider()

            when {
                state.hasNoRecords -> RecordsEmptyState(
                    title = stringResource(R.string.records_empty_title),
                    body = stringResource(R.string.records_empty_body),
                )

                // Below `hasNoRecords` on purpose. The total comes from its own query, so a user
                // whose count says zero has genuinely set no records whether or not the page read
                // failed, and "no records yet" is the truer of the two things to say.
                items.itemCount == 0 && items.loadState.refresh is LoadState.Error ->
                    RecordsErrorState(onRetry = items::retry)

                // `itemCount` is zero in three different situations: before the first page
                // lands, when a filter matches nothing, and when the load *failed*. Only the
                // middle one is "nothing of that kind" — so the test is that the refresh has
                // settled as `NotLoading`, not merely that it stopped loading. Reading it the
                // other way would tell a user whose query errored that they hold no records of a
                // kind they may well hold plenty of.
                items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading ->
                    RecordsEmptyState(
                        title = stringResource(R.string.records_filtered_empty_title),
                        body = stringResource(R.string.records_filtered_empty_body),
                    )

                else -> RecordsList(items = items, onOpenExercise = onOpenExercise)
            }
        }
    }
}

@Composable
private fun RecordsHeader(
    state: PersonalRecordsUiState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = OpenGymTheme.spacing.screen,
                vertical = OpenGymTheme.spacing.md,
            ),
    ) {
        // The total, not the filtered count: a filter narrows what is listed rather than what the
        // user holds, and a header that fell to "3 records" on a chip tap would be reporting a
        // loss of records they still have.
        Eyebrow(text = pluralStringResource(R.plurals.records_count, state.totalRecords, state.totalRecords))
    }
}

/**
 * One chip per kind, plus `ALL`.
 *
 * All six kinds, not the wireframe's four: #30 writes cardio records, and a distance or pace
 * record with no chip to find it under is a record the user cannot reach. Scrolled horizontally
 * rather than wrapped, so the row's height does not change with the translation.
 */
@Composable
private fun FilterChips(
    selected: PrType?,
    onSelect: (PrType?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                horizontal = OpenGymTheme.spacing.screen,
                vertical = OpenGymTheme.spacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(text = stringResource(R.string.records_filter_all)) },
        )
        PrType.entries.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(text = type.label()) },
            )
        }
    }
}

@Composable
private fun RecordsList(
    items: LazyPagingItems<PersonalRecordEntry>,
    onOpenExercise: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(
            count = items.itemCount,
            // Keyed on the record's own id: a list that re-keys by position loses its scroll
            // anchor every time a page loads above it.
            key = { index -> items.peek(index)?.record?.id ?: index },
        ) { index ->
            val entry = items[index]
            if (entry != null) {
                PersonalRecordEntryRow(
                    entry = entry,
                    onClick = { onOpenExercise(entry.record.exerciseId) },
                )
                HairlineDivider()
            }
        }

        // A page below the ones on screen failed. Without this the list simply stops, which is
        // indistinguishable from having reached the end of the history — the user is told they
        // have no older records rather than that we could not read them.
        if (items.loadState.append is LoadState.Error) {
            item(key = APPEND_ERROR_KEY) {
                RecordsAppendError(onRetry = items::retry)
            }
        }
    }
}

/** A string, so the footer's key cannot collide with a row's, which is always a number. */
private const val APPEND_ERROR_KEY = "append-error"

@Composable
private fun RecordsAppendError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OpenGymTheme.spacing.screen, vertical = OpenGymTheme.spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.records_error_more),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RetryButton(onRetry = onRetry)
    }
}

@Composable
private fun RecordsEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    RecordsMessage(title = title, body = body, modifier = modifier)
}

/**
 * The whole list failed to load, with the one thing the user can do about it.
 *
 * Wears the empty states' layout rather than the shared centred `EmptyState`, so a screen that
 * has three things to say says all three the same way — the difference between them is the
 * sentence and the button, which is the difference that matters.
 */
@Composable
private fun RecordsErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RecordsMessage(
        title = stringResource(R.string.records_error_title),
        body = stringResource(R.string.records_error_body),
        modifier = modifier,
        action = { RetryButton(onRetry = onRetry) },
    )
}

@Composable
private fun RecordsMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.invoke()
    }
}

/** The retry both failure states offer, so they cannot drift into two different buttons. */
@Composable
private fun RetryButton(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onRetry,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Eyebrow(
            text = stringResource(R.string.records_error_action),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Personal records", showBackground = true, heightDp = 700)
@Composable
fun PersonalRecordsScreenPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        PreviewScreen(state = PersonalRecordsUiState(totalRecords = 4, isLoading = false))
    }
}

@Preview(name = "Personal records - dark", showBackground = true, heightDp = 700)
@Composable
fun PersonalRecordsScreenDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        PreviewScreen(state = PersonalRecordsUiState(totalRecords = 4, isLoading = false))
    }
}

@Preview(name = "Personal records - filtered", showBackground = true, heightDp = 700)
@Composable
fun PersonalRecordsScreenFilteredPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        PreviewScreen(
            state = PersonalRecordsUiState(
                filter = PrType.MAX_REPS,
                totalRecords = 4,
                isLoading = false,
            ),
        )
    }
}

@Preview(name = "Personal records - none yet", showBackground = true, heightDp = 700)
@Composable
fun PersonalRecordsScreenEmptyPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        PreviewScreen(
            state = PersonalRecordsUiState(totalRecords = 0, isLoading = false),
            records = flowOf(PagingData.empty()),
        )
    }
}

/**
 * The two failure states, drawn directly rather than through [PersonalRecordsScreen].
 *
 * Which state that screen draws depends on the *load state* of a `PagingData` rather than on a
 * value, and a preview renders before a flow's load states have propagated — so a screen-level
 * failure preview captures the blank list it exists to rule out. `PersonalRecordsScreenTest`
 * covers the wiring; these cover how the states look.
 */
@Preview(name = "Personal records - unreadable", showBackground = true, widthDp = 360)
@Composable
fun PersonalRecordsFailurePreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        FailureCatalogue()
    }
}

@Preview(name = "Personal records - unreadable, dark", showBackground = true, widthDp = 360)
@Composable
fun PersonalRecordsFailureDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        FailureCatalogue()
    }
}

@Composable
private fun FailureCatalogue() {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        RecordsErrorState(onRetry = {})
        HairlineDivider()
        RecordsAppendError(onRetry = {})
    }
}

@Composable
private fun PreviewScreen(
    state: PersonalRecordsUiState,
    records: Flow<PagingData<PersonalRecordEntry>> = flowOf(PagingData.from(previewRecordEntries())),
) {
    PersonalRecordsScreen(
        state = state,
        records = records,
        onEvent = {},
        onBack = {},
        onOpenExercise = {},
    )
}
