package com.opengym.feature.history.template

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import com.opengym.core.domain.model.SetType
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
import kotlinx.coroutines.flow.first
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
        // Held until the ViewModel will take it, rather than dispatched and forgotten.
        // `addExercises` drops an answer that arrives before the read has landed or while a
        // write is out, and the clearing below throws away the only copy of what was picked —
        // with the picker already popped behind it. The same wait the workout screen makes.
        viewModel.uiState.first { !it.isLoading && !it.isSaving }
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
 * SAVE, at the bottom of the screen and the full width of it.
 *
 * ### Why it is not a top-bar action
 *
 * It was one, and the top-right corner of a 6.3in phone is the far corner of the thumb's
 * reach — the "ow" zone. Writing the plan is the whole point of this page and the only thing
 * the user came here to finish, so it belongs in the natural zone at the bottom, where the
 * picker's own confirm bar and the new-exercise sheet's `Create` already put theirs. The top
 * bar keeps what suits a hard-to-reach corner: leaving, which is rare and which nobody should
 * hit by accident.
 *
 * Filled and in the accent, because it is that call to action; disabled — greyed by Material,
 * container and label together — until there is something to write. `ConfirmSelectionBar`'s
 * shape exactly: a hairline over a full-width button on the screen's own margin, so the two
 * bars a user meets one after the other in this flow read as the same control.
 *
 * No `navigationBarsPadding` here, unlike the picker's: this screen is inside the tab shell,
 * which has already inset its content and drawn its own bar under this one.
 */
