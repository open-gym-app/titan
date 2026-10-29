/*
 * Over the file's function count by the previews: the screen is one composable and its bar,
 * and the rest is the eight states it is drawn in plus the four builders they share. A
 * previews file of its own would put the states a reader checks a change against one file
 * away from the code that draws them.
 */
@file:Suppress("TooManyFunctions")

package com.titan.feature.session.active

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.LoadSuggestion
import com.titan.core.domain.model.MILLIGRAMS_PER_KILOGRAM
import com.titan.core.domain.model.PastPerformance
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.SessionStats
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.SuggestionReason
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.timer.RestTimerState
import com.titan.core.ui.component.DiscardSessionDialog
import com.titan.core.ui.component.DockSet
import com.titan.core.ui.component.EmptyState
import com.titan.core.ui.component.NotificationPermissionPrompt
import com.titan.core.ui.component.PreviousSet
import com.titan.core.ui.component.ReorderState
import com.titan.core.ui.component.ReorderableItem
import com.titan.core.ui.component.RestExpiryAlert
import com.titan.core.ui.component.RestTimerDock
import com.titan.core.ui.component.RestTimerSheet
import com.titan.core.ui.component.SessionHeaderBar
import com.titan.core.ui.component.SessionNameDialog
import com.titan.core.ui.component.SessionProgress
import com.titan.core.ui.component.SessionStatChips
import com.titan.core.ui.component.SheetDragHandle
import com.titan.core.ui.component.SupersetLink
import com.titan.core.ui.component.clearFocusOnTap
import com.titan.core.ui.component.rememberReorderState
import com.titan.core.ui.component.reorderable
import com.titan.core.ui.format.formatSessionDate
import com.titan.core.ui.format.formatSet
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.session.R
import com.titan.feature.session.active.component.CollapsedExerciseRow
import com.titan.feature.session.active.component.FinishSessionDialog
import com.titan.feature.session.active.component.MobilitySuggestionCard
import com.titan.feature.session.active.component.SessionExerciseCard
import kotlinx.coroutines.flow.first
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
    // Handed down as a read rather than a value, so a second passing recomposes the two places
    // that draw a clock and not this whole destination — see [SessionClocks].
    val clocks by viewModel.clocks.collectAsStateWithLifecycle()

    // Whether anybody is looking at the workout, which is the one thing the idle nudge depends
    // on: a session with its screen in front of the user is not an abandoned session, and one
    // that has just gone off screen is the only kind that can become one. `ON_START`/`ON_STOP`
    // rather than resume/pause, so the notification is not taken down and put back by every
    // dialog and system sheet that steals the window.
    //
    // Here rather than inside `ActiveSessionScreen` because it is a property of the
    // destination, not of the screen — the same reason the back handler is on the destination.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onEvent(ActiveSessionEvent.ScreenShown) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onEvent(ActiveSessionEvent.ScreenHidden) }

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

    // The notification prompt, over the first rest of a user who has never been asked — see
    // `ActiveSessionViewModel.askForRestNotifications`. Here rather than in the screen because
    // only something attached to an Activity can raise it. The answer itself is not read,
    // because whichever it was, the ViewModel's move is the same: publish the rest again.
    NotificationPermissionPrompt(
        request = state.notificationPermissionRequest,
        onRequestShown = { viewModel.onEvent(ActiveSessionEvent.NotificationPermissionRequestShown) },
        onAnswered = { viewModel.onEvent(ActiveSessionEvent.NotificationPermissionAnswered) },
    )

    ActiveSessionScreen(
        state = state,
        clocks = { clocks },
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
 * Over the complexity threshold, and it is the branching a screen this size has: three dialogs,
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
    clocks: () -> SessionClocks = { SessionClocks() },
    onPickExercises: (replacing: Boolean) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    // Which of the two the message is drawn as. The zero-sets refusal is a state of the screen
    // rather than an event that passed through it, so it stays on the page.
    val inlineMessage = state.message?.takeIf { it == ActiveSessionMessage.NO_SETS_LOGGED }
    val transient = state.message?.takeUnless { it == ActiveSessionMessage.NO_SETS_LOGGED }
    val transientText = transient?.let { stringResource(it.labelRes()) }

    // Both of the things this screen puts in the bar take it from whatever is already in it,
    // rather than queueing behind it. `SnackbarHostState` serialises on one mutex, so a refusal
    // raised while the undo below is up would otherwise surface as much as ten seconds after the
    // tap that caused it — by which time it reads as a message about something else. The newest
    // thing that happened is the one worth saying, so it displaces the older one; an undo cut
    // short this way is withdrawn rather than delayed, which the effect below reports as the
    // offer lapsing.
    LaunchedEffect(transientText) {
        if (transientText == null) return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(transientText)
        onEvent(ActiveSessionEvent.MessageShown)
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

    // Keyed on [ActiveSessionUiState.isBusy] as well, so the offer is withdrawn the moment the
    // workout stops taking writes: `RestoreSetUseCase` refuses a session that is not running,
    // and a snackbar action is the one control on this page that going inert does not reach —
    // it stays on screen and pressable over a workout the user has already ended.
    LaunchedEffect(state.deletedSet?.id, state.isBusy) {
        if (deletedSetText == null) return@LaunchedEffect
        if (state.isBusy) {
            onEvent(ActiveSessionEvent.SetDeleteDismissed)
            return@LaunchedEffect
        }
        snackbarHostState.currentSnackbarData?.dismiss()
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

    val focusManager = LocalFocusManager.current

    // Resolved out here rather than in the dock's slot: the slot recomposes on every tick of a
    // rest, and the answer only changes with the workout.
    val working = state.dockExercise

    Scaffold(
        // A tap on the page rather than on a control puts the keyboard away — see
        // `clearFocusOnTap`.
        modifier = modifier
            .fillMaxSize()
            .clearFocusOnTap(focusManager),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            SessionHeaderBar(
                name = state.header.title(),
                // Read here, inside the bar's own slot, so the tick recomposes the bar and only it.
                elapsed = clocks().elapsed,
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
                // The clock is the way into the rest window, with or without a rest running.
                onClockClick = { onEvent(ActiveSessionEvent.RestWindowRequested) },
                canFinish = state.canFinish,
            )
        },
        bottomBar = {
            // The rest, at the edge the thumb is on — see `RestTimerDock` for why it left the
            // header. Only over a workout that is there: a blank page or a missing session has
            // no rest to take.
            if (state.header != null) {
                RestTimerDock(
                    // Read here, inside the dock's own slot, so the tick recomposes the dock and
                    // only it — the same reason the header reads its clock in its slot.
                    state = clocks().rest,
                    // One event for both: the ViewModel raises the sheet over a rest that is
                    // going and starts one where none is.
                    onRestClick = { onEvent(ActiveSessionEvent.RestWindowRequested) },
                    nextSet = working?.let { row ->
                        DockSet(
                            label = stringResource(R.string.active_complete_set, row.nextSetNumber),
                            detail = row.exercise?.name ?: stringResource(R.string.active_exercise_removed),
                            // The rule the card's own button follows, so the two never disagree —
                            // a slot whose exercise is being swapped out included.
                            enabled = state.isEditable && row.canLogEntry &&
                                state.swappingExerciseId != row.sessionExerciseId &&
                                row.sessionExerciseId !in state.loggingExerciseIds,
                        )
                    },
                    // The same event as the card's own button: a set validated from the dock is
                    // a set validated, rest and all.
                    onValidateSet = { working?.let { onEvent(ActiveSessionEvent.SetLogged(it.sessionExerciseId)) } },
                    // The dock reads the rest out only while it is the only readout: with the
                    // sheet up, the sheet's own eyebrow is the live region, and two windows
                    // announcing the same quarter-minute would say it twice.
                    announces = !state.isRestSheetOpen,
                    enabled = !state.isBusy,
                )
            }
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
                clocks = clocks,
                exercises = exercises,
                reorder = reorder,
                canReorder = canReorder,
                inlineMessage = inlineMessage,
                onEvent = onEvent,
                onPickExercises = onPickExercises,
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

    // Rung once, on a countdown that ran out while somebody was looking at this screen — and
    // deliberately not on a rest restored as finished, nor on one that was skipped. See
    // `RestExpiryAlert`, which draws nothing and is here rather than inside the sheet because
    // the sheet is the half the user swipes away.
    RestAlert(rest = { clocks().rest })

    RestSheet(state = state, rest = { clocks().rest }, onEvent = onEvent)

    if (state.confirmingFinish) {
        val progress = state.header?.progress ?: SessionProgress()
        FinishSessionDialog(
            loggedSets = state.stats?.totalSets ?: 0,
            untouchedExercises = progress.total - progress.completed,
            onConfirm = { onEvent(ActiveSessionEvent.FinishConfirmed) },
            onDismiss = { onEvent(ActiveSessionEvent.FinishDismissed) },
            canConfirm = state.canFinish,
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

/**
 * [RestExpiryAlert] over the countdown [rest] reads, in a scope of its own.
 *
 * A function only so the read has somewhere to happen: made in [ActiveSessionScreen]'s body, it
 * would recompose the whole screen on every tick of a rest — see [SessionClocks].
 */
@Composable
private fun RestAlert(rest: () -> RestTimerState) {
    RestExpiryAlert(state = rest())
}

/**
 * The rest, over the workout it is a rest from.
 *
 * ### Why a sheet rather than a bar under the exercise
 *
 * This screen used to specify an inline `RestTimerBar` pinned under the active exercise. The
 * wireframes have a sheet, and they are right: mid-rest the countdown is the only thing on the
 * screen the user cares about, and a bar inside a list as long as a workout is a bar that
 * scrolls off. A sheet also dims what is behind it, which is the honest picture of the ninety
 * seconds where there is nothing to do.
 *
 * ### Swiping it away does not cancel anything
 *
 * The one thing a lifter reliably wants mid-rest is the set they logged wrong, and the sheet is
 * in front of it. So the swipe hides the sheet and leaves the countdown running — the rest
 * dock says so, and tapping it brings this back. Ending the rest is the button that says so.
 *
 * ### It comes down when the rest is over, and nothing here has to decide that
 *
 * A countdown that reaches zero takes the sheet with it — the rest is finished, and the answer
 * to "what now" is the workout, not a panel saying it is done. Whether the sheet is up is
 * `isRestSheetOpen`'s answer: it is only true over a rest that is still live, and it survives a
 * process restart, which a `SheetState` could not. See `ActiveSessionViewModel.build`. The one
 * thing added here is waiting for the countdown itself, which arrives separately and can be a
 * frame behind the flag.
 *
 * What this *does* own is the way down. Material animates a swipe out itself and calls
 * `onDismissRequest` afterwards, but the two commonest endings — the skip button and the
 * countdown running out — flip the flag from the ViewModel's side, and a sheet dropped straight
 * out of composition there takes itself and its scrim off in a single frame. So the flag going
 * false starts a `hide()`, and the sheet stays composed until that animation is done. The state
 * is hoisted for exactly that reason and no other: `hide()` has to reach the sheet that is on
 * screen.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RestSheet(
    state: ActiveSessionUiState,
    rest: () -> RestTimerState,
    onEvent: (ActiveSessionEvent) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    // The flag and the countdown come down two flows — see [SessionClocks] — and a rest that has
    // just started can raise the flag a frame before its countdown reaches [rest]. Opened on
    // both, so the sheet never slides in showing a zeroed clock. Derived, so this scope hears
    // when a rest goes live or stops being live, and not each of its seconds.
    val isRestLive by remember(rest) {
        derivedStateOf { rest().let { it is RestTimerState.Running || it is RestTimerState.Paused } }
    }
    val isOpen = state.isRestSheetOpen && isRestLive

    // Composed while the sheet is up *or* on its way down, which is the one thing the screen
    // state cannot say: it went false the moment the rest ended.
    var onScreen by remember { mutableStateOf(isOpen) }

    LaunchedEffect(isOpen) {
        if (isOpen) {
            onScreen = true
        } else if (onScreen) {
            // Best-effort: a sheet asked to hide before it has been laid out has nothing to
            // animate, and a rest that ends in the same frame it began is not worth a crash.
            runCatching { sheetState.hide() }
            onScreen = false
        }
    }

    if (!onScreen) return

    ModalBottomSheet(
        onDismissRequest = { onEvent(ActiveSessionEvent.RestSheetDismissed) },
        sheetState = sheetState,
        // A step up from the page rather than the scheme's default `surfaceContainerLow`, so
        // the sheet reads as a card lifted off the workout instead of as a lighter patch of it.
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { SheetDragHandle() },
    ) {
        RestTimerSheet(
            // Read inside the sheet's own content, so a tick of the countdown redraws the sheet
            // and not the workout under it — see [SessionClocks].
            state = rest(),
            onAdjust = { onEvent(ActiveSessionEvent.RestAdjusted(it)) },
            onSkip = { onEvent(ActiveSessionEvent.RestSkipped) },
            onTogglePause = { onEvent(ActiveSessionEvent.RestPauseToggled) },
            // What the set that ends this rest is expected to be. Formatted here rather than in
            // the ViewModel because the unit is the ambient one and the "×" is a word.
            nextSet = state.restTarget?.let { formatSet(it.weight, it.reps, it.duration, it.distance) },
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
    clocks: () -> SessionClocks,
    exercises: List<SessionExerciseRow>,
    reorder: ReorderState,
    canReorder: Boolean,
    inlineMessage: ActiveSessionMessage?,
    onEvent: (ActiveSessionEvent) -> Unit,
    onPickExercises: (replacing: Boolean) -> Unit,
    contentPadding: PaddingValues,
) {
    // The entry row goes read-only while the session is suspended or on its way out.
    // `LogSetUseCase` refuses a set logged into a paused session, so a field that still took
    // keystrokes would be collecting them for a write that cannot happen.
    val enabled = state.isEditable

    val gap by animateDpAsState(
        targetValue = if (reorder.isActive) TitanTheme.spacing.md else TitanTheme.spacing.lg,
        label = "reorder gap",
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // The list stops at the rest dock rather than running on under it, so a card cut
            // off at the bottom is cut off by the list's own edge — the edge a scroll is
            // expected to reveal more past — and not hidden behind an opaque bar.
            .padding(bottom = contentPadding.calculateBottomPadding())
            .navigationBarsPadding()
            // The gesture lives on the list rather than on the cards, because the cards move —
            // see `Modifier.reorderable`.
            .reorderable(reorder),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + TitanTheme.spacing.screen,
            bottom = TitanTheme.spacing.section,
            start = TitanTheme.spacing.screen,
            end = TitanTheme.spacing.screen,
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
                        // The set counter moves when a tick lands three cards down; announced,
                        // or a screen reader never hears the workout add up.
                        announces = true,
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

        if (!reorder.isActive) {
            // Above the first exercise, because that is the order it is performed in — and
            // under the stats rather than over them, so the running figures stay the first
            // thing on the page for the rest of the workout.
            mobilityCard(
                card = state.warmup,
                kind = MobilityKind.WARMUP,
                enabled = enabled,
                onEvent = onEvent,
            )
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
                            // Which card is being timed changes on a start or a stop; what it reads
                            // changes every second, and is read inside that card's stopwatch alone.
                            isHoldTimed = state.timedExerciseId == row.sessionExerciseId,
                            isHoldTimedElsewhere = state.timedExerciseId
                                ?.let { it != row.sessionExerciseId } == true,
                            holdElapsed = { clocks().stopwatch },
                            onRemove = {
                                onEvent(ActiveSessionEvent.ExerciseRemoved(row.sessionExerciseId))
                            },
                            onSwap = {
                                onEvent(ActiveSessionEvent.ExerciseSwapRequested(row.sessionExerciseId))
                                // Replacing: the slot holds one exercise, and the picker takes
                                // one rather than offering a list it would have to trim.
                                onPickExercises(true)
                            },
                            togglingSetIds = state.togglingSetIds,
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

        // Under the last exercise and above the add action: it belongs after the work, and the
        // button below it is about the work rather than about the stretch.
        mobilityCard(
            card = state.cooldown,
            kind = MobilityKind.COOLDOWN,
            enabled = enabled,
            onEvent = onEvent,
        )

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
                    .padding(top = TitanTheme.spacing.md)
                    .animateItem(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = TitanTheme.spacing.sm),
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
 * One of the two suggestion cards as a list item, or nothing at all (#214).
 *
 * A `LazyListScope` extension rather than a composable, because what it has to be able to do is
 * *not emit an item*: a null card draws no row and takes no gap in the column's spacing, where a
 * composable returning early would still have an item around it.
 *
 * Keyed on the kind so the two cards keep their identity as the workout between them grows.
 */
private fun LazyListScope.mobilityCard(
    card: MobilityCard?,
    kind: MobilityKind,
    enabled: Boolean,
    onEvent: (ActiveSessionEvent) -> Unit,
) {
    card ?: return
    item(key = "mobility-$kind") {
        MobilitySuggestionCard(
            card = card,
            kind = kind,
            onExpand = { onEvent(ActiveSessionEvent.MobilityExpanded(kind, it)) },
            onAdd = { onEvent(ActiveSessionEvent.MobilityAccepted(kind)) },
            onDismiss = { onEvent(ActiveSessionEvent.MobilityDismissed(kind)) },
            enabled = enabled,
            modifier = Modifier.animateItem(),
        )
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
private fun ActiveSessionHeader?.title(): String =
    when {
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
private fun SessionExerciseRow.name(): String = exercise?.name ?: stringResource(R.string.active_exercise_removed)

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
    ActiveSessionMessage.SET_COMPLETION_FAILED -> R.string.active_error_set_completion
    ActiveSessionMessage.RESTORE_SET_FAILED -> R.string.active_error_restore_set
    ActiveSessionMessage.SET_RESTORED_AT_END -> R.string.active_error_restored_at_end
    ActiveSessionMessage.RESTORE_FAILED -> R.string.active_error_restore
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

// Taller than a phone on purpose: the page is the whole workout now, and a preview cut off at
// 480dp would document one card and none of the joins between them.
@Preview(name = "Active session", showBackground = true, heightDp = 1800)
@Composable
fun ActiveSessionScreenPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        ActiveSessionScreen(state = previewState(), onEvent = {}, clocks = ::previewClocks)
    }
}

/**
 * Every label at twice its size — the bar #40 holds every screen to, on the pattern Home set.
 *
 * The set table is the case worth looking at: five fixed columns and two fields on a 320dp
 * grid, and the previous-set cell is the one that ellipsises first. Recorded rather than argued
 * about: a change that starts clipping something at 2x fails the build. Robolectric's font
 * metrics are not a phone's, so the device walk in docs/ACCESSIBILITY.md is still the result.
 */
@Preview(name = "Active session - twice the font", showBackground = true, heightDp = 3000, fontScale = 2f)
@Composable
fun ActiveSessionScreenLargeFontPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        ActiveSessionScreen(state = previewState(), onEvent = {}, clocks = ::previewClocks)
    }
}

@Preview(name = "Active session, dark", showBackground = true)
@Composable
fun ActiveSessionScreenDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        ActiveSessionScreen(state = previewState(), onEvent = {}, clocks = ::previewClocks)
    }
}

@Preview(name = "Active session - refused finish", showBackground = true)
@Composable
fun ActiveSessionScreenNoSetsPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
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
            clocks = ::previewClocks,
        )
    }
}

