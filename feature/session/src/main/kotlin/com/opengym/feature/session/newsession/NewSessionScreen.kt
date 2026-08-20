package com.opengym.feature.session.newsession

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.ui.component.EmptyState
import com.opengym.core.ui.component.ExerciseListItem
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.component.SessionNameDialog
import com.opengym.core.ui.component.TemplateRow
import com.opengym.core.ui.format.formatAsEstimate
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.session.R
import com.opengym.feature.session.newsession.component.ResumeSessionDialog
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The new-session flow, wired to its ViewModel.
 *
 * @param onCancel the user left without starting anything. The flow is over and nothing was
 *   written.
 * @param onSessionOpened the workout should open. Called once, from the state — see
 *   [NewSessionUiState.openSessionId], which is set both by a start that succeeded and by the
 *   already-running session being picked back up. The two land in the same place, because in
 *   both cases this is the workout the user is now in.
 * @param onPickExercises open the exercise picker. It is a destination in another feature, so
 *   this screen reports that the user wants to pick and `:app` decides what that opens.
 * @param pickedExerciseIds the picker's answer on the way back, or null when it has not
 *   answered. Applied once and then handed back through [onPickedExercisesHandled] so a
 *   recomposition does not re-apply it.
 */
@Composable
fun NewSessionRoute(
    onCancel: () -> Unit,
    onSessionOpened: (sessionId: Long) -> Unit,
    onPickExercises: () -> Unit,
    modifier: Modifier = Modifier,
    pickedExerciseIds: List<Long>? = null,
    onPickedExercisesHandled: () -> Unit = {},
    viewModel: NewSessionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Consumed here rather than in the ViewModel because the ids are a *navigation* result:
    // `:app` holds them while the picker pops, and the ViewModel has no business knowing
    // that a back stack is how the answer arrived.
    LaunchedEffect(pickedExerciseIds) {
        if (pickedExerciseIds != null) {
            viewModel.onEvent(NewSessionEvent.ExercisesSelected(pickedExerciseIds))
            onPickedExercisesHandled()
        }
    }

    // The picker is pushed over this destination, so this screen being resumed *is* the
    // picker being gone — and it is the only signal that covers every way out of it. Only
    // the confirm bar leaves an answer behind; the back arrow, the system gesture and a
    // predictive-back swipe leave nothing, and a cancel handled through the answer alone
    // would strand the screen in [NewSessionStep.ExerciseSelection] with its quick-start
    // action disabled for good.
    //
    // Ordering against the effect above does not matter: a plan, once adopted, outranks this
    // flag in [NewSessionUiState.step], so clearing it either side of a confirmed selection
    // reads the same.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(NewSessionEvent.ExerciseSelectionClosed)
    }

    LaunchedEffect(state.openSessionId) {
        state.openSessionId?.let(onSessionOpened)
    }

    // The review panel is a step within one destination, so the system back gesture has to
    // mean "back to the list" there rather than "leave the flow" — otherwise a user
    // correcting a mistapped template is dropped out of the flow entirely. Here rather than
    // in [NewSessionScreen] because `BackHandler` needs an `OnBackPressedDispatcherOwner`,
    // which a `@Preview` does not provide.
    BackHandler(enabled = state.step is NewSessionStep.ReadyToStart) {
        viewModel.onEvent(NewSessionEvent.PlanDiscarded)
    }

    NewSessionScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onCancel = onCancel,
        onPickExercises = onPickExercises,
        modifier = modifier,
    )
}

