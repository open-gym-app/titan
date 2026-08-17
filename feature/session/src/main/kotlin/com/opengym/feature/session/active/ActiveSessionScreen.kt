package com.opengym.feature.session.active

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.SessionStats
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.ui.component.EmptyState
import com.opengym.core.ui.component.SessionHeaderBar
import com.opengym.core.ui.component.SessionNameDialog
import com.opengym.core.ui.component.SessionProgress
import com.opengym.core.ui.component.SessionStatChips
import com.opengym.core.ui.format.formatSessionDate
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.session.R
import com.opengym.feature.session.active.component.DiscardSessionDialog
import com.opengym.feature.session.active.component.SessionExerciseCard
import com.opengym.feature.session.active.component.SupersetLink
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The workout, wired to its ViewModel.
 *
 * @param onFinishSession the workout ended and should be recapped, with the session it produced.
 * @param onDiscardSession the workout was thrown away. A different exit from [onFinishSession]
 *   and not a special case of it: there is no recap of a session that no longer exists, so the
 *   two land in different places and `:app` resolves both.
 * @param onPickExercises push the exercise picker. What that opens lives in another feature
 *   module, so it is `:app`'s to resolve — see `ExercisePicker`.
 * @param pickedExerciseIds what the picker came back with, or null when it has not answered.
 * @param onPickedExercisesHandled the answer has been applied and should be forgotten, so a
 *   recomposition or a process restore does not append the same exercises twice.
 */