@Preview(name = "Active session - correcting a set", showBackground = true, heightDp = 1000)
@Composable
fun ActiveSessionScreenEditingPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        val base = previewState()
        ActiveSessionScreen(
            state = base.copy(
                exercises = base.exercises.map { row ->
                    // The second set of the bench press, half corrected: 70 typed back to 72.5,
                    // not yet left. Nothing about the table has changed shape — the sets around it
                    // are still readable and still typeable, which is the whole argument for there
                    // being no editor at all.
                    if (row.sessionExerciseId != 1L) {
                        row
                    } else {
                        row.copy(
                            sets = row.sets.mapIndexed { index, set ->
                                if (index == 1) {
                                    set.copy(entry = set.entry.copy(weight = "72.5"))
                                } else {
                                    set
                                }
                            },
                        )
                    }
                },
            ),
            onEvent = {},
            clocks = ::previewClocks,
        )
    }
}

@Preview(name = "Active session - resting", showBackground = true)
@Composable
fun ActiveSessionScreenRestingPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        // The sheet swiped away with the rest still running, which is the state the rest
        // dock's countdown exists for. The sheet itself is a window of its own and does not render in a
        // preview; `RestTimerSheet`'s own previews in `:core:ui` are where its body is drawn.
        ActiveSessionScreen(
            state = previewState().copy(restTarget = RestTarget(weight = 80_000_000L, reps = 5)),
            onEvent = {},
            clocks = {
                previewClocks().copy(rest = RestTimerState.Running(remaining = 83.seconds, total = 120.seconds))
            },
        )
    }
}

