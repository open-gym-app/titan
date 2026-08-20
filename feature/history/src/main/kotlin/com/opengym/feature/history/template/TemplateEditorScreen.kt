package com.opengym.feature.history.template

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.CollapsedReorderRow
import com.opengym.core.ui.component.EmptyState
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.ReorderState
import com.opengym.core.ui.component.ReorderableItem
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.component.SupersetLink
import com.opengym.core.ui.component.TemplateNameDialog
import com.opengym.core.ui.component.TemplateRow
import com.opengym.core.ui.component.rememberReorderState
import com.opengym.core.ui.component.reorderable
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R
import com.opengym.feature.history.template.component.DiscardTemplateEditsDialog
import com.opengym.feature.history.template.component.TemplateExerciseCard
import kotlin.time.Duration.Companion.minutes

/**
 * The template editor, wired to its ViewModel.
 *
 * @param onExit the screen is done with — saved, discarded, or opened on a template that is not
 *   there. Reported from the state rather than from a callback, so a navigation cannot be fired
 *   again on every recomposition until the pop actually happens.
 * @param onPickExercises push the exercise picker. It lives outside the tab scaffold, so the push
 *   is `:app`'s — see `historyGraph`.
 * @param pickedExerciseIds what the picker came back with, or null when it has not answered.
 * @param onPickedExercisesHandled the answer has been applied and should be forgotten, so a
 *   recomposition or a process restore does not append the same exercises twice.
 */
@Composable
fun TemplateEditorRoute(
    onExit: () -> Unit,
    onPickExercises: () -> Unit,
    modifier: Modifier = Modifier,
    pickedExerciseIds: List<Long>? = null,
    onPickedExercisesHandled: () -> Unit = {},
    viewModel: TemplateEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Consumed here rather than in the ViewModel because the ids are a *navigation* result:
    // `:app` holds them while the picker pops, and the ViewModel has no business knowing that a
    // back stack is how the answer arrived. The same shape the session flow uses.
    LaunchedEffect(pickedExerciseIds) {
        val picked = pickedExerciseIds ?: return@LaunchedEffect
        viewModel.onEvent(TemplateEditorEvent.ExercisesPicked(picked))
        onPickedExercisesHandled()
    }

    // The picker is pushed over this destination, so this screen being resumed *is* the picker
    // being gone — and it is the only signal that covers every way out of it. Only the confirm
    // bar leaves an answer behind; the back arrow, the system gesture and a predictive-back
    // swipe leave nothing, and a cancel handled through the answer alone would leave the "add
    // exercises" button disabled for good. The same shape the new-session flow uses.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(TemplateEditorEvent.ExercisesPickClosed)
    }

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onExit()
    }

    TemplateEditorScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onExit = onExit,
        onPickExercises = onPickExercises,
        modifier = modifier,
    )
}