@Composable
fun ActiveSessionRoute(
    onFinishSession: (sessionId: Long) -> Unit,
    onDiscardSession: () -> Unit,
    onPickExercises: () -> Unit,
    modifier: Modifier = Modifier,
    pickedExerciseIds: List<Long>? = null,
    onPickedExercisesHandled: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    viewModel: ActiveSessionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Consumed here rather than in the ViewModel because the ids are a *navigation* result:
    // `:app` holds them while the picker pops, and the ViewModel has no business knowing that a
    // back stack is how the answer arrived. The same shape the new-session flow uses.
    LaunchedEffect(pickedExerciseIds) {
        if (pickedExerciseIds != null) {
            viewModel.onEvent(ActiveSessionEvent.ExercisesPicked(pickedExerciseIds))
            onPickedExercisesHandled()
        }
    }

    // Reported once the state says so, rather than from the ViewModel: the write has landed by
    // the time the flag is set, and a navigation driven from a callback would fire again on
    // every recomposition until the pop actually happened.
    LaunchedEffect(state.finishedSessionId) {
        state.finishedSessionId?.let(onFinishSession)
    }
    LaunchedEffect(state.isDiscarded) {
        if (state.isDiscarded) onDiscardSession()
    }

    ActiveSessionScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onPickExercises = onPickExercises,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * Log a set into any exercise, pair them up, end the workout.
 *
 * ### The shape
 *
 * A dark header that says this is a mode, and under it the session as one scrolling document:
 * every exercise in the order it is performed, each with its sets and a row to type the next one
 * into. The alternative — one set at a time, big numbers — reads better in a screenshot and is
 * worse in a gym: the question between sets is "what did I do last set", and answering it should
 * not cost a navigation.
 *
 * Nothing is folded and nothing is selected. An earlier version of this screen had one exercise
 * open and the rest collapsed to a line, which cost the user their own session — and made
 * supersets unrepresentable, since two exercises performed back to back are alternated set for
 * set and only one of them could take a set. The page is long as a result. That is the trade:
 * scrolling is cheap, and a workout you have to tap through is one you cannot check.
 *
 * ### Supersets
 *
 * Between each pair of cards sits the join between them — [SupersetLink] — which is where a
 * superset is made and unmade. The grouping itself is a column on `session_exercise` and the
 * rules over it are `Supersets`'; this screen only ever asks to link two *neighbours*, which is
 * the one request that cannot produce an illegal grouping.
 *
 * ### Where the accent is spent
 *
 * On the row being typed, its tick and COMPLETE SET — on every card, since every card has one —
 * and on the superset joins, which are the other thing the page is *about*. The cards, the stat
 * chips and every rule around them are neutral, so a glance from arm's length lands on a box to
 * type in rather than on a border.
 *
 * ### Two failures, drawn two different ways
 *
 * A refused *set* appears under the row that caused it, because the value that was refused is
 * still in the field above it. A refused *finish* appears above the header's END, because the
 * thing that was refused is off in the corner and the answer to it — log a set, or discard — is
 * on this screen rather than in a snackbar that fades.
 */
@Composable
fun ActiveSessionScreen(
    state: ActiveSessionUiState,
    onEvent: (ActiveSessionEvent) -> Unit,
    modifier: Modifier = Modifier,
    onPickExercises: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    // Which of the two the message is drawn as. The zero-sets refusal is a state of the screen
    // rather than an event that passed through it, so it stays on the page.
    val inlineMessage = state.message?.takeIf { it == ActiveSessionMessage.NO_SETS_LOGGED }
    val transient = state.message?.takeUnless { it == ActiveSessionMessage.NO_SETS_LOGGED }
    val transientText = transient?.let { stringResource(it.labelRes()) }

    LaunchedEffect(transientText) {
        if (transientText != null) {
            snackbarHostState.showSnackbar(transientText)
            onEvent(ActiveSessionEvent.MessageShown)
        }
    }

    // Which card's rating scale is open, by `session_exercise` id, or null for none. Screen-local
    // and rightly so: whether a scale is open is not a fact about the workout, and a ViewModel
    // holding it would be a scale that reopened itself on every recomposition of a screen the
    // user had closed it on. `rememberSaveable` is what carries it across a configuration change,
    // which is the only thing it does need to survive.
    //
    // One at a time, for the same reason the scale is inline rather than in a sheet: it is nine
    // cells tall, and two of them open would push the second exercise's table off the screen.
    var rpeScaleFor by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            SessionHeaderBar(
                name = state.header.title(),
                elapsed = state.header?.elapsed ?: Duration.ZERO,
                progress = state.header?.progress ?: SessionProgress(),
                isPaused = state.isPaused,
                onPauseToggle = { onEvent(ActiveSessionEvent.PauseToggled) },
                onFinish = { onEvent(ActiveSessionEvent.FinishRequested) },
                onDiscard = { onEvent(ActiveSessionEvent.DiscardRequested) },
                // The name is the affordance, so a workout started from the quick action can be
                // titled from the one place its title is already shown.
                onNameClick = { onEvent(ActiveSessionEvent.NameEditRequested) },
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> Unit

            state.isMissing -> EmptyState(
                title = stringResource(R.string.active_missing_title),
                description = stringResource(R.string.active_missing_body),
                modifier = Modifier.padding(innerPadding),
            )

            else -> SessionBody(
                state = state,
                inlineMessage = inlineMessage,
                onEvent = onEvent,
                onPickExercises = onPickExercises,
                rpeScaleFor = rpeScaleFor,
                onRpeToggle = { id -> rpeScaleFor = id.takeIf { it != rpeScaleFor } },
                contentPadding = innerPadding,
            )
        }
    }

    state.nameDraft?.let { draft ->
        SessionNameDialog(
            name = draft,
            onNameChange = { onEvent(ActiveSessionEvent.NameChanged(it)) },
            onConfirm = { onEvent(ActiveSessionEvent.NameConfirmed) },
            onDismiss = { onEvent(ActiveSessionEvent.NameEditCancelled) },
            isSaving = state.isRenaming,
        )
    }

    if (state.confirmingDiscard) {
        DiscardSessionDialog(
            loggedSets = state.stats?.totalSets ?: 0,
            onConfirm = { onEvent(ActiveSessionEvent.DiscardConfirmed) },
            onDismiss = { onEvent(ActiveSessionEvent.DiscardDismissed) },
            isDiscarding = state.isDiscarding,
        )
    }
}