/**
 * The two suggestion cards in place: the warm-up over the workout, the cool-down under it.
 *
 * In context rather than only in `MobilitySuggestionCard`'s own previews, because where they sit
 * is half of what they are — a warm-up under the last exercise would be a warm-up nobody does.
 */
@Preview(name = "Active session - with suggestions", showBackground = true, heightDp = 2400)
@Composable
fun ActiveSessionScreenSuggestionsPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        ActiveSessionScreen(
            state = previewState().copy(
                warmup = MobilityCard(
                    movements = listOf(
                        MobilityMovement(exerciseId = 10L, name = "Arm circles"),
                        MobilityMovement(exerciseId = 11L, name = "Band pull-apart"),
                    ),
                    rampUp = listOf(
                        PlannedSet(setType = SetType.WARMUP, reps = 5, weight = 40_000_000L),
                        PlannedSet(setType = SetType.WARMUP, reps = 3, weight = 60_000_000L),
                    ),
                    rampUpExerciseName = "Bench press",
                ),
                cooldown = MobilityCard(
                    movements = listOf(
                        MobilityMovement(exerciseId = 20L, name = "Standing chest stretch"),
                        MobilityMovement(exerciseId = 21L, name = "Overhead triceps stretch"),
                    ),
                    isExpanded = false,
                ),
            ),
            onEvent = {},
            clocks = ::previewClocks,
        )
    }
}