/**
 * A saved plan, opened to be changed: reorder it, pair exercises up, price what a set should be.
 *
 * ### The shape
 *
 * The template's own row at the top — the same [TemplateRow] the tab and the picker draw, so the
 * name, the count and the estimate are read the same way everywhere — and under it the plan as
 * one scrolling document, a card per exercise with the join between each pair in the gap.
 *
 * The header is tappable, and that is the only way to rename: a title that is also the control
 * is what the workout's own header does, and it saves a second control saying "rename" over a
 * name that is already on screen. Tapping it opens the same `TemplateNameDialog` the tab's
 * rename uses, so the two cannot ask differently.
 *
 * ### Nothing is written until SAVE
 *
 * Every edit here is a change to a draft — see [TemplateEditorUiState] — which is what makes the
 * back gesture ask before throwing it away, and what makes SAVE inert until something has
 * actually changed. The alternative, writing as the user goes, would rewrite the whole exercise
 * list on every keystroke.
 *
 * ### Supersets and the order
 *
 * Both are the workout screen's, and deliberately so: holding an exercise picks it up and the
 * page collapses to one line per exercise while it is held, and the gap between two cards is
 * where a superset is made and unmade. A template's grouping *is* the session's grouping — it is
 * copied across when the template is applied — so editing it anywhere else would be a second
 * vocabulary for the same fact.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TemplateEditorScreen(
    state: TemplateEditorUiState,
    onEvent: (TemplateEditorEvent) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onPickExercises: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val message = state.message
    val messageText = message?.let { stringResource(it.labelRes()) }

    LaunchedEffect(message, messageText) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            onEvent(TemplateEditorEvent.MessageShown)
        }
    }

    // The system gesture goes through the same question the back arrow does. Enabled only while
    // there is something to lose, so a screen nobody has changed keeps the platform's own back.
    BackHandler(enabled = state.isDirty && !state.isSaving) {
        onEvent(TemplateEditorEvent.BackRequested)
    }

    // The order as it is being dragged. Screen-local and seeded from the draft, which is what
    // makes it honest: the key is the draft's order, so anything that lands — the drag's own
    // commit, an exercise added, one removed — re-seeds it. Between gestures the two are equal.
    val draftOrder = state.exercises.map(TemplateExerciseRow::key)
    var order by remember(draftOrder) { mutableStateOf(draftOrder) }

    // Not `rememberSaveable`: a held exercise cannot survive the process that is holding it, and
    // a list that came back collapsed with nothing under the finger would be a mode with no way
    // out.
    val reorder = rememberReorderState(
        itemCount = state.exercises.size,
        onMove = { from, to -> order = order.toMutableList().apply { add(to, removeAt(from)) } },
        onCommit = { onEvent(TemplateEditorEvent.ExercisesReordered(order)) },
        // Nothing to reorder in a plan of one, and nothing to reorder at all while a write is
        // out or the read has not landed.
        enabled = state.isEditable && state.exercises.size > 1,
    )

    // Drawn in the order being dragged rather than the draft's, so the page under the finger is
    // the page the finger is making.
    val exercises = order.mapNotNull { key -> state.exercises.firstOrNull { it.key == key } }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.template_editor_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            // Asked first only where there is something to lose; the screen
                            // leaves on its own otherwise, which is what the flag decides.
                            if (state.isDirty) onEvent(TemplateEditorEvent.BackRequested) else onExit()
                        },
                        enabled = !state.isSaving,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.template_editor_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onEvent(TemplateEditorEvent.SaveRequested) },
                        enabled = state.canSave,
                    ) {
                        Eyebrow(text = stringResource(R.string.template_editor_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> Unit

            state.isMissing -> EmptyState(
                glyph = stringResource(R.string.sessions_templates_glyph),
                title = stringResource(R.string.template_editor_missing_title),
                description = stringResource(R.string.template_editor_missing_body),
                actionLabel = stringResource(R.string.template_editor_missing_action),
                action = onExit,
                modifier = Modifier.padding(innerPadding),
            )

            else -> TemplateEditorBody(
                state = state,
                exercises = exercises,
                reorder = reorder,
                onEvent = onEvent,
                onPickExercises = onPickExercises,
                contentPadding = innerPadding,
            )
        }
    }

    state.naming?.let { naming ->
        TemplateNameDialog(
            name = naming.name,
            onNameChange = { onEvent(TemplateEditorEvent.NameChanged(it)) },
            onConfirm = { onEvent(TemplateEditorEvent.NameConfirmed) },
            onDismiss = { onEvent(TemplateEditorEvent.NameEditCancelled) },
            title = stringResource(R.string.template_editor_name_title),
            confirmLabel = stringResource(R.string.template_editor_name_confirm),
            category = naming.category,
            onCategoryChange = { onEvent(TemplateEditorEvent.CategoryChanged(it)) },
            error = stringResource(R.string.template_editor_name_taken).takeIf { naming.nameTaken },
        )
    }

    if (state.confirmingDiscard) {
        DiscardTemplateEditsDialog(
            onConfirm = { onEvent(TemplateEditorEvent.DiscardConfirmed) },
            onDismiss = { onEvent(TemplateEditorEvent.DiscardDismissed) },
        )
    }
}

/**
 * The plan itself.
 *
 * Everything that is not an exercise goes while one is held. What is left is the template as a
 * list of names, which is the shortest it can be drawn and the whole reason a drag across it is
 * one movement — see [CollapsedReorderRow].
 */