@Composable
private fun SessionBody(
    state: ActiveSessionUiState,
    inlineMessage: ActiveSessionMessage?,
    onEvent: (ActiveSessionEvent) -> Unit,
    onPickExercises: () -> Unit,
    rpeScaleFor: Long?,
    onRpeToggle: (Long) -> Unit,
    contentPadding: PaddingValues,
) {
    // The entry row goes read-only while the session is suspended or on its way out.
    // `LogSetUseCase` refuses a set logged into a paused session, so a field that still took
    // keystrokes would be collecting them for a write that cannot happen.
    val enabled = !state.isPaused && !state.isBusy

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + OpenGymTheme.spacing.screen,
            bottom = OpenGymTheme.spacing.section,
            start = OpenGymTheme.spacing.screen,
            end = OpenGymTheme.spacing.screen,
        ),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        state.stats?.let { stats ->
            item(key = "stats") {
                // Without the duration: the header's clock is directly above it, and the same
                // number twice on one screen is one of them being ignored.
                SessionStatChips(stats = stats, showDuration = false)
            }
        }

        if (inlineMessage != null) {
            item(key = "message") {
                Text(
                    text = stringResource(inlineMessage.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.exercises.isEmpty()) {
            item(key = "no-exercises") {
                EmptyState(
                    title = stringResource(R.string.active_no_exercises_title),
                    description = stringResource(R.string.active_no_exercises_body),
                )
            }
        }

        state.exercises.forEachIndexed { index, row ->
            item(key = "exercise-${row.sessionExerciseId}") {
                SessionExerciseCard(
                    row = row,
                    onEvent = onEvent,
                    onRemove = { onEvent(ActiveSessionEvent.ExerciseRemoved(row.sessionExerciseId)) },
                    showRpeScale = rpeScaleFor == row.sessionExerciseId,
                    onRpeToggle = { onRpeToggle(row.sessionExerciseId) },
                    enabled = enabled,
                    isLogging = state.loggingExerciseId == row.sessionExerciseId,
                )
            }

            // The join to the exercise below, which is only a thing where there is one. The last
            // exercise has nothing to be performed back to back with, so it gets no control
            // rather than a disabled one that would have to explain itself.
            val next = state.exercises.getOrNull(index + 1) ?: return@forEachIndexed
            item(key = "join-${row.sessionExerciseId}") {
                SupersetLink(
                    isLinked = row.isLinkedToNext,
                    // `A1` is this exercise's place in group `A`; the pill names the group.
                    groupLabel = row.supersetLabel?.takeWhile { !it.isDigit() },
                    exerciseName = row.name(),
                    nextExerciseName = next.name(),
                    onClick = {
                        onEvent(
                            if (row.isLinkedToNext) {
                                ActiveSessionEvent.SupersetUnlinked(row.sessionExerciseId)
                            } else {
                                ActiveSessionEvent.SupersetLinked(row.sessionExerciseId)
                            },
                        )
                    },
                    enabled = enabled && !state.isRegrouping,
                )
            }
        }

        item(key = "add-exercises") {
            // At the bottom, under everything the workout already holds, because that is where
            // what it adds will land — an action at the top would append to the far end of a
            // list the user would then have to scroll to find.
            OutlinedButton(
                onClick = {
                    onEvent(ActiveSessionEvent.AddExercisesRequested)
                    onPickExercises()
                },
                // Gated on the pause like every other control: `AddSessionExercisesUseCase`
                // refuses a session that is not active, so a live button here would walk the
                // user through the whole picker to refuse what they chose at the end of it.
                enabled = enabled && !state.isAddingExercises,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = OpenGymTheme.spacing.md),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = OpenGymTheme.spacing.sm),
                )
                Text(
                    text = stringResource(R.string.active_add_exercises),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The session's title: what the user called it, or the day it happened.
 *
 * The fallback is the date rather than a word like "Workout", matching the recap: a session
 * started empty is identified by when it was, and `workout_session.name` being null means
 * genuinely unnamed rather than named badly.
 */
@Composable
private fun ActiveSessionHeader?.title(): String = when {
    this == null -> ""
    !name.isNullOrBlank() -> name
    else -> formatSessionDate(startedAt)
}

/**
 * What an exercise is called, for the places that have to say it in a sentence.
 *
 * The fallback is the same one the card draws, so a screen reader announcing the join above an
 * exercise deleted from the library says what the card above it says.
 */
@Composable
private fun SessionExerciseRow.name(): String =
    exercise?.name ?: stringResource(R.string.active_exercise_removed)

/** The words a message is said in. */
@StringRes
private fun ActiveSessionMessage.labelRes(): Int = when (this) {
    ActiveSessionMessage.NO_SETS_LOGGED -> R.string.active_error_no_sets
    ActiveSessionMessage.FINISH_FAILED -> R.string.active_error_finish
    ActiveSessionMessage.DISCARD_FAILED -> R.string.active_error_discard
    ActiveSessionMessage.SET_REJECTED -> R.string.active_error_set
    ActiveSessionMessage.PAUSE_FAILED -> R.string.active_error_pause
    ActiveSessionMessage.ADD_EXERCISES_FAILED -> R.string.active_error_add
    ActiveSessionMessage.EXERCISE_HAS_SETS -> R.string.active_error_exercise_has_sets
    ActiveSessionMessage.REMOVE_EXERCISE_FAILED -> R.string.active_error_remove
    ActiveSessionMessage.RENAME_FAILED -> R.string.active_error_rename
    ActiveSessionMessage.SUPERSET_FAILED -> R.string.active_error_superset
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

// Taller than a phone on purpose: the page is the whole workout now, and a preview cut off at
// 480dp would document one card and none of the joins between them.
@Preview(name = "Active session", showBackground = true, heightDp = 1800)
@Composable
fun ActiveSessionScreenPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ActiveSessionScreen(state = previewState(), onEvent = {})
    }
}

@Preview(name = "Active session, dark", showBackground = true)
@Composable
fun ActiveSessionScreenDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        ActiveSessionScreen(state = previewState(), onEvent = {})
    }
}