@Preview(name = "Active session - paused", showBackground = true)
@Composable
fun ActiveSessionScreenPausedPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        ActiveSessionScreen(state = previewState().copy(isPaused = true), onEvent = {}, clocks = ::previewClocks)
    }
}

/**
 * A workout of timed exercises: a plank one hold in, with the stopwatch under the hold being
 * typed, and a run not started yet — the two other shapes an exercise card takes (#M7-17).
 */
@Preview(name = "Active session - a hold and a run", showBackground = true, heightDp = 1000)
@Composable
fun ActiveSessionScreenTimedPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        ActiveSessionScreen(state = previewTimedState(), onEvent = {}, clocks = ::previewClocks)
    }
}

/** The same workout with the plank being held: the stopwatch reading, and its stop control. */
@Preview(name = "Active session - timing a hold, dark", showBackground = true, heightDp = 1000)
@Composable
fun ActiveSessionScreenTimingDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        ActiveSessionScreen(
            state = previewTimedState().copy(timedExerciseId = 5L),
            onEvent = {},
            clocks = { previewClocks().copy(stopwatch = 47.seconds) },
        )
    }
}

/** What the header's clock reads in every preview. */
private fun previewClocks() = SessionClocks(elapsed = 23.minutes + 14.seconds)

/** A push day, three exercises in, with its two accessories run as a superset. */
private fun previewState(): ActiveSessionUiState =
    ActiveSessionUiState(
        header = ActiveSessionHeader(
            name = "Push day · Upper",
            startedAt = 1_700_000_000_000L,
            progress = SessionProgress.of(completed = 2, total = 4),
        ),
        stats = SessionStats(
            totalSets = 5,
            totalVolume = 2_800_000_000L,
            durationSeconds = 1_394,
            exerciseBreakdown = emptyList(),
        ),
        exercises = previewExercises(),
        isLoading = false,
    )

