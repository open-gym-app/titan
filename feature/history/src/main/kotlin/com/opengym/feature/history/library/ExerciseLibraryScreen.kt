package com.opengym.feature.history.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.EmptyState
import com.opengym.core.ui.component.ExerciseListItem
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.OpenGymSearchField
import com.opengym.core.ui.component.PrValue
import com.opengym.core.ui.component.displayName
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R
import com.opengym.feature.history.library.component.ConfirmSelectionBar
import com.opengym.feature.history.library.component.LibraryFilterSheet
import com.opengym.feature.history.library.component.LibraryHeader
import com.opengym.feature.history.library.component.NewExerciseSheet
import com.opengym.feature.history.library.component.PickerTopBar

/**
 * The exercise library, wired to its ViewModel.
 *
 * @param onBack leave the screen. **Null in the Library tab**, which is not somewhere you come
 *   back from — the bottom bar is the way out, and a back arrow above a tab is a control that
 *   either does nothing or drops the user out of the tab they chose.
 * @param selecting whether the screen is being used as a *picker*. The library and the
 *   exercise picker are the same screen — see the note on [ExerciseLibraryScreen] — and this
 *   is the only thing that differs between them.
 * @param onExerciseClick open an exercise's detail screen. **Null until #94 lands**, which
 *   draws the rows inert rather than giving them a ripple and a click role that lead nowhere.
 * @param onSelectionConfirmed the picker's result: the chosen ids, in the order they were
 *   picked. Never called outside selection mode.
 */
@Composable
fun ExerciseLibraryRoute(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    selecting: Boolean = false,
    onExerciseClick: ((exerciseId: Long) -> Unit)? = null,
    onSelectionConfirmed: (List<Long>) -> Unit = {},
    viewModel: ExerciseLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ExerciseLibraryScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        selecting = selecting,
        onExerciseClick = onExerciseClick,
        onSelectionConfirmed = onSelectionConfirmed,
        modifier = modifier,
    )
}