/**
 * Choose what to train: start blank, or start from something saved.
 *
 * ### One destination, three faces
 *
 * The quick-start action and the template list are the same face; the picker covers it while
 * it is open; and confirming either path replaces it with the review panel. They are not
 * three destinations, because only the first is somewhere the user can be *sent back to* —
 * see [NewSessionStep]. The consequence worth knowing is that the back arrow and the back
 * gesture mean different things at different steps, and both are resolved here rather than
 * by the shell.
 *
 * ### Why the review panel exists at all
 *
 * The wireframe starts a template on one tap. This does not, and the difference is the
 * mistap: a session started by accident cannot simply be dismissed, because it is a row in
 * the database and a state machine that refuses a second one until it is finished or
 * discarded. One screen of "this is what you are about to do" costs a tap and removes that
 * whole class of mess.
 *
 * @param onPickExercises open the picker. Paired with an event rather than replacing it: the
 *   ViewModel records that the picker is up, and this reports where "up" is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(
    state: NewSessionUiState,
    onEvent: (NewSessionEvent) -> Unit,
    onCancel: () -> Unit,
    onPickExercises: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val step = state.step
    state.nameDraft?.let { draft ->
        SessionNameDialog(
            name = draft,
            onNameChange = { onEvent(NewSessionEvent.NameChanged(it)) },
            onConfirm = { onEvent(NewSessionEvent.NameConfirmed) },
            onDismiss = { onEvent(NewSessionEvent.NameEditCancelled) },
        )
    }

    // A refused start, asked rather than reported — see [ResumeSessionDialog]. It is drawn
    // over whichever face is showing, because the refusal is about the workout the user is
    // already in rather than about the plan they were assembling.
    if (state.runningSessionId != null) {
        ResumeSessionDialog(
            onConfirm = { onEvent(NewSessionEvent.ResumeRequested) },
            onDismiss = { onEvent(NewSessionEvent.ResumeDismissed) },
        )
    }

    val message = state.message?.let { stringResource(it.labelRes()) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onEvent(NewSessionEvent.MessageShown)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            NewSessionTopBar(
                reviewing = step is NewSessionStep.ReadyToStart,
                // The arrow means "back one step" on the review panel and "leave" everywhere
                // else, which is the whole of what makes the panel a step rather than a
                // place.
                onBack = {
                    if (step is NewSessionStep.ReadyToStart) {
                        onEvent(NewSessionEvent.PlanDiscarded)
                    } else {
                        onCancel()
                    }
                },
            )
        },
        bottomBar = {
            if (step is NewSessionStep.ReadyToStart) {
                StartBar(
                    enabled = step.canStart && !state.isStarting,
                    onStart = { onEvent(NewSessionEvent.SessionStartRequested) },
                )
            }
        },
    ) { innerPadding ->
        when (step) {
            is NewSessionStep.ReadyToStart -> ReadyToStartList(
                onRename = { onEvent(NewSessionEvent.NameEditRequested) },
                step = step,
                onRemove = { index -> onEvent(NewSessionEvent.ExerciseRemoved(index)) },
                contentPadding = innerPadding,
            )

            NewSessionStep.TemplateSelection, NewSessionStep.ExerciseSelection -> TemplateList(
                state = state,
                // Disabled while the picker is already up: two taps dispatched through a
                // slow transition would otherwise push a second picker over the first.
                pickingExercises = step is NewSessionStep.ExerciseSelection,
                onPickExercises = {
                    onEvent(NewSessionEvent.ExerciseSelectionOpened)
                    onPickExercises()
                },
                onTemplateClick = { id -> onEvent(NewSessionEvent.TemplateSelected(id)) },
                contentPadding = innerPadding,
            )
        }
    }
}

/**
 * The flow's own bar. There is no shell above it — the session flow hides both of the app's
 * bars — so this is the only chrome the screen has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSessionTopBar(reviewing: Boolean, onBack: () -> Unit) {
    Column {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(
                            if (reviewing) R.string.new_session_back_to_list else R.string.new_session_leave,
                        ),
                    )
                }
            },
            title = {
                Text(
                    text = stringResource(R.string.new_session_title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        // The wireframes rule off every bar rather than floating it on elevation. A hairline
        // is legible in a gym; a 1dp shadow is not.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The quick-start action over the saved templates — where the flow opens. */
@Composable
private fun TemplateList(
    state: NewSessionUiState,
    pickingExercises: Boolean,
    onPickExercises: () -> Unit,
    onTemplateClick: (templateId: Long) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Column(
                modifier = Modifier.padding(
                    horizontal = OpenGymTheme.spacing.screen,
                    vertical = OpenGymTheme.spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
            ) {
                SectionHeading(text = stringResource(R.string.new_session_quick_start))
                EmptySessionAction(enabled = !pickingExercises, onClick = onPickExercises)
            }
        }

        // Nothing at all while the read is in flight. An empty state with a call to action
        // is a poor thing to flash at a user who has six templates, and a spinner over a
        // list that arrives in one frame is worse than a beat of nothing.
        if (state.isLoading) return@LazyColumn

        if (state.templates.isEmpty()) {
            item { NoTemplates(onPickExercises = onPickExercises) }
            return@LazyColumn
        }

        item {
            SectionHeading(
                text = stringResource(R.string.new_session_templates),
                trailing = {
                    Eyebrow(
                        text = pluralStringResource(
                            R.plurals.new_session_template_count,
                            state.templates.size,
                            state.templates.size,
                        ),
                    )
                },
                modifier = Modifier.padding(
                    start = OpenGymTheme.spacing.screen,
                    end = OpenGymTheme.spacing.screen,
                    bottom = OpenGymTheme.spacing.md,
                ),
            )
        }

        itemsIndexed(state.templates, key = { _, summary -> summary.template.id }) { index, summary ->
            if (index > 0) HairlineDivider()
            TemplateRow(summary = summary, onClick = { onTemplateClick(summary.template.id) })
        }
    }
}