@Preview(name = "Active session - refused finish", showBackground = true)
@Composable
fun ActiveSessionScreenNoSetsPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ActiveSessionScreen(
            state = previewState().copy(
                exercises = previewState().exercises.map { it.copy(sets = emptyList()) },
                stats = SessionStats(
                    totalSets = 0,
                    totalVolume = 0,
                    durationSeconds = 62,
                    exerciseBreakdown = emptyList(),
                ),
                message = ActiveSessionMessage.NO_SETS_LOGGED,
            ),
            onEvent = {},
        )
    }
}

@Preview(name = "Active session - paused", showBackground = true)
@Composable
fun ActiveSessionScreenPausedPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ActiveSessionScreen(state = previewState().copy(isPaused = true), onEvent = {})
    }
}

/** A push day, three exercises in, with its two accessories run as a superset. */
private fun previewState(): ActiveSessionUiState {
    val bench = Exercise(id = 1L, name = "Bench press")
    val incline = Exercise(id = 2L, name = "Incline dumbbell press")
    val fly = Exercise(id = 3L, name = "Cable fly")
    val pushdown = Exercise(id = 4L, name = "Tricep pushdown")

    fun set(
        number: Int,
        weight: Long,
        reps: Int,
        rpe: Int,
        sessionExerciseId: Long = 1L,
    ) = WorkoutSet(
        id = number.toLong() + sessionExerciseId * 100,
        sessionExerciseId = sessionExerciseId,
        setNumber = number,
        reps = reps,
        weight = weight,
        rpe = rpe,
        isCompleted = true,
        completedAt = 1L,
        createdAt = 1L,
        updatedAt = 1L,
    )

    return ActiveSessionUiState(
        header = ActiveSessionHeader(
            name = "Push day · Upper",
            startedAt = 1_700_000_000_000L,
            elapsed = 23.minutes + 14.seconds,
            progress = SessionProgress(completed = 2, total = 4),
        ),
        stats = SessionStats(
            totalSets = 5,
            totalVolume = 2_800_000_000L,
            durationSeconds = 1_394,
            exerciseBreakdown = emptyList(),
        ),
        exercises = listOf(
            SessionExerciseRow(
                sessionExerciseId = 1L,
                position = 0,
                exercise = bench,
                sets = listOf(
                    set(number = 1, weight = 60_000_000L, reps = 10, rpe = 60),
                    set(number = 2, weight = 70_000_000L, reps = 8, rpe = 75),
                    set(number = 3, weight = 75_000_000L, reps = 6, rpe = 85),
                ),
            ),
            SessionExerciseRow(
                sessionExerciseId = 2L,
                position = 1,
                exercise = incline,
                sets = listOf(
                    set(number = 1, weight = 26_000_000L, reps = 12, rpe = 70, sessionExerciseId = 2L),
                    set(number = 2, weight = 28_000_000L, reps = 10, rpe = 80, sessionExerciseId = 2L),
                ),
                // Mid-set, which is what brings the set-type chips and the note out.
                entry = SetEntry(weight = "30", reps = "8", rpe = 90, setType = SetType.NORMAL),
            ),
            SessionExerciseRow(
                sessionExerciseId = 3L,
                position = 2,
                exercise = fly,
                supersetLabel = "A1",
                isLinkedToNext = true,
            ),
            SessionExerciseRow(
                sessionExerciseId = 4L,
                position = 3,
                exercise = pushdown,
                supersetLabel = "A2",
            ),
        ),
        isLoading = false,
    )
}