@Composable
private fun SaveBar(enabled: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Button(
            onClick = onSave,
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(OpenGymTheme.spacing.screen),
        ) {
            Text(
                text = stringResource(R.string.template_editor_save),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
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
        // This screen is inside the tab shell, whose own `Scaffold` has already inset the
        // content for the status and navigation bars and padded the `NavHost` by it. Asking
        // for them again would push the bar down twice — the same rule `SessionsScreen` and
        // the library tab follow.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (state.isNew) R.string.template_editor_new_title else R.string.template_editor_title,
                        ),
                    )
                },
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
                // No action up here. SAVE is this page's one call to action and it lives at
                // the bottom, in reach — see [SaveBar].
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        // Only over the editor itself: there is nothing to save while the read is out, and a
        // template that is not there has a dead end and a way out rather than a form.
        bottomBar = {
            if (state.isEditorDrawn) {
                SaveBar(
                    enabled = state.canSave,
                    onSave = { onEvent(TemplateEditorEvent.SaveRequested) },
                )
            }
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
            isNew = state.isNew,
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
            // The scaffold's own bottom inset *plus* the breathing room, not instead of it:
            // the save bar is part of that inset, and a list that ignored it would scroll its
            // last exercise underneath the button that saves it.
            bottom = contentPadding.calculateBottomPadding() + OpenGymTheme.spacing.section,
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
                            onSetFieldChange = { setKey, field, text ->
                                onEvent(
                                    TemplateEditorEvent.SetFieldChanged(row.key, setKey, field, text),
                                )
                            },
                            onSetTypeChange = { setKey, setType ->
                                onEvent(TemplateEditorEvent.SetTypeChanged(row.key, setKey, setType))
                            },
                            onSetAdd = { onEvent(TemplateEditorEvent.SetAdded(row.key)) },
                            onSetRemove = { setKey ->
                                onEvent(TemplateEditorEvent.SetRemoved(row.key, setKey))
                            },
                            onRestChange = { onEvent(TemplateEditorEvent.RestChanged(row.key, it)) },
                            onRemove = { onEvent(TemplateEditorEvent.ExerciseRemoved(row.key)) },
                            unitSystem = state.unitSystem,
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
                    // The group's own name, off the row rather than sliced out of its `A1`
                    // label: the label's format belongs to `SupersetLabels`, and its numeric
                    // fallback past the twenty-sixth group is all digits, which a slice on the
                    // first digit reads as an empty name.
                    groupLabel = row.supersetGroupName,
                    exerciseName = row.exercise?.name
                        ?: stringResource(R.string.template_exercise_removed),
                    nextExerciseName = next.exercise?.name
                        ?: stringResource(R.string.template_exercise_removed),
                    onClick = {
                        onEvent(
                            if (row.isLinkedToNext) {
                                TemplateEditorEvent.SupersetUnlinked(row.key)
                            } else {
                                TemplateEditorEvent.SupersetLinked(row.key)
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
 *
 * A template being built has no name until the user gives it one, and they can dismiss the
 * dialog that asks without answering. The placeholder stands in for that, because the row is the
 * only way back into the dialog and a row with an empty title is one there is nothing to press.
 *
 * Inert while a write is out, like every other control on the page: it is the one that is not a
 * field or a button, and a heading that stayed live would be the way into the draft the rest of
 * the screen has just been sealed off from.
 */
@Composable
private fun TemplateHeader(state: TemplateEditorUiState, onEvent: (TemplateEditorEvent) -> Unit) {
    val summary = state.summary
    val untitled = stringResource(R.string.template_editor_untitled)

    TemplateRow(
        summary = if (state.name.isBlank()) {
            summary.copy(template = summary.template.copy(name = untitled))
        } else {
            summary
        },
        // Null rather than a no-op: `TemplateRow` draws no ripple and takes no click role for
        // one, so the row stops offering itself instead of offering and refusing.
        onClick = if (state.isEditable) {
            { onEvent(TemplateEditorEvent.NameEditRequested) }
        } else {
            null
        },
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
    TemplateEditorMessage.EDITOR_RESTARTED -> R.string.template_editor_restarted
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
                    if (row.key != PREVIEW_REFUSED_KEY) return@map row
                    // One set of the plan, not the whole card: an error belongs to the figure
                    // that was typed wrong, and the rows around it stay perfectly good.
                    row.copy(
                        sets = row.sets.mapIndexed { index, set ->
                            if (index == 1) {
                                set.copy(reps = "0", errors = setOf(TemplateSetField.REPS))
                            } else {
                                set
                            }
                        },
                    )
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

/** Opened on nothing, with the dialog that asks what it is called already up over it. */
@Preview(name = "Template editor - new", showBackground = true, heightDp = 900)
@Composable
fun TemplateEditorScreenNewPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        TemplateEditorScreen(
            state = TemplateEditorUiState(
                isNew = true,
                isLoading = false,
                naming = TemplateNamingDraft(name = ""),
            ),
            onEvent = {},
            onExit = {},
        )
    }
}

/** The same, with the dialog dismissed unanswered — which is what the placeholder is for. */
@Preview(name = "Template editor - new, unnamed", showBackground = true, heightDp = 900)
@Composable
fun TemplateEditorScreenNewUnnamedPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        TemplateEditorScreen(
            state = TemplateEditorUiState(isNew = true, isLoading = false),
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

/**
 * The row the "edited" preview shows refused.
 *
 * The first card, because the plan is a scrolling list and a refusal drawn on the third one is a
 * reference screenshot of a page with nothing wrong on it.
 */
private const val PREVIEW_REFUSED_KEY = 1L

/** A push day with its last two exercises paired up. */
private fun previewState() = TemplateEditorUiState(
    name = "Push Day · Upper",
    category = TemplateCategory.STRENGTH,
    estimatedDuration = 52.minutes,
    isLoading = false,
    exercises = listOf(
        // A warm-up, two working sets and a drop set — the plan the old four-cell card could
        // not express, which is why the preview leads with it.
        previewRow(
            key = 1L,
            name = "Bench press",
            sets = listOf(
                previewSet(key = 1L, setType = SetType.WARMUP, weight = "40", reps = "12"),
                previewSet(key = 2L, weight = "80", reps = "8"),
                previewSet(key = 3L, weight = "80", reps = "8"),
                previewSet(key = 4L, setType = SetType.DROP_SET, weight = "60", reps = "10"),
            ),
        ),
        previewRow(
            key = 2L,
            name = "Incline dumbbell press",
            sets = previewPlan(firstKey = 10L, count = 3, weight = "28", reps = "10"),
        ),
        previewRow(
            key = 3L,
            name = "Cable fly",
            sets = previewPlan(firstKey = 20L, count = 3, weight = "15", reps = "12"),
            supersetGroup = 0,
            supersetLabel = "A1",
            supersetGroupName = "A",
            isLinkedToNext = true,
        ),
        previewRow(
            key = 4L,
            name = "Tricep pushdown",
            sets = previewPlan(firstKey = 30L, count = 3, weight = "25", reps = "12"),
            supersetGroup = 0,
            supersetLabel = "A2",
            supersetGroupName = "A",
        ),
    ),
)

/** [count] identical working sets, keyed from [firstKey], for a card whose plan is not the point. */
private fun previewPlan(firstKey: Long, count: Int, weight: String, reps: String) =
    List(count) { previewSet(key = firstKey + it, weight = weight, reps = reps) }

private fun previewSet(
    key: Long,
    setType: SetType = SetType.NORMAL,
    weight: String = "",
    reps: String = "",
) = TemplateSetRow(key = key, setType = setType, weight = weight, reps = reps)

@Suppress("LongParameterList")
private fun previewRow(
    key: Long,
    name: String,
    sets: List<TemplateSetRow> = emptyList(),
    rest: String = "90",
    supersetGroup: Int? = null,
    supersetLabel: String? = null,
    supersetGroupName: String? = null,
    isLinkedToNext: Boolean = false,
) = TemplateExerciseRow(
    key = key,
    exerciseId = key,
    exercise = Exercise(id = key, name = name),
    sets = sets,
    rest = rest,
    supersetGroup = supersetGroup,
    supersetLabel = supersetLabel,
    supersetGroupName = supersetGroupName,
    isLinkedToNext = isLinkedToNext,
)