/**
 * Every exercise the app knows, searchable, filterable, and grouped by what it trains.
 *
 * ### One screen, two jobs
 *
 * Browsing the catalogue and picking exercises for a session are the same screen with
 * [selecting] flipped. They have to be: both want the same search, the same filters and the
 * same grouping, and two implementations of that is how one of them ends up not finding an
 * exercise the other does. What changes is small and local — rows toggle instead of opening,
 * a confirm bar appears, the title says what is being done.
 *
 * ### Two jobs, two sets of chrome
 *
 * The browsing half is a tab, so the shell has already drawn a bar saying "Library" and a
 * bottom bar to leave by: the screen adds only [LibraryHeader], a hairline-ruled strip
 * carrying the count and the three actions, and no back arrow. The picking half is pushed
 * over a workout with no shell around it, so it draws the whole [PickerTopBar] — back arrow,
 * title, count, the same actions — and a confirm bar under the list.
 *
 * ### The controls are chrome, not list items
 *
 * The search box and the two filter rows used to be the first three items of the `LazyColumn`,
 * which made them both expensive and unreachable: 130dp of a 360dp screen while the user was
 * reading rows, and a scroll back through forty of them to change a filter. They are a button
 * each now. Search reveals a box under the bar; filters open a sheet showing every muscle and
 * every kind of kit at once, wrapped rather than in a strip that scrolls sideways past its own
 * options — see [LibraryFilterSheet]. What is left in the list is what the user came to read.
 *
 * ### Why the rows are not in cards
 *
 * `ExerciseListItem` is documented as a row for a list inside one `OpenGymCard`, and this
 * screen deliberately does not do that: a card per section would have to be one `LazyColumn`
 * item holding every one of its rows, and the seeded sections run from eight rows to a
 * hundred and forty-eight — composing all of a section and its images to scroll past two of
 * them is exactly the dropped-frame problem this screen has to avoid across 873 exercises.
 * So every row is its own lazy item, separated by the hairline that would have been inside
 * the card. The design's own rule — separate with a line, not with elevation — holds either
 * way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseLibraryScreen(
    state: ExerciseLibraryUiState,
    onEvent: (ExerciseLibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    selecting: Boolean = false,
    onExerciseClick: ((exerciseId: Long) -> Unit)? = null,
    onSelectionConfirmed: (List<Long>) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message
    val messageText = message?.let { stringResource(it.labelRes()) }

    // Which controls are open is the screen's own business — nothing outside it acts on the
    // answer, and the ViewModel would be carrying two booleans it could never do anything
    // with. `rememberSaveable` is what makes them survive a rotation anyway.
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    // Deliberately *not* saveable, which is the whole of its job: it means "the user just
    // asked for the box", which is only ever true in the composition that handled the tap.
    // Coming back to the tab restores `searchExpanded` and starts this false, so the box
    // returns open with the keyboard left down — see the effect below.
    var searchFocusRequested by remember { mutableStateOf(false) }

    LaunchedEffect(message, messageText) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            onEvent(ExerciseLibraryEvent.MessageShown)
        }
    }

    // Opening the box puts the caret in it. A search control that takes two taps to type into
    // is a search control the user stops reaching for.
    //
    // Keyed on the *asking* rather than on `searchExpanded`, which is restored state and so
    // cannot tell "just opened" from "was already open". Keyed on that instead, leaving the
    // tab with the search open and the keyboard dismissed and coming back re-ran this and
    // threw the keyboard back over the results the user had gone to read.
    LaunchedEffect(searchFocusRequested) {
        if (searchFocusRequested) {
            searchFocus.requestFocus()
            searchFocusRequested = false
        }
    }

    /*
     * Collapsing clears the query, rather than hiding a box that still holds one.
     *
     * The alternative strands the user: results narrowed to twelve rows, nothing on screen
     * saying by what, and the control that would say so now closed. One rule instead — the
     * box is open exactly when there is a search — and closing it visibly returns the list,
     * which is its own explanation.
     */
    val onToggleSearch = {
        if (searchExpanded && state.query.isNotEmpty()) {
            onEvent(ExerciseLibraryEvent.QueryChanged(""))
        }
        searchExpanded = !searchExpanded
        searchFocusRequested = searchExpanded
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The picker owns the window; the tab is inside the shell's own `Scaffold`, which has
        // already inset the content for the status and navigation bars and padded the
        // `NavHost` by it. Asking for them again would push the header down twice.
        contentWindowInsets = if (selecting) {
            ScaffoldDefaults.contentWindowInsets
        } else {
            WindowInsets(left = 0, top = 0, right = 0, bottom = 0)
        },
        topBar = {
            Column {
                if (selecting) {
                    PickerTopBar(
                        state = state,
                        searchExpanded = searchExpanded,
                        onBack = onBack,
                        onToggleSearch = onToggleSearch,
                        onOpenFilters = { filtersOpen = true },
                        onNewExercise = { onEvent(ExerciseLibraryEvent.NewExerciseRequested) },
                    )
                } else {
                    LibraryHeader(
                        state = state,
                        searchExpanded = searchExpanded,
                        onToggleSearch = onToggleSearch,
                        onOpenFilters = { filtersOpen = true },
                        onNewExercise = { onEvent(ExerciseLibraryEvent.NewExerciseRequested) },
                    )
                }

                // Below the bar's rule rather than inside it: the box belongs to the list it
                // is narrowing, and pushing the rows down is what says so.
                AnimatedVisibility(visible = searchExpanded) {
                    OpenGymSearchField(
                        query = state.query,
                        onQueryChange = { onEvent(ExerciseLibraryEvent.QueryChanged(it)) },
                        placeholder = stringResource(R.string.library_search_placeholder),
                        focusRequester = searchFocus,
                        modifier = Modifier.padding(
                            horizontal = OpenGymTheme.spacing.screen,
                            vertical = OpenGymTheme.spacing.md,
                        ),
                    )
                }
            }
        },
        bottomBar = {
            if (selecting) {
                ConfirmSelectionBar(
                    count = state.selection.size,
                    onConfirm = { onSelectionConfirmed(state.selection) },
                )
            }
        },
    ) { innerPadding ->
        LibraryList(
            state = state,
            onEvent = onEvent,
            selecting = selecting,
            onExerciseClick = onExerciseClick,
            modifier = Modifier.padding(innerPadding),
        )
    }

    if (filtersOpen) {
        LibraryFilterSheet(
            state = state,
            onEvent = onEvent,
            onDismiss = { filtersOpen = false },
        )
    }

    val draft = state.draft
    if (draft != null) {
        NewExerciseSheet(
            draft = draft,
            muscleGroups = state.muscleGroups,
            onDraftChange = { onEvent(ExerciseLibraryEvent.NewExerciseChanged(it)) },
            onSubmit = { onEvent(ExerciseLibraryEvent.NewExerciseSubmitted) },
            onDismiss = { onEvent(ExerciseLibraryEvent.NewExerciseDismissed) },
        )
    }
}