/** A core and conditioning day: a plank with one hold logged, and a run. */
private fun previewTimedState(): ActiveSessionUiState =
    previewState().copy(
        header = ActiveSessionHeader(
            name = "Core · Cardio",
            startedAt = 1_700_000_000_000L,
            progress = SessionProgress.of(completed = 1, total = 2),
        ),
        stats = SessionStats(
            totalSets = 1,
            totalVolume = 0,
            durationSeconds = 1_394,
            exerciseBreakdown = emptyList(),
            timedSeconds = 75,
        ),
        exercises = listOf(
            SessionExerciseRow(
                sessionExerciseId = 5L,
                position = 0,
                exercise = Exercise(id = 5L, name = "Plank", type = ExerciseType.ISOMETRIC),
                sets = listOf(
                    LoggedSet(
                        id = 501L,
                        entry = SetEntry(duration = "1:15"),
                        isCompleted = true,
                        previous = PreviousSet(weight = null, reps = null, duration = 60),
                    ),
                ),
                entryPrevious = PreviousSet(weight = null, reps = null, duration = 60),
                hasHistory = true,
            ),
            SessionExerciseRow(
                sessionExerciseId = 6L,
                position = 1,
                exercise = Exercise(id = 6L, name = "Running, Treadmill", type = ExerciseType.ENDURANCE),
                entry = SetEntry(distance = "5", duration = "27:30"),
                entryPrevious = PreviousSet(weight = null, reps = null, duration = 1_710, distance = 500_000),
                hasHistory = true,
            ),
        ),
    )