@Composable
private fun TemplateEditorBody(
    state: TemplateEditorUiState,
    exercises: List<TemplateExerciseRow>,
    reorder: ReorderState,
    onEvent: (TemplateEditorEvent) -> Unit,
    onPickExercises: () -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            // The gesture lives on the list rather than on the cards, because the cards move —
            // see `Modifier.reorderable`.
            .reorderable(reorder),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = OpenGymTheme.spacing.section,
            start = OpenGymTheme.spacing.screen,
            end = OpenGymTheme.spacing.screen,
        ),
        // Tighter while the plan is collapsed: the gap between two cards separates one exercise's
        // figures from the next, and between two title rows it is only air.
        verticalArrangement = Arrangement.spacedBy(
            if (reorder.isActive) OpenGymTheme.spacing.md else OpenGymTheme.spacing.lg,
        ),
    ) {
        if (!reorder.isActive) {
            item(key = HEADER_KEY) {
                TemplateHeader(state = state, onEvent = onEvent)
            }

            item(key = SECTION_KEY) {
                SectionHeading(text = stringResource(R.string.template_editor_exercises))
            }

            if (exercises.isEmpty()) {
                item(key = EMPTY_KEY) {
                    EmptyState(
                        title = stringResource(R.string.template_editor_empty_title),
                        description = stringResource(R.string.template_editor_empty_body),
                    )
                }
            }
        }

        exercises.forEachIndexed { index, row ->
            item(key = "exercise-${row.key}") {
                ReorderableItem(state = reorder, key = row.key, index = index) { isHeld ->
                    if (reorder.isActive) {
                        CollapsedReorderRow(
                            position = index,
                            name = row.exercise?.name
                                ?: stringResource(R.string.template_exercise_removed),
                            isHeld = isHeld,
                            supersetLabel = row.supersetLabel,
                        )
                    } else {
                        TemplateExerciseCard(
                            row = row,
                            // The place it is being dragged to rather than the one in the draft,
                            // which is a frame behind while an order is being applied.
                            position = index,
                            onFieldChange = { field, text ->
                                onEvent(TemplateEditorEvent.FieldChanged(row.key, field, text))
                            },
                            onRemove = { onEvent(TemplateEditorEvent.ExerciseRemoved(row.key)) },
                            enabled = state.isEditable,
                        )
                    }
                }
            }

            // The join to the exercise below, which is only a thing where there is one. The last
            // exercise has nothing to be performed back to back with, so it gets no control
            // rather than a disabled one that would have to explain itself. Gone while an
            // exercise is held: nothing on the page can be pressed by a finger already holding
            // something.
            val next = exercises.getOrNull(index + 1) ?: return@forEachIndexed
            if (reorder.isActive) return@forEachIndexed
            item(key = "join-${row.key}") {
                SupersetLink(
                    isLinked = row.isLinkedToNext,
                    // `A1` is this exercise's place in group `A`; the pill names the group.
                    groupLabel = row.supersetLabel?.takeWhile { !it.isDigit() },
                    exerciseName = row.exercise?.name
                        ?: stringResource(R.string.template_exercise_removed),
                    nextExerciseName = next.exercise?.name
                        ?: stringResource(R.string.template_exercise_removed),
                    onClick = {
                        onEvent(
                            if (row.isLinkedToNext) {
                                TemplateEditorEvent.SupersetUnlinked(index)
                            } else {
                                TemplateEditorEvent.SupersetLinked(index)
                            },
                        )
                    },
                    enabled = state.isEditable,
                )
            }
        }

        if (!reorder.isActive) {
            item(key = ADD_KEY) {
                OutlinedButton(
                    onClick = {
                        onEvent(TemplateEditorEvent.ExercisesPickRequested)
                        onPickExercises()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.isEditable && !state.isPickingExercises,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                        Eyebrow(text = stringResource(R.string.template_editor_add_exercises))
                    }
                }
            }
        }
    }
}

/**
 * The template's own row, which is also the way to rename it.
 *
 * The count and the estimate on it are the *draft's* — see [TemplateEditorUiState.summary] — so
 * dropping an exercise repaints the header that is describing the list underneath it.
 */