/**
 * The wireframe's accent-filled quick start.
 *
 * The one place on this screen the accent is spent, because it is the one action that is
 * always available — a user with no templates, or none that fit today, has this and nothing
 * else. The template rows underneath are deliberately quiet by comparison.
 */
@Composable
private fun EmptySessionAction(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(OpenGymTheme.spacing.xxl),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(imageVector = Icons.Outlined.Add, contentDescription = null)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = OpenGymTheme.spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.new_session_empty),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.new_session_empty_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * What the screen says before the user has ever saved a template.
 *
 * It names where templates come from, because nothing on this screen creates one — they are
 * authored by saving a finished session (#92) — and an empty list with no explanation reads
 * as a feature that is broken rather than one that has not been used yet.
 */
@Composable
private fun NoTemplates(onPickExercises: () -> Unit) {
    EmptyState(
        glyph = "▤",
        title = stringResource(R.string.new_session_no_templates_title),
        description = stringResource(R.string.new_session_no_templates_body),
        actionLabel = stringResource(R.string.new_session_empty),
        action = onPickExercises,
    )
}

/** The review panel: the workout as it will be started. */
@Composable
private fun ReadyToStartList(
    onRename: () -> Unit,
    step: NewSessionStep.ReadyToStart,
    onRemove: (index: Int) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item { ReadyToStartHeader(step = step, onRename = onRename) }

        itemsIndexed(step.exercises) { index, row ->
            if (index > 0) HairlineDivider()
            ExerciseListItem(
                exercise = row.exercise,
                muscleGroups = row.muscleGroups,
                // Empty, not null: this screen has not asked history anything, and null is
                // how a row says "never done" — see `ExerciseListItem`. Telling a lifter
                // they have never benched, on the screen where they are about to, is worse
                // than saying nothing. #101 is what fills this in.
                lastPerformed = "",
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                    ) {
                        if (row.supersetLabel != null) {
                            OpenGymBadge(
                                text = stringResource(R.string.new_session_superset, row.supersetLabel),
                            )
                        }
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(
                                    R.string.new_session_remove,
                                    row.exercise.name,
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }
    }
}

/** What the session will be called, and what it costs. */
@Composable
private fun ReadyToStartHeader(step: NewSessionStep.ReadyToStart, onRename: () -> Unit) {
    val count = pluralStringResource(
        R.plurals.new_session_exercise_count,
        step.exercises.size,
        step.exercises.size,
    )

    Column(
        modifier = Modifier.padding(
            horizontal = OpenGymTheme.spacing.screen,
            vertical = OpenGymTheme.spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
    ) {
        Eyebrow(
            text = stringResource(R.string.new_session_ready),
            color = MaterialTheme.colorScheme.primary,
        )
        val rename = stringResource(R.string.new_session_rename)
        Text(
            // A session built by hand starts out genuinely unnamed, so this is a placeholder
            // for a null rather than a name being set. Tapping it is where the name comes from
            // — the earliest of the three moments a session can be titled, the other two being
            // the workout's own header and the recap.
            text = step.name ?: stringResource(R.string.new_session_unnamed),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clickable(onClick = onRename)
                .semantics { onClick(label = rename, action = null) },
        )
        Text(
            text = when (val estimate = step.estimatedDuration) {
                null -> count
                else -> stringResource(
                    R.string.new_session_ready_meta,
                    count,
                    estimate.formatAsEstimate(),
                )
            },
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one button that writes anything on this screen.
 *
 * Pinned rather than at the end of the list, for the same reason the picker's confirm is: a
 * twelve-exercise session would otherwise put the commit below the fold of the thing being
 * committed to. The navigation-bar inset is asked for rather than assumed — see the note on
 * the picker's own confirm bar.
 */
@Composable
private fun StartBar(enabled: Boolean, onStart: () -> Unit) {
    Column(modifier = Modifier.navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Button(
            onClick = onStart,
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(OpenGymTheme.spacing.screen),
        ) {
            Text(
                text = stringResource(R.string.new_session_start),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** The sentence a message is shown as. */
private fun NewSessionMessage.labelRes(): Int = when (this) {
    NewSessionMessage.START_FAILED -> R.string.new_session_error_start_failed
    NewSessionMessage.TEMPLATE_UNAVAILABLE -> R.string.new_session_error_template
    NewSessionMessage.EXERCISES_UNAVAILABLE -> R.string.new_session_error_exercises_missing
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "New session", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun NewSessionScreenPreview() {
    PreviewScreen(NewSessionUiState(templates = previewTemplates(), isLoading = false))
}

@Preview(name = "New session - dark", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun NewSessionScreenDarkPreview() {
    PreviewScreen(
        state = NewSessionUiState(templates = previewTemplates(), isLoading = false),
        theme = ThemePreference.DARK,
    )
}

@Preview(name = "New session - no templates", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun NewSessionScreenEmptyPreview() {
    PreviewScreen(NewSessionUiState(isLoading = false))
}

@Preview(name = "New session - ready from a template", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun NewSessionScreenReadyPreview() {
    PreviewScreen(
        NewSessionUiState(
            templates = previewTemplates(),
            isLoading = false,
            step = NewSessionStep.ReadyToStart(
                name = "Push Day · Upper",
                estimatedDuration = 55.minutes,
                exercises = listOf(
                    previewRow("Barbell bench press"),
                    previewRow("Incline dumbbell press", supersetLabel = "A1"),
                    previewRow("Cable fly", supersetLabel = "A2"),
                    previewRow("Overhead press"),
                    previewRow("Triceps pushdown"),
                ),
            ),
        ),
    )
}

@Preview(name = "New session - ready from scratch", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun NewSessionScreenReadyEmptyStartPreview() {
    PreviewScreen(
        NewSessionUiState(
            isLoading = false,
            step = NewSessionStep.ReadyToStart(
                name = null,
                estimatedDuration = null,
                exercises = listOf(previewRow("Back squat"), previewRow("Romanian deadlift")),
            ),
        ),
    )
}

@Composable
private fun PreviewScreen(
    state: NewSessionUiState,
    theme: ThemePreference = ThemePreference.LIGHT,
) {
    OpenGymTheme(theme = theme) {
        NewSessionScreen(state = state, onEvent = {}, onCancel = {}, onPickExercises = {})
    }
}

private fun previewTemplates(): List<TemplateSummary> = listOf(
    previewTemplate(
        id = 1L,
        name = "Push Day · Upper",
        exerciseCount = 8,
        duration = 55.minutes,
        category = TemplateCategory.STRENGTH,
    ),
    previewTemplate(
        id = 2L,
        name = "Pull Day",
        exerciseCount = 7,
        duration = 50.minutes,
        category = TemplateCategory.STRENGTH,
    ),
    previewTemplate(
        id = 3L,
        name = "Leg Day · Heavy",
        exerciseCount = 6,
        duration = 62.minutes,
        category = TemplateCategory.HYPERTROPHY,
    ),
    previewTemplate(
        id = 4L,
        name = "Full Body Beginner",
        exerciseCount = 5,
        duration = 40.minutes,
        category = TemplateCategory.GENERAL,
    ),
    previewTemplate(
        id = 5L,
        name = "HIIT Cardio",
        exerciseCount = 3,
        duration = 28.minutes,
        category = TemplateCategory.ENDURANCE,
    ),
)

private fun previewTemplate(
    id: Long,
    name: String,
    exerciseCount: Int,
    duration: Duration,
    category: TemplateCategory,
) = TemplateSummary(
    template = WorkoutTemplate(id = id, name = name, category = category),
    exerciseCount = exerciseCount,
    estimatedDuration = duration,
)

private fun previewRow(name: String, supersetLabel: String? = null) = PlannedExerciseRow(
    exercise = Exercise(id = name.hashCode().toLong(), name = name),
    supersetLabel = supersetLabel,
)

/**
 * A `@Preview` renders at the device's height and silently clips a screen with a pinned bar
 * at the bottom, so the captures are given a phone's worth of height explicitly.
 */
private const val FULL_SCREEN_HEIGHT_DP = 720