/**
 * The four exercises the previews are drawn from, in the four states this screen has to show at
 * once: one with a full reference behind it, one with a shorter one, one that has never been
 * performed, and one waiting for its first set of the day.
 */
private fun previewExercises(): List<SessionExerciseRow> =
    listOf(
        previewBench(),
        previewIncline(),
        // Never performed, which is the state the badge under the name is for.
        SessionExerciseRow(
            sessionExerciseId = 3L,
            position = 2,
            exercise = Exercise(id = 3L, name = "Cable fly"),
            supersetLabel = "A1",
            supersetGroupName = "A",
            isLinkedToNext = true,
        ),
        SessionExerciseRow(
            sessionExerciseId = 4L,
            position = 3,
            exercise = Exercise(id = 4L, name = "Tricep pushdown"),
            supersetLabel = "A2",
            supersetGroupName = "A",
            entryPrevious = previously(weightKg = 30.0, reps = 15),
            hasHistory = true,
            // Fifteen is past the top of any range: the weight goes up and the line under the
            // row says by how much — the one target on this page that shows a change.
            target = previewTarget(
                weightKg = 32.5,
                reps = 3,
                reason = SuggestionReason.INCREASE,
                previousWeightKg = 30.0,
                basis = listOf(previously(weightKg = 30.0, reps = 15)),
            ),
        ),
    )