@Composable
private fun TemplateHeader(state: TemplateEditorUiState, onEvent: (TemplateEditorEvent) -> Unit) {
    TemplateRow(
        summary = state.summary,
        onClick = { onEvent(TemplateEditorEvent.NameEditRequested) },
        trailing = {
            // A glyph rather than a button: the whole row is the target, and a second one inside
            // it would be two things to hit for one action. It is what says the row is not just
            // a heading.
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** The sentence a message is shown as. */
private fun TemplateEditorMessage.labelRes(): Int = when (this) {
    TemplateEditorMessage.EXERCISES_UNAVAILABLE -> R.string.template_editor_exercises_unavailable
    TemplateEditorMessage.SAVE_FAILED -> R.string.template_editor_save_failed
}

/* Stable keys for the items that are not exercises. */
private const val HEADER_KEY = "header"
private const val SECTION_KEY = "section"
private const val EMPTY_KEY = "empty"
private const val ADD_KEY = "add"

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Template editor", showBackground = true, heightDp = 900)
@Composable
fun TemplateEditorScreenPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        TemplateEditorScreen(state = previewState(), onEvent = {}, onExit = {})
    }
}

@Preview(name = "Template editor - dark", showBackground = true, heightDp = 900)
@Composable
fun TemplateEditorScreenDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        TemplateEditorScreen(state = previewState(), onEvent = {}, onExit = {})
    }
}

@Preview(name = "Template editor - edited", showBackground = true, heightDp = 900)
@Composable
fun TemplateEditorScreenEditedPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        TemplateEditorScreen(
            state = previewState().copy(
                isDirty = true,
                exercises = previewState().exercises.map { row ->
                    if (row.key == PREVIEW_REFUSED_KEY) {
                        row.copy(reps = "0", errors = setOf(TemplateField.REPS))
                    } else {
                        row
                    }
                },
            ),
            onEvent = {},
            onExit = {},
        )
    }
}

@Preview(name = "Template editor - emptied out", showBackground = true, heightDp = 900)
@Composable
fun TemplateEditorScreenEmptyPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        TemplateEditorScreen(
            state = previewState().copy(exercises = emptyList(), isDirty = true),
            onEvent = {},
            onExit = {},
        )
    }
}

@Preview(name = "Template editor - no such template", showBackground = true, heightDp = 900)
@Composable
fun TemplateEditorScreenMissingPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        TemplateEditorScreen(
            state = TemplateEditorUiState(isLoading = false, isMissing = true),
            onEvent = {},
            onExit = {},
        )
    }
}

/** The row the "edited" preview shows refused, which is the first member of the superset. */
private const val PREVIEW_REFUSED_KEY = 3L

/** A push day with its last two exercises paired up. */
private fun previewState() = TemplateEditorUiState(
    name = "Push Day · Upper",
    category = TemplateCategory.STRENGTH,
    estimatedDuration = 52.minutes,
    isLoading = false,
    exercises = listOf(
        previewRow(1L, "Bench press", sets = "4", reps = "8", weight = "80"),
        previewRow(2L, "Incline dumbbell press", sets = "3", reps = "10", weight = "28"),
        previewRow(
            key = 3L,
            name = "Cable fly",
            sets = "3",
            reps = "12",
            weight = "15",
            supersetGroup = 0,
            supersetLabel = "A1",
            isLinkedToNext = true,
        ),
        previewRow(
            key = 4L,
            name = "Tricep pushdown",
            sets = "3",
            reps = "12",
            weight = "25",
            supersetGroup = 0,
            supersetLabel = "A2",
        ),
    ),
)

@Suppress("LongParameterList")
private fun previewRow(
    key: Long,
    name: String,
    sets: String = "",
    reps: String = "",
    weight: String = "",
    rest: String = "90",
    supersetGroup: Int? = null,
    supersetLabel: String? = null,
    isLinkedToNext: Boolean = false,
) = TemplateExerciseRow(
    key = key,
    exerciseId = key,
    exercise = Exercise(id = key, name = name),
    sets = sets,
    reps = reps,
    weight = weight,
    rest = rest,
    supersetGroup = supersetGroup,
    supersetLabel = supersetLabel,
    isLinkedToNext = isLinkedToNext,
)