/**
 * The results, and nothing else.
 *
 * The search box and the filters used to be the first three items of this list, which is what
 * made them cost 130dp of a 360dp screen *and* be unreachable from row forty. Both are chrome
 * now — see [ExerciseLibraryScreen] — so what scrolls here is only what the user came to read.
 */
@Composable
private fun LibraryList(
    state: ExerciseLibraryUiState,
    onEvent: (ExerciseLibraryEvent) -> Unit,
    selecting: Boolean,
    onExerciseClick: ((exerciseId: Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        if (state.sections.isEmpty()) {
            // Nothing is drawn while the first read is in flight: a spinner for a query over
            // 873 local rows is a flash, and an empty state under it would be a lie.
            if (!state.isLoading) {
                item(key = EMPTY_KEY) {
                    LibraryEmptyState(
                        hasFilters = state.hasFilters,
                        onClearFilters = {
                            onEvent(ExerciseLibraryEvent.SearchAndFiltersCleared)
                        },
                    )
                }
            }
            return@LazyColumn
        }

        state.sections.forEach { section ->
            item(key = "section-${section.muscleGroup?.id ?: UNLINKED_SECTION_KEY}") {
                SectionHeader(section = section)
            }
            items(
                items = section.exercises,
                key = { it.exercise.id },
            ) { item ->
                ExerciseRow(
                    item = item,
                    selecting = selecting,
                    selected = item.exercise.id in state.selection,
                    onToggle = { onEvent(ExerciseLibraryEvent.ExerciseToggled(item.exercise.id)) },
                    onClick = onExerciseClick,
                )
                HairlineDivider()
            }
        }

        // Air under the last row, so the final divider is not flush against the bottom of
        // the window (or, in selection mode, against the confirm bar).
        item(key = FOOTER_KEY) {
            Spacer(modifier = Modifier.height(OpenGymTheme.spacing.section))
        }
    }
}

/**
 * `CHEST · 12`, and a rule running out to the margin.
 *
 * The rule is what makes a heading in a flat list read as a heading. Without it the eyebrow
 * is a short line of small text above another line of text, and at a glance between sets that
 * is not a section break.
 */
@Composable
private fun SectionHeader(section: ExerciseSection, modifier: Modifier = Modifier) {
    val name = section.muscleGroup?.displayName() ?: stringResource(R.string.library_section_unlinked)
    // One node rather than a name beside a count: they are one heading, and a screen reader
    // that read them as two would say "chest" and "dot twelve" three swipes apart.
    val heading = stringResource(R.string.library_section_header, name, section.exercises.size)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = OpenGymTheme.spacing.screen,
                end = OpenGymTheme.spacing.screen,
                top = OpenGymTheme.spacing.section,
                bottom = OpenGymTheme.spacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Eyebrow(text = heading, color = MaterialTheme.colorScheme.onSurface)
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * One exercise.
 *
 * In selection mode the *row* carries the toggle rather than the checkbox: a 44dp-high row is
 * a far better target than a 24dp box at the end of it, and giving the checkbox its own click
 * would leave two overlapping controls saying the same thing to a screen reader.
 */
@Composable
private fun ExerciseRow(
    item: LibraryExercise,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: ((exerciseId: Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ExerciseListItem(
        exercise = item.exercise,
        muscleGroups = item.muscleGroups,
        // Empty rather than null: the library has not read anybody's history, and null would
        // tell every user they have never done any of these — see `ExerciseListItem`.
        lastPerformed = "",
        onClick = if (selecting) null else onClick?.let { { it(item.exercise.id) } },
        trailing = {
            when {
                selecting -> Checkbox(checked = selected, onCheckedChange = null)
                item.record != null -> PrValue(record = item.record)
            }
        },
        modifier = if (selecting) {
            modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
        } else {
            modifier
        },
    )
}

/**
 * The two empty states, which are not the same state.
 *
 * A filtered library that matches nothing has a way out and is offered it. An unfiltered one
 * that is empty means the seed did not run, which the user can do nothing about — so it says
 * what happened rather than offering a button that would not help.
 */
@Composable
private fun LibraryEmptyState(
    hasFilters: Boolean,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hasFilters) {
        EmptyState(
            glyph = stringResource(R.string.library_glyph_search),
            title = stringResource(R.string.library_no_results_title),
            description = stringResource(R.string.library_no_results_description),
            actionLabel = stringResource(R.string.library_clear_filters),
            action = onClearFilters,
            modifier = modifier,
        )
    } else {
        EmptyState(
            glyph = stringResource(R.string.library_glyph_empty),
            title = stringResource(R.string.library_empty_title),
            description = stringResource(R.string.library_empty_description),
            modifier = modifier,
        )
    }
}

private fun ExerciseLibraryMessage.labelRes(): Int = when (this) {
    ExerciseLibraryMessage.EXERCISE_CREATED -> R.string.library_exercise_created
    ExerciseLibraryMessage.SAVE_FAILED -> R.string.library_save_failed
}

/* Stable keys for the two items that are not rows. */
private const val EMPTY_KEY = "empty"
private const val FOOTER_KEY = "footer"

/** The section for exercises the catalogue links to no muscle. Never collides with a row id. */
private const val UNLINKED_SECTION_KEY = "unlinked"

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private const val PREVIEW_BENCH_ID = 1L
private const val PREVIEW_FLY_ID = 2L
private const val PREVIEW_INCLINE_ID = 3L
private const val PREVIEW_PULL_UP_ID = 4L
private const val PREVIEW_ROW_ID = 5L

private fun previewGroup(id: Long, name: String) = MuscleGroup(id = id, name = name)

private val PREVIEW_CHEST = previewGroup(id = 1, name = "CHEST")
private val PREVIEW_TRICEPS = previewGroup(id = 2, name = "TRICEPS")
private val PREVIEW_LATS = previewGroup(id = 3, name = "LATS")

private val PREVIEW_TAXONOMY = listOf(PREVIEW_CHEST, PREVIEW_LATS, PREVIEW_TRICEPS)

private fun previewExercise(
    id: Long,
    name: String,
    equipment: EquipmentType,
    primary: MuscleGroup,
    secondary: MuscleGroup? = null,
    record: PersonalRecord? = null,
) = LibraryExercise(
    exercise = Exercise(id = id, name = name, equipmentType = equipment),
    muscleGroups = listOfNotNull(
        ExerciseMuscleGroupLink(primary, isPrimary = true),
        secondary?.let { ExerciseMuscleGroupLink(it, isPrimary = false) },
    ),
    record = record,
)

/**
 * What the seed ships, so the preview's eyebrow reads like the real screen's.
 *
 * One constant for every preview in the feature — `LibraryFilterSheet`'s included — because
 * two copies of a figure that tracks `assets/seed/exercises.sql` is how one of them ends up
 * being the stale one somebody reasons from.
 */
internal const val SEEDED_CATALOGUE_SIZE = 873

private const val BENCH_PR_MILLIGRAMS = 92_000_000L
private const val INCLINE_PR_MILLIGRAMS = 28_000_000L

private fun previewRecord(exerciseId: Long, weight: Long) = PersonalRecord(
    id = exerciseId,
    exerciseId = exerciseId,
    workoutSetId = exerciseId,
    type = PrType.MAX_WEIGHT,
    weight = weight,
    achievedAt = 0,
)

private val PREVIEW_STATE = ExerciseLibraryUiState(
    muscleGroups = PREVIEW_TAXONOMY,
    equipmentTypes = listOf(EquipmentType.BARBELL, EquipmentType.DUMBBELL, EquipmentType.CABLE),
    sections = listOf(
        ExerciseSection(
            muscleGroup = PREVIEW_CHEST,
            exercises = listOf(
                previewExercise(
                    id = PREVIEW_BENCH_ID,
                    name = "Barbell bench press",
                    equipment = EquipmentType.BARBELL,
                    primary = PREVIEW_CHEST,
                    secondary = PREVIEW_TRICEPS,
                    record = previewRecord(PREVIEW_BENCH_ID, BENCH_PR_MILLIGRAMS),
                ),
                previewExercise(PREVIEW_FLY_ID, "Cable fly", EquipmentType.CABLE, PREVIEW_CHEST),
                previewExercise(
                    id = PREVIEW_INCLINE_ID,
                    name = "Incline dumbbell press",
                    equipment = EquipmentType.DUMBBELL,
                    primary = PREVIEW_CHEST,
                    record = previewRecord(PREVIEW_INCLINE_ID, INCLINE_PR_MILLIGRAMS),
                ),
            ),
        ),
        ExerciseSection(
            muscleGroup = PREVIEW_LATS,
            exercises = listOf(
                previewExercise(
                    id = PREVIEW_PULL_UP_ID,
                    name = "Pull-up",
                    equipment = EquipmentType.BODYWEIGHT,
                    primary = PREVIEW_LATS,
                ),
                previewExercise(PREVIEW_ROW_ID, "Barbell row", EquipmentType.BARBELL, PREVIEW_LATS),
            ),
        ),
    ),
    catalogueSize = SEEDED_CATALOGUE_SIZE,
    matchCount = 5,
    isLoading = false,
)

/*
 * The tab is the default shape, so it is what most of these render: no back arrow, and the
 * count and the three actions in the header the shell's bar sits above on a device. The
 * search box and the filter sheet are closed, which is how the screen opens; the sheet has
 * its own previews next to the panel that draws it.
 */

@Preview(name = "Exercise library", showBackground = true)
@Composable
fun ExerciseLibraryScreenPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseLibraryScreen(state = PREVIEW_STATE, onEvent = {})
    }
}

@Preview(name = "Exercise library - dark", showBackground = true)
@Composable
fun ExerciseLibraryScreenDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        ExerciseLibraryScreen(state = PREVIEW_STATE, onEvent = {})
    }
}

@Preview(name = "Exercise library - selecting", showBackground = true)
@Composable
fun ExerciseLibraryScreenSelectingPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseLibraryScreen(
            state = PREVIEW_STATE.copy(selection = listOf(PREVIEW_BENCH_ID, PREVIEW_PULL_UP_ID)),
            onEvent = {},
            // The picker, which is the one shape that still has a way back at the top of it.
            onBack = {},
            selecting = true,
        )
    }
}

@Preview(name = "Exercise library - filtered", showBackground = true)
@Composable
fun ExerciseLibraryScreenFilteredPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseLibraryScreen(
            // A filter set and nothing matching it, which is the state the tinted funnel in
            // the bar exists for: it is the only thing on screen saying why the list is empty.
            state = PREVIEW_STATE.copy(
                selectedMuscleGroupId = PREVIEW_LATS.id,
                selectedEquipment = setOf(EquipmentType.CABLE),
                sections = emptyList(),
                matchCount = 0,
            ),
            onEvent = {},
        )
    }
}

@Preview(name = "Exercise library - empty catalogue", showBackground = true)
@Composable
fun ExerciseLibraryScreenEmptyPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseLibraryScreen(state = ExerciseLibraryUiState(isLoading = false), onEvent = {})
    }
}