/** Three sets in, with last week's numbers against every row of it and against the next one. */
private fun previewBench() =
    SessionExerciseRow(
        sessionExerciseId = 1L,
        position = 0,
        exercise = Exercise(id = 1L, name = "Bench press"),
        sets = listOf(
            previewSet(
                number = 1,
                weight = "60",
                reps = 10,
                rpe = 60,
                previous = previously(weightKg = 60.0, reps = 10),
            ),
            previewSet(
                number = 2,
                weight = "70",
                reps = 8,
                rpe = 75,
                previous = previously(weightKg = 70.0, reps = 8),
            ),
            // The decimal case the previous column is sized against: `72.5×6` is about the widest
            // thing that cell has to hold without clipping.
            previewSet(
                number = 3,
                weight = "75",
                reps = 6,
                rpe = 85,
                previous = previously(weightKg = 72.5, reps = 6),
            ),
        ),
        // What the fourth set was last time, which is the row that reads as a target.
        entryPrevious = previously(weightKg = 80.0, reps = 5),
        hasHistory = true,
        // And what the engine makes of it: 80 × 5 sits inside the strength range, so the
        // weight stays and the reps go up by one. Drawn as the fields' placeholders and as the
        // line under the entry row — see `SetTargetLine`.
        target = previewTarget(
            weightKg = 80.0,
            reps = 6,
            reason = SuggestionReason.ADD_REP,
            previousWeightKg = 80.0,
            basis = listOf(
                previously(weightKg = 60.0, reps = 10),
                previously(weightKg = 70.0, reps = 8),
                previously(weightKg = 72.5, reps = 6),
                previously(weightKg = 80.0, reps = 5),
            ),
        ),
        // The other line under a name: the mark to beat, which is what an exercise with history
        // carries where one without it carries the "first time" badge. Set on this exercise so
        // every preview of this screen draws both states of that pair at once.
        record = PersonalRecord(
            id = 1L,
            exerciseId = 1L,
            workoutSetId = 3L,
            type = PrType.MAX_WEIGHT,
            weight = (92 * MILLIGRAMS_PER_KILOGRAM).toLong(),
            achievedAt = 1_699_000_000_000L,
        ),
    )

