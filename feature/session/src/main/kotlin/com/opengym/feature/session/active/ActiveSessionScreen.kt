package com.opengym.feature.session.active

import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.SessionStats
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.ui.component.EmptyState
import com.opengym.core.ui.component.ReorderState
import com.opengym.core.ui.component.ReorderableItem
import com.opengym.core.ui.component.SessionHeaderBar
import com.opengym.core.ui.component.SessionNameDialog
import com.opengym.core.ui.component.SessionProgress
import com.opengym.core.ui.component.SessionStatChips
import com.opengym.core.ui.component.rememberReorderState
import com.opengym.core.ui.component.reorderable
import com.opengym.core.ui.format.formatSessionDate
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.session.R
import com.opengym.feature.session.active.component.CollapsedExerciseRow
import com.opengym.feature.session.active.component.DiscardSessionDialog
import com.opengym.feature.session.active.component.SessionExerciseCard
import com.opengym.feature.session.active.component.SupersetLink
import kotlinx.coroutines.flow.first
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
 * @param onPickExercises push the exercise picker, saying which of the two questions it is
 *   being opened with: `replacing` for the swap on a card, which fills one slot and so takes
 *   one exercise, and false for the button that adds to the workout. What that opens lives in
 *   another feature module, so it is `:app`'s to resolve — see `ExercisePicker`.
 * @param pickedExerciseIds what the picker came back with, or null when it has not answered.
 *   Two things on this screen open it — "add more exercises" and the swap on a card — and which
 *   of them asked is remembered here rather than in the ViewModel, for the reason the ids
 *   themselves are consumed here: it is a fact about a navigation in flight.
 * @param onPickedExercisesHandled the answer has been applied and should be forgotten, so a
 *   recomposition or a process restore does not append the same exercises twice.
 */