/** Two sets today against one last time, and mid-set — which is what brings the note out. */
private fun previewIncline() =
    SessionExerciseRow(
        sessionExerciseId = 2L,
        position = 1,
        exercise = Exercise(id = 2L, name = "Incline dumbbell press"),
        sets = listOf(
            previewSet(
                number = 1,
                weight = "26",
                reps = 12,
                rpe = 70,
                sessionExerciseId = 2L,
                previous = previously(weightKg = 24.0, reps = 12),
            ),
            // The column is empty on this row, and the exercise still has history behind it: the
            // two states the "first time" badge has to stay out of.
            previewSet(number = 2, weight = "28", reps = 10, rpe = 80, sessionExerciseId = 2L),
        ),
        entry = SetEntry(weight = "30", reps = "8", rpe = 90, setType = SetType.NORMAL),
        hasHistory = true,
    )

/**
 * A logged set as the row it is drawn in: the values already in the fields, because every row of
 * the table is live — see `LoggedSetTable`. The weight is text in the ambient unit, which is what
 * the ViewModel formats it into.
 */
private fun previewSet(
    number: Int,
    weight: String,
    reps: Int,
    rpe: Int,
    sessionExerciseId: Long = 1L,
    previous: PreviousSet? = null,
) = LoggedSet(
    id = number.toLong() + sessionExerciseId * 100,
    entry = SetEntry(weight = weight, reps = reps.toString(), rpe = rpe),
    isCompleted = true,
    previous = previous,
)

/**
 * Last week's set. Written in kilograms and converted, because what this type carries is the
 * milligrams everything is stored in, and `72_500_000` reads as nothing at a glance.
 */
private fun previously(
    weightKg: Double,
    reps: Int,
) = PreviousSet(weight = (weightKg * MILLIGRAMS_PER_KILOGRAM).toLong(), reps = reps)

/**
 * What the engine would prescribe, written the same way: kilograms, and the sets it was read
 * from as [previously] writes them, in set order.
 */
private fun previewTarget(
    weightKg: Double?,
    reps: Int,
    reason: SuggestionReason,
    previousWeightKg: Double?,
    basis: List<PreviousSet>,
) = LoadSuggestion(
    weight = weightKg?.let { (it * MILLIGRAMS_PER_KILOGRAM).toLong() },
    reps = reps,
    reason = reason,
    previousWeight = previousWeightKg?.let { (it * MILLIGRAMS_PER_KILOGRAM).toLong() },
    basis = PastPerformance(
        basis.mapIndexed { index, set ->
            WorkoutSet(
                sessionExerciseId = 1L,
                setNumber = index + 1,
                reps = set.reps,
                weight = set.weight,
                isCompleted = true,
                completedAt = 0,
                createdAt = 0,
                updatedAt = 0,
            )
        },
    ),
)