@Composable
fun ActiveSessionRoute(
    onFinishSession: (sessionId: Long) -> Unit,
    onDiscardSession: () -> Unit,
    onPickExercises: (replacing: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pickedExerciseIds: List<Long>? = null,
    onPickedExercisesHandled: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    viewModel: ActiveSessionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The slot a swap is waiting on, or null when the picker was opened to add to the workout.
    // Set by whichever control asked, so a picker backed out of and reopened from the other one
    // cannot answer the wrong question. `rememberSaveable` because the picker is a screen the
    // user can sit on long enough for the process to be killed behind it, which is the same
    // reason `:app` saves the answer itself.
    var swappingExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Consumed here rather than in the ViewModel because the ids are a *navigation* result:
    // `:app` holds them while the picker pops, and the ViewModel has no business knowing that a
    // back stack is how the answer arrived. The same shape the new-session flow uses.
    LaunchedEffect(pickedExerciseIds) {
        val picked = pickedExerciseIds ?: return@LaunchedEffect
        val target = swappingExerciseId
        val replacement = picked.firstOrNull()

        // Held until the ViewModel will take it, rather than dispatched and forgotten. Both
        // `addPicked` and `swap` refuse while a write of their own kind is in flight, and an
        // answer that lands in that refusal is dropped in silence — while the clearing below
        // throws away the only copy of what the user picked, with the picker already popped
        // behind them. A workout on its way out is the one case with nothing to wait for:
        // nothing can be added to a session that is over.
        val ready = viewModel.uiState.first {
            it.isBusy || (!it.isAddingExercises && it.swappingExerciseId == null)
        }

        // A slot holds one exercise, and a picker opened for a swap is drawn as a single
        // choice — see `ExercisePickerDestination.replacing` — so the list is one id long.
        // Read as the first of it all the same: this is the one place that would otherwise
        // turn a second answer into an edit of the whole workout, silently, and it costs a
        // `firstOrNull` to make that unrepresentable rather than merely unlikely.
        if (!ready.isBusy) {
            if (target != null && replacement != null) {
                viewModel.onEvent(ActiveSessionEvent.ExerciseSwapped(target, replacement))
            } else {
                viewModel.onEvent(ActiveSessionEvent.ExercisesPicked(picked))
            }
        }

        swappingExerciseId = null
        onPickedExercisesHandled()
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
        onEvent = { event ->
            // Recorded on the way past rather than handled: the ViewModel has nothing to do with
            // either of these, and what they change is which question the picker is about to
            // answer. The add action clears it, so the last control tapped is always the one the
            // answer belongs to.
            when (event) {
                is ActiveSessionEvent.ExerciseSwapRequested ->
                    swappingExerciseId = event.sessionExerciseId

                ActiveSessionEvent.AddExercisesRequested -> swappingExerciseId = null
                else -> Unit
            }
            viewModel.onEvent(event)
        },
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
 * ### The plan meets the gym
 *
 * Two things can be changed about the workout itself, because a plan does not survive contact
 * with a busy room.
 *
 * **The order.** Holding an exercise for a moment picks it up, and the whole page collapses to
 * one line per exercise while it is held — the session, whole, on one screen — so moving the
 * sixth exercise above the second is one movement of a thumb rather than a drag into a
 * scrolling edge. Letting go drops it there and saves. There is no reorder mode to enter or
 * leave: the finger coming up is the end of it. A superset the drag pulls apart is torn rather
 * than refused, which is `ReorderSessionExercisesUseCase`'s rule and is why nothing here has to
 * explain a bounced drop.
 *
 * **What is in a slot.** Each card can have its exercise swapped for another, keeping its place
 * in the order and whatever it is paired with. Refused once sets have been logged against it,
 * because those sets would otherwise be attributed to a movement nobody performed.
 *
 * Both go inert on a paused session, like everything else on the page.
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
/*
 * Over the complexity threshold, and it is the branching a screen this size has: two dialogs,
 * four page states, a snackbar that is two different offers, and a list that changes shape while
 * an exercise is held. Every branch is a state the workout can genuinely be in, and pulling them
 * into helpers would move the `when` rather than remove it — see `SessionBody`, which is the
 * split that was worth making.
 */
@Suppress("CyclomaticComplexMethod")
fun ActiveSessionScreen(
    state: ActiveSessionUiState,
    onEvent: (ActiveSessionEvent) -> Unit,
    modifier: Modifier = Modifier,
    onPickExercises: (replacing: Boolean) -> Unit = {},
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

    // The undo, which is the whole of why deleting a set asks nothing first. Keyed on the set's
    // id rather than on the value, so deleting a second set while the first offer is still up
    // cancels that offer and makes a new one — two undos on screen at once would be two buttons
    // that look identical and mean different sets.
    //
    // `Long` rather than `Short`: this is not a notification, it is the window in which a
    // mistake can be taken back, and four seconds is not long enough to look up from the bar,
    // read it and decide.
    val deletedSetText = state.deletedSet?.let {
        stringResource(R.string.active_set_deleted, it.setNumber)
    }
    val undoLabel = stringResource(R.string.active_undo)

    LaunchedEffect(state.deletedSet?.id) {
        if (deletedSetText == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedSetText,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Long,
        )
        onEvent(
            when (result) {
                SnackbarResult.ActionPerformed -> ActiveSessionEvent.SetDeleteUndone
                SnackbarResult.Dismissed -> ActiveSessionEvent.SetDeleteDismissed
            },
        )
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

    // The order as it is being dragged. Screen-local and seeded from the store, on two keys.
    //
    // The stored order is the obvious one: anything that *changes* the workout — the drag's own
    // write, an exercise added or cut — re-seeds this, and between gestures the two are the same
    // list. What that key cannot see is a reorder the store **refused**, which is the case that
    // matters: a refusal changes nothing, so the stored order is the list it already was, the key
    // is structurally equal to what it was, `remember` does not re-run, and the screen goes on
    // drawing an order the database rejected — a lifter working down a list the log disagrees
    // with, with nothing to put it back.
    //
    // Hence the count of refusals, which is a fact about the workout that only the ViewModel has.
    // A count rather than a flag because two refusals in a row have to be two re-seeds, and it is
    // read only as a key: nothing on the screen draws it.
    val storedOrder = state.exercises.map(SessionExerciseRow::sessionExerciseId)
    var order by remember(storedOrder, state.refusedReorders) { mutableStateOf(storedOrder) }

    // Not `rememberSaveable`, unlike the scale above: a held exercise cannot survive the process
    // that is holding it, and a list that came back collapsed with nothing under the finger
    // would be a mode with no way out.
    // Hoisted, because the cards say so too: the grab mark on a card is drawn exactly where
    // holding it would pick it up, and a hint at a gesture that is inert is worse than none.
    val canReorder = state.isEditable && !state.isReordering && state.exercises.size > 1

    val reorder = rememberReorderState(
        itemCount = state.exercises.size,
        onMove = { from, to -> order = order.toMutableList().apply { add(to, removeAt(from)) } },
        onCommit = { onEvent(ActiveSessionEvent.ExercisesReordered(order)) },
        // Nothing to reorder in a workout of one, and a paused workout is read-only —
        // `ReorderSessionExercisesUseCase` refuses one, so an exercise that could still be
        // picked up would be a gesture whose only outcome is a refusal. Nor while an order is
        // being written: a second one computed against the list as it was is the same mistake
        // the superset control guards against with `isRegrouping`.
        enabled = canReorder,
    )

    // Drawn in the order being dragged rather than the stored one, so the page under the finger
    // is the page the finger is making. `mapNotNull` because the two lists are only briefly
    // different — an exercise cut elsewhere leaves an id here until the re-seed above.
    val exercises = order.mapNotNull { id ->
        state.exercises.firstOrNull { it.sessionExerciseId == id }
    }

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
                // The pause is *not* gated on `isEditable`: coming back from a pause is what
                // that control is for. What takes the three of them out is a workout already
                // closing out — a pause landing behind a finish is refused and reported over a
                // screen on its way to the recap.
                enabled = !state.isBusy,
                onPauseToggle = { onEvent(ActiveSessionEvent.PauseToggled) },
                onFinish = { onEvent(ActiveSessionEvent.FinishRequested) },
                onDiscard = { onEvent(ActiveSessionEvent.DiscardRequested) },
                // The name is the affordance, so a workout started from the quick action can be
                // titled from the one place its title is already shown.
                onNameClick = { onEvent(ActiveSessionEvent.NameEditRequested) },
                canFinish = state.canFinish,
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
                exercises = exercises,
                reorder = reorder,
                canReorder = canReorder,
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
/*
 * One branch per thing the page draws or leaves out, which is what the count is measuring: the
 * stats, the inline refusal, the empty state, the join between each pair of cards, the add
 * action — and, over all of them, whether an exercise is being held. Splitting the list into
 * per-section composables would move the branches rather than remove them, and it would put the
 * `LazyListScope` this all belongs to behind five signatures.
 */
@Suppress("CyclomaticComplexMethod")
private fun SessionBody(
    state: ActiveSessionUiState,
    exercises: List<SessionExerciseRow>,
    reorder: ReorderState,
    canReorder: Boolean,
    inlineMessage: ActiveSessionMessage?,
    onEvent: (ActiveSessionEvent) -> Unit,
    onPickExercises: (replacing: Boolean) -> Unit,
    rpeScaleFor: Long?,
    onRpeToggle: (Long) -> Unit,
    contentPadding: PaddingValues,
) {
    // The entry row goes read-only while the session is suspended or on its way out.
    // `LogSetUseCase` refuses a set logged into a paused session, so a field that still took
    // keystrokes would be collecting them for a write that cannot happen.
    val enabled = state.isEditable

    val gap by animateDpAsState(
        targetValue = if (reorder.isActive) OpenGymTheme.spacing.md else OpenGymTheme.spacing.lg,
        label = "reorder gap",
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            // The gesture lives on the list rather than on the cards, because the cards move —
            // see `Modifier.reorderable`.
            .reorderable(reorder),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + OpenGymTheme.spacing.screen,
            bottom = OpenGymTheme.spacing.section,
            start = OpenGymTheme.spacing.screen,
            end = OpenGymTheme.spacing.screen,
        ),
        // Tighter while the workout is collapsed: the gap between two cards is what separates
        // one exercise's set table from the next, and between two title rows it is only air.
        // Animated, because it is the whole page closing up at once — stepped, the collapse
        // reads as the screen being replaced rather than the workout folding into itself.
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        // Everything that is not an exercise goes while one is held. What is left is the
        // workout as a list of names, which is the shortest a session can be drawn and the
        // whole reason a drag across it is one movement — see `CollapsedExerciseRow`.
        if (!reorder.isActive) {
            state.stats?.let { stats ->
                item(key = "stats") {
                    // Without the duration: the header's clock is directly above it, and the
                    // same number twice on one screen is one of them being ignored.
                    //
                    // Faded rather than cut, here and on every other item that leaves when an
                    // exercise is picked up: what the collapse is *for* is the workout folding
                    // down to its order, and half the page vanishing between two frames reads
                    // as a screen that broke.
                    SessionStatChips(
                        stats = stats,
                        showDuration = false,
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            if (inlineMessage != null) {
                item(key = "message") {
                    Text(
                        text = stringResource(inlineMessage.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                    )
                }
            }

            if (exercises.isEmpty()) {
                item(key = "no-exercises") {
                    EmptyState(
                        title = stringResource(R.string.active_no_exercises_title),
                        description = stringResource(R.string.active_no_exercises_body),
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        exercises.forEachIndexed { index, row ->
            item(key = "exercise-${row.sessionExerciseId}") {
                val isHeldItem = reorder.heldKey == row.sessionExerciseId

                ReorderableItem(
                    state = reorder,
                    key = row.sessionExerciseId,
                    index = index,
                    // The exercises the held one is passing slide into the place it left,
                    // rather than being somewhere else the next frame. It is the same
                    // information either way; it is only legible when the eye can follow it.
                    //
                    // **Never the held one.** Its own place is where the finger is, and that
                    // is computed from where it has been measured — an animated slot would be
                    // arithmetic chasing an animation that is chasing the arithmetic.
                    modifier = Modifier.animateItem(
                        placementSpec = if (isHeldItem) null else REFLOW,
                    ),
                ) { isHeld ->
                    if (reorder.isActive) {
                        CollapsedExerciseRow(row = row, position = index, isHeld = isHeld)
                    } else {
                        SessionExerciseCard(
                            row = row,
                            // The place it is being dragged to rather than the one on the row,
                            // which is a frame behind while an order is being written.
                            position = index,
                            onEvent = onEvent,
                            onRemove = {
                                onEvent(ActiveSessionEvent.ExerciseRemoved(row.sessionExerciseId))
                            },
                            onSwap = {
                                onEvent(ActiveSessionEvent.ExerciseSwapRequested(row.sessionExerciseId))
                                // Replacing: the slot holds one exercise, and the picker takes
                                // one rather than offering a list it would have to trim.
                                onPickExercises(true)
                            },
                            showRpeScale = rpeScaleFor == row.sessionExerciseId,
                            onRpeToggle = { onRpeToggle(row.sessionExerciseId) },
                            // The card whose exercise is being replaced goes read-only with it:
                            // the fields belong to a movement on its way out of the slot.
                            enabled = enabled && state.swappingExerciseId != row.sessionExerciseId,
                            isLogging = row.sessionExerciseId in state.loggingExerciseIds,
                            isMovable = canReorder,
                        )
                    }
                }
            }

            // The join to the exercise below, which is only a thing where there is one. The last
            // exercise has nothing to be performed back to back with, so it gets no control
            // rather than a disabled one that would have to explain itself. Gone while an
            // exercise is held: it is a control, and nothing on the page can be pressed by a
            // finger that is already holding something.
            val next = exercises.getOrNull(index + 1) ?: return@forEachIndexed
            if (reorder.isActive) return@forEachIndexed
            item(key = "join-${row.sessionExerciseId}") {
                SupersetLink(
                    isLinked = row.isLinkedToNext,
                    // The group's own name, off the row rather than sliced out of its `A1`
                    // label: the label's format belongs to `SupersetLabels`, and its numeric
                    // fallback for a twenty-seventh group is all digits, which a slice on the
                    // first digit reads as an empty name.
                    groupLabel = row.supersetGroupName,
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
                    modifier = Modifier.animateItem(),
                )
            }
        }

        if (reorder.isActive) return@LazyColumn

        item(key = "add-exercises") {
            // At the bottom, under everything the workout already holds, because that is where
            // what it adds will land — an action at the top would append to the far end of a
            // list the user would then have to scroll to find.
            OutlinedButton(
                onClick = {
                    onEvent(ActiveSessionEvent.AddExercisesRequested)
                    onPickExercises(false)
                },
                // Gated on the pause like every other control: `AddSessionExercisesUseCase`
                // refuses a session that is not active, so a live button here would walk the
                // user through the whole picker to refuse what they chose at the end of it.
                enabled = enabled && !state.isAddingExercises,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = OpenGymTheme.spacing.md)
                    .animateItem(),
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
 * How an exercise travels to the place the held one left.
 *
 * A spring rather than a duration, and a soft one: the distance is a whole card in a laid-out
 * workout and a single line in a collapsed one, and a spec that reads well over one of those
 * reads wrong over the other. `StiffnessMediumLow` settles in about a third of a second over
 * either. No bounce — the rows that are *not* being dragged should not draw attention.
 */
private val REFLOW = spring<IntOffset>(
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

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

/**
 * The words a message is said in.
 *
 * One branch per case of the enum and nothing else: it is a lookup table, and the only way to
 * make it shorter is to split it into two tables that a reader then has to find both of.
 */
@StringRes
/*
 * One branch per message, which is what the count is measuring. An exhaustive `when` over an
 * enum has no paths through it to get wrong — the compiler is what checks it is complete — so
 * the threshold is counting cases rather than complexity. A lookup table keyed by the enum
 * would satisfy it and would be the same lines again with a map in front of them.
 */
@Suppress("CyclomaticComplexMethod")
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
    ActiveSessionMessage.REORDER_FAILED -> R.string.active_error_reorder
    ActiveSessionMessage.SWAP_HAS_SETS -> R.string.active_error_swap_has_sets
    ActiveSessionMessage.SWAP_FAILED -> R.string.active_error_swap
    ActiveSessionMessage.SET_EDIT_REJECTED -> R.string.active_error_set_edit
    ActiveSessionMessage.DELETE_SET_FAILED -> R.string.active_error_delete_set
    ActiveSessionMessage.RESTORE_SET_FAILED -> R.string.active_error_restore_set
    ActiveSessionMessage.RESTORE_FAILED -> R.string.active_error_restore
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

@Preview(name = "Active session - correcting a set", showBackground = true, heightDp = 1000)
@Composable
fun ActiveSessionScreenEditingPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        val base = previewState()
        ActiveSessionScreen(
            state = base.copy(
                exercises = base.exercises.map { row ->
                    // The second set of the bench press, reopened: the table around it is still
                    // readable, which is the whole argument for the editor being the row.
                    if (row.sessionExerciseId != 1L) {
                        row
                    } else {
                        row.copy(
                            editing = SetEdit(
                                sessionExerciseId = row.sessionExerciseId,
                                setId = row.sets[1].id,
                                entry = SetEntry(weight = "70", reps = "8", rpe = 75),
                            ),
                        )
                    }
                },
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
            progress = SessionProgress.of(completed = 2, total = 4),
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
