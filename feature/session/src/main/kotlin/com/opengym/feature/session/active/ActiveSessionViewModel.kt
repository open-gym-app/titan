package com.opengym.feature.session.active

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.opengym.core.domain.model.ActiveExercise
import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import com.opengym.core.domain.stats.SessionStatsCalculator
import com.opengym.core.domain.superset.Supersets
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.usecase.AddSessionExercisesUseCase
import com.opengym.core.domain.usecase.DiscardSessionUseCase
import com.opengym.core.domain.usecase.FinishSessionUseCase
import com.opengym.core.domain.usecase.LinkSupersetUseCase
import com.opengym.core.domain.usecase.LogSetUseCase
import com.opengym.core.domain.usecase.PauseSessionUseCase
import com.opengym.core.domain.usecase.RemoveSessionExerciseUseCase
import com.opengym.core.domain.usecase.RenameSessionUseCase
import com.opengym.core.domain.usecase.ReorderSessionExercisesUseCase
import com.opengym.core.domain.usecase.ResumeSessionUseCase
import com.opengym.core.domain.usecase.SwapSessionExerciseUseCase
import com.opengym.core.domain.usecase.UnlinkSupersetUseCase
import com.opengym.core.domain.validation.SetValidator
import com.opengym.core.ui.component.SessionProgress
import com.opengym.core.ui.component.supersetGroupNames
import com.opengym.core.ui.component.supersetLabels
import com.opengym.core.ui.format.formatWeightValue
import com.opengym.core.ui.format.parseWeightMilligrams
import com.opengym.feature.session.navigation.ActiveSessionDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The workout itself: what has been logged, what is being typed, and the four ways out.
 *
 * ### The rows are the record; the draft is the place in them
 *
 * A logged set is written to `workout_set` the moment it is ticked, and the screen reads its
 * sets straight back out of the store as a `Flow`. That is what makes the running figures
 * update "immediately" without a tally being maintained alongside them, and it is what lets
 * `FinishSessionUseCase` — which counts the rows, not anything this ViewModel holds — agree
 * with what is on screen.
 *
 * The [ActiveSession] draft carries the parts no column holds: how long the session has spent
 * paused, and the values half-entered in the fields. It is saved on every change, and it is what
 * a relaunch reads. See PERSISTENCE.md § The in-progress session draft.
 *
 * ### One entry row per exercise
 *
 * The screen has no notion of a current exercise, so neither does this: [ScreenState.entries]
 * holds one half-typed set per `session_exercise` id, and every event about a set names the row
 * it belongs to. That is what makes a superset loggable — its members are alternated set for
 * set, and a single shared entry would be cleared on every switch between them.
 *
 * ### Rotation and process death are two different problems
 *
 * Rotation is answered by this object outliving the activity: the entry fields live in
 * [screenState], so a rotation mid-set changes nothing at all. Process death is answered by the
 * draft, which carries the *parsed* values — a field holding `8.` is not a number and
 * `ActiveSet` has nowhere to put it, so what comes back after a kill is `8` and not `8.`. That
 * is the honest limit of a model that stores quantities rather than keystrokes.
 *
 * ### Reattaching to a session the state machine has forgotten
 *
 * `SessionStateRepository` is in-memory, so a process restart leaves it `Idle` over a session
 * row that is still `is_in_progress = 1`. Every write path here refuses to work on a session
 * that is not `Active`, so the screen would come back read-only. [reattach] is what fixes that:
 * on open, a session that is running on disk but not in the state machine is resumed through
 * [ResumeSessionUseCase], which is the transition that exists for exactly this.
 *
 * A workout that was *paused* when the process died is the one case where that resume is not the
 * final answer: the draft remembers the pause and the state machine does not, so [realignPause]
 * puts the two back on the same side of it before the user can tap anything.
 */
@HiltViewModel
/*
 * Two thresholds are exceeded on purpose, and both are about the same thing: this is the screen
 * the app exists for, and it is the one place a workout can be logged, edited, named, paused,
 * finished and thrown away.
 *
 * - **The parameter list.** Nineteen constructor parameters, and every one is a graph edge:
 *   three stores, the catalogue, the settings, twelve use cases and a clock. Bundling them into
 *   a holder to satisfy a count would put a type in the way of reading what this screen depends
 *   on, which is the one thing the list is good for.
 * - **The function count.** The handlers are already grouped — `onEntryEvent`, `onNameEvent`
 *   and `onLayoutEvent` each own one subject — and what is left is one private method per
 *   thing the user can do. Splitting further means a second class over the same
 *   `MutableStateFlow`, which is two owners of one piece of state to satisfy a number.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class ActiveSessionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val inProgressSessionRepository: InProgressSessionRepository,
    exerciseRepository: ExerciseRepository,
    userPreferencesRepository: UserPreferencesRepository,
    private val logSet: LogSetUseCase,
    private val addExercises: AddSessionExercisesUseCase,
    private val removeExercise: RemoveSessionExerciseUseCase,
    private val reorderExercises: ReorderSessionExercisesUseCase,
    private val swapExercise: SwapSessionExerciseUseCase,
    private val linkSuperset: LinkSupersetUseCase,
    private val unlinkSuperset: UnlinkSupersetUseCase,
    private val renameSession: RenameSessionUseCase,
    private val pauseSession: PauseSessionUseCase,
    private val resumeSession: ResumeSessionUseCase,
    private val finishSession: FinishSessionUseCase,
    private val discardSession: DiscardSessionUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.toRoute<ActiveSessionDestination>().sessionId

    /** What the user is in the middle of, which no store has an opinion about. */
    private val screenState = MutableStateFlow(ScreenState())

    /**
     * The session as it stands on disk: the row, its exercises, and every set logged against it.
     *
     * Three reads as one value, the shape the recap uses and for the same reasons — the
     * arithmetic has a single object to be a pure function of, and `null` means one thing
     * ("nothing has come back yet") rather than three flags that can disagree.
     */
    private val loaded: StateFlow<Loaded?> = combine(
        sessionRepository.getById(sessionId),
        sessionRepository.getExercises(sessionId),
        sessionRepository.getSetsForSession(sessionId),
    ) { session, exercises, sets ->
        Loaded(session = session, exercises = exercises, sets = sets)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    /** The catalogue rows the session's exercises are drawn from. */
    private val catalogue: StateFlow<Catalogue?> = combine(
        exerciseRepository.getAll(),
        exerciseRepository.getAllMuscleGroupLinks(),
    ) { exercises, links ->
        Catalogue(exercises = exercises.associateBy(Exercise::id), muscleLinks = links)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    /**
     * The unit system every weight on this screen is typed and read in.
     *
     * Needed here rather than only in composition because this is the half that *parses*: what
     * the user types is a number in their own unit, and the bound `SetValidator` checks is in
     * milligrams. `LocalUnitSystem` covers the rendering; this covers the round trip back.
     *
     * Collected outright rather than derived with `WhileSubscribed`, which is the one flow here
     * that has to be. It is read *synchronously*, from a keystroke handler, and a `StateFlow`
     * nothing is collecting holds its initial value forever — so a lifter on pounds would have
     * every entry parsed as kilograms until something happened to subscribe. A settings read is
     * one small document behind a `DataStore`, so holding it open for the life of the screen
     * costs nothing worth the bug.
     */
    private val unitSystem = MutableStateFlow(UnitSystem.METRIC)

    /**
     * A heartbeat, once a second, so the elapsed clock is redrawn while nothing else changes.
     *
     * It carries no value — the reading is taken inside [build], from the injected [Clock] —
     * because a timestamp emitted here would be the one the *tick* happened at, and every other
     * thing that rebuilds the state (a set logged, a pause, an exercise opened) would then draw
     * a clock up to a second stale. Taking it at assembly time makes the header right on every
     * rebuild and leaves this flow with one job: making sure a rebuild happens at all.
     *
     * It ticks only while something is collecting the state — `WhileSubscribed` — so a
     * backgrounded workout is not waking the process once a second to recompute a number nobody
     * is reading.
     */
    private val tick: StateFlow<Long> = flow {
        var beat = 0L
        while (true) {
            emit(beat++)
            delay(TICK)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), 0L)

    /**
     * Everything the reads decide, with what the user is in the middle of laid over it.
     *
     * The tick is not one of its inputs. Assembling this runs the row pipeline over every
     * exercise and rolls `SessionStatsCalculator` over every set, and none of that changes
     * because a second has passed — a workout with two hundred sets in it would redo all of
     * that arithmetic sixty times a minute to move one clock. What the tick changes is folded
     * on afterwards, in [uiState].
     */
    private val assembled: StateFlow<ActiveSessionUiState> = combine(
        loaded,
        catalogue,
        screenState,
    ) { loaded, catalogue, screen ->
        build(loaded, catalogue, screen, clock.nowMillis())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = ActiveSessionUiState(),
    )

    /** The assembled state with its clocks moved on, once a second, and nothing else redone. */
    val uiState: StateFlow<ActiveSessionUiState> = combine(
        assembled,
        tick,
    ) { state, _ ->
        state.clockedAt(clock.nowMillis(), screenState.value)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = ActiveSessionUiState(),
    )

    init {
        viewModelScope.launch {
            userPreferencesRepository.observe().collect { unitSystem.value = it.unitSystem }
        }
        // One coroutine, in this order, because each step depends on the one before it. The
        // settings are read again here rather than waited on from the collection above: the two
        // are independent reads of the same small document, and [restoreDraft] formats stored
        // milligrams back into the unit they were typed in — a restore that won the race against
        // the collection would hand a lifter on pounds their 175 lb set back as `79.4`.
        //
        // Wrapped, because every step of it reads or writes a store and this runs before the
        // user has touched anything: a disk error escaping here would take the process down on
        // the way into the screen, over a session that is on disk and recoverable. What a
        // failure costs is the realignment — the screen comes back as the state machine has it
        // — and that is a screen the user can still act on.
        viewModelScope.launch {
            try {
                unitSystem.value = userPreferencesRepository.observe().first().unitSystem
                reattach()
                restoreDraft()
                realignPause()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                screenState.update { it.copy(message = ActiveSessionMessage.RESTORE_FAILED) }
            }
        }
    }

    fun onEvent(event: ActiveSessionEvent) {
        if (onEntryEvent(event) || onNameEvent(event) || onLayoutEvent(event)) return

        when (event) {
            is ActiveSessionEvent.SetLogged -> log(event.sessionExerciseId)
            ActiveSessionEvent.PauseToggled -> togglePause()
            ActiveSessionEvent.FinishRequested -> finish()

            ActiveSessionEvent.DiscardRequested ->
                screenState.update { it.copy(confirmingDiscard = true) }
            // Ignored while the delete is in flight, matching the recap's template dialog: a
            // dismissal that cleared the flag would re-arm the action over a session that is
            // already being deleted.
            ActiveSessionEvent.DiscardDismissed ->
                if (!screenState.value.isDiscarding) {
                    screenState.update { it.copy(confirmingDiscard = false) }
                }
            ActiveSessionEvent.DiscardConfirmed -> discard()

            ActiveSessionEvent.MessageShown -> screenState.update { it.copy(message = null) }
            else -> Unit
        }
    }

    /**
     * The five events that only change the row being typed, handled together.
     *
     * Split off [onEvent] because they are one concern with one effect — edit the entry,
     * re-validate, save the draft — and leaving them inline made a `when` that branched fifteen
     * ways over two unrelated subjects.
     *
     * @return whether [event] was one of them.
     */
    private fun onEntryEvent(event: ActiveSessionEvent): Boolean {
        when (event) {
            is ActiveSessionEvent.WeightChanged ->
                editEntry(event.sessionExerciseId) { it.copy(weight = event.text) }

            is ActiveSessionEvent.RepsChanged ->
                editEntry(event.sessionExerciseId) { it.copy(reps = event.text) }

            is ActiveSessionEvent.RpeChanged ->
                editEntry(event.sessionExerciseId) { it.copy(rpe = event.rpe) }

            is ActiveSessionEvent.SetTypeChanged ->
                editEntry(event.sessionExerciseId) { it.copy(setType = event.setType) }

            is ActiveSessionEvent.NotesChanged ->
                editEntry(event.sessionExerciseId) { it.copy(notes = event.text) }

            else -> return false
        }
        return true
    }

    // ─── Entry ──────────────────────────────────────────────────────────────────────────

    /**
     * Applies [change] to one exercise's entry row and re-validates it.
     *
     * Validation on every keystroke rather than on the tick, which is the whole of "live
     * validation matching `SetValidator`": the rule that refuses the set is the rule that
     * refuses the *value*, so the user sees it while the offending field is still under their
     * thumb. `LogSetUseCase` runs the same check again before it writes — see its note on why
     * that is not duplication.
     *
     * The other exercises' rows are untouched, which is the point of keying them: a lifter
     * halfway through a superset has a weight typed against each of its members, and typing into
     * one must not disturb the other.
     *
     * Refused outright while the session is suspended or on its way out, which is the read-only
     * guarantee `LogSetUseCase` enforces on the write path, enforced here on the draft. Every
     * control that reaches this is drawn inert, so the guard is for the event a control
     * dispatched in the frame before it was — the same reason [log] and [remove] have theirs.
     */
    private fun editEntry(sessionExerciseId: Long, change: (SetEntry) -> SetEntry) {
        val state = screenState.value
        if (state.isBusy || state.pausedAt != null) return

        screenState.update { current ->
            val edited = change(current.entryFor(sessionExerciseId))
            current.copy(
                entries = current.entries + (
                    sessionExerciseId to edited.copy(error = validate(edited))
                    ),
                message = null,
            )
        }
        saveDraftSoon()
    }

    /**
     * Why the entry as typed would be refused, or null.
     *
     * The two questions are asked in order and by different things, per CONTRIBUTING.md: a
     * parser answers "is this a number", and `SetValidator` answers "is this allowed". A field
     * that is empty is neither — it is a field the user has not reached yet — so it is not an
     * error until the set is submitted.
     */
    private fun validate(entry: SetEntry): SetEntryError? {
        val weight = weightField(entry.weight)
        if (weight.isMalformed) return SetEntryError.WeightNotANumber
        val reps = repsField(entry.reps)
        if (reps.isMalformed) return SetEntryError.RepsNotANumber

        val candidate = WorkoutSet(
            sessionExerciseId = 0,
            setNumber = 1,
            reps = reps.value,
            weight = weight.value,
            rpe = entry.rpe,
            setType = entry.setType,
            completedAt = 0,
            // Not flagged completed: `SetValidator` only asks for the exercise's own dimension
            // once a set is performed, and a row still being typed has not been. The bounds —
            // which are what a keystroke can violate — are checked either way.
            isCompleted = false,
            createdAt = 0,
            updatedAt = 0,
        )
        return SetValidator.validate(candidate).reasonOrNull()?.let(SetEntryError::Refused)
    }

    /** The weight field read as milligrams, in whatever unit the user is typing in. */
    private fun weightField(text: String): Field<Long> = when {
        text.isBlank() -> Field.absent()
        else -> Field.of(parseWeightMilligrams(text, unitSystem.value))
    }

    private fun repsField(text: String): Field<Int> = when {
        text.isBlank() -> Field.absent()
        else -> Field.of(text.trim().toIntOrNull())
    }

    /**
     * Writes one exercise's entry as a set.
     *
     * Guarded on [ScreenState.loggingExerciseId] rather than only by the disabled tick, for the
     * reason the new-session flow guards START: a double tap dispatched before recomposition
     * would otherwise reach the use case twice and log the set twice, which — unlike a refused
     * second start — the user would not notice until the recap.
     *
     * The guard is per exercise rather than screen-wide, because the rows are independent: a
     * write in flight against one member of a superset must not swallow the tick on the other.
     */
    private fun log(sessionExerciseId: Long) {
        val state = screenState.value
        if (!state.canLogNow(sessionExerciseId)) return

        val exercise = exerciseRow(sessionExerciseId) ?: return
        val entry = state.entryFor(sessionExerciseId)
        val exerciseType = catalogue.value?.exercises?.get(exercise.exerciseId)?.type

        screenState.update { it.copy(loggingExerciseId = sessionExerciseId) }

        viewModelScope.launch {
            val draft = ActiveSet(
                reps = repsField(entry.reps).value,
                weight = weightField(entry.weight).value,
                rpe = entry.rpe,
                setType = entry.setType,
                notes = entry.notes?.trim()?.ifEmpty { null },
            )

            logSet(exercise.id, draft, exerciseType)
                .onSuccess {
                    screenState.update { state ->
                        state.copy(
                            loggingExerciseId = null,
                            // Cleared except for the set type, which carries over: a lifter
                            // logging a warm-up is usually about to log another, and re-picking
                            // it every set is the sort of friction that gets a set logged wrong.
                            entries = state.entries + (
                                sessionExerciseId to
                                    SetEntry(setType = state.entryFor(sessionExerciseId).setType)
                                ),
                        )
                    }
                    saveDraft()
                }
                .onFailure { failure ->
                    // A refusal about the *set* is shown under the row, where the value that
                    // caused it still is. Anything else — a session that is no longer running,
                    // a store that cannot be written — has nothing to point at on the row and
                    // is said once instead.
                    val inline = failure.toEntryError()
                    screenState.update { state ->
                        val refused = state.entryFor(sessionExerciseId)
                        state.copy(
                            loggingExerciseId = null,
                            // The refused values stay in the fields: they are the only copy of
                            // what the user typed, and a row that cleared on refusal would make
                            // them enter it again to find out what was wrong with it.
                            entries = state.entries + (
                                sessionExerciseId to refused.copy(error = inline ?: refused.error)
                                ),
                            message = if (inline == null) ActiveSessionMessage.SET_REJECTED else null,
                        )
                    }
                }
        }
    }

    /**
     * One `session_exercise` row of this session, read from the store rather than from [uiState].
     *
     * Deliberately not `uiState.value`: that flow is shared with `WhileSubscribed`, so with
     * nothing collecting it — a test that has not started collecting, a screen mid-teardown — it
     * holds its *initial* value, and an action reading its own screen's state from it would
     * quietly do nothing.
     */
    private fun exerciseRow(sessionExerciseId: Long): SessionExercise? =
        loaded.value?.ordered?.firstOrNull { it.id == sessionExerciseId }

    // ─── The layout of the workout ──────────────────────────────────────────────────────

    /**
     * The eight events that change the workout's list of exercises, its order, or how it is
     * paced.
     *
     * Split off [onEvent] for the reason [onEntryEvent] and [onNameEvent] are: one `when` per
     * subject, so none of them branches twenty ways over four unrelated ones.
     *
     * @return whether [event] was one of them.
     */
    private fun onLayoutEvent(event: ActiveSessionEvent): Boolean {
        when (event) {
            is ActiveSessionEvent.ExercisesPicked -> addPicked(event.exerciseIds)
            is ActiveSessionEvent.ExerciseRemoved -> remove(event.sessionExerciseId)
            is ActiveSessionEvent.ExercisesReordered -> reorder(event.orderedSessionExerciseIds)
            is ActiveSessionEvent.ExerciseSwapped -> swap(event.sessionExerciseId, event.exerciseId)
            is ActiveSessionEvent.SupersetLinked -> regroup(event.sessionExerciseId, linked = true)
            is ActiveSessionEvent.SupersetUnlinked -> regroup(event.sessionExerciseId, linked = false)
            // Nothing to do for either: opening the picker is a navigation, and the destination
            // is what performs it. They exist so the screen has one thing to emit rather than a
            // callback threaded past the state — and, for the swap, so that which slot asked is
            // recorded where the answer will come back to. See `ActiveSessionRoute`.
            ActiveSessionEvent.AddExercisesRequested -> Unit
            is ActiveSessionEvent.ExerciseSwapRequested -> Unit
            else -> return false
        }
        return true
    }

    /**
     * Appends what the picker came back with.
     *
     * The ids are handed over as they arrived rather than deduplicated against what the session
     * already holds: the same exercise twice in one workout is legal and sometimes deliberate —
     * two bench slots either side of an accessory — and `session_exercise` is keyed on position
     * rather than on the exercise for exactly that reason.
     */
    private fun addPicked(exerciseIds: List<Long>) {
        val state = screenState.value
        if (exerciseIds.isEmpty() || state.isBusy || state.isAddingExercises) return
        screenState.update { it.copy(isAddingExercises = true, message = null) }

        viewModelScope.launch {
            addExercises(exerciseIds)
                .onSuccess { screenState.update { state -> state.copy(isAddingExercises = false) } }
                .onFailure {
                    screenState.update { state ->
                        state.copy(
                            isAddingExercises = false,
                            message = ActiveSessionMessage.ADD_EXERCISES_FAILED,
                        )
                    }
                }
            saveDraft()
        }
    }

    /**
     * Cuts an exercise out, and reports the one refusal that is worth explaining.
     *
     * An exercise with sets logged against it cannot go, because `session_exercise` cascades to
     * `workout_set` — the use case is where that rule lives and why. It is said rather than
     * hidden: a remove control that silently did nothing on the exercises the user has actually
     * worked is worse than one that says why.
     *
     * Its half-typed row goes with it. Nothing else would ever clear that entry — the card it
     * belonged to is gone — and a `session_exercise` id is not reused, so it would sit in the
     * map for the life of the screen and be written into every draft.
     *
     * Guarded per exercise, the way [log] is and for the same reason the rows are keyed at all:
     * two taps on one card's × dispatched before recomposition would otherwise reach the use
     * case twice, and the second call — against a row the first one deleted — is refused as
     * `ExerciseNotInSession` and reported as "it is still in the workout", over an exercise that
     * has already left. A removal aimed at a *different* card is a different row and still runs.
     */
    private fun remove(sessionExerciseId: Long) {
        val state = screenState.value
        if (state.isBusy || sessionExerciseId in state.removingExerciseIds) return
        screenState.update { it.copy(removingExerciseIds = it.removingExerciseIds + sessionExerciseId) }

        viewModelScope.launch {
            removeExercise(sessionExerciseId)
                .onSuccess {
                    screenState.update {
                        it.copy(
                            entries = it.entries - sessionExerciseId,
                            removingExerciseIds = it.removingExerciseIds - sessionExerciseId,
                        )
                    }
                    saveDraft()
                }
                .onFailure { failure ->
                    screenState.update {
                        it.copy(
                            removingExerciseIds = it.removingExerciseIds - sessionExerciseId,
                            message = when (failure) {
                                is SessionError.ExerciseHasLoggedSets ->
                                    ActiveSessionMessage.EXERCISE_HAS_SETS

                                else -> ActiveSessionMessage.REMOVE_EXERCISE_FAILED
                            },
                        )
                    }
                }
        }
    }

    /**
     * Writes the order the exercises will be performed in from now on.
     *
     * The whole order, because that is what the event carries and what the use case checks: a
     * list that is no longer this session's exercises is a screen working from a layout that has
     * changed under it, and it is refused rather than applied to whichever rows still match.
     *
     * Guarded on a flag of its own, the way [regroup] is: what is being written is the session's
     * layout, and a second drag let through mid-write would have been computed against the order
     * as it was before. Nothing is written back into [ScreenState] on success — `position` is a
     * column on `session_exercise`, which is read as a `Flow`, so the new order arrives the same
     * way a logged set does.
     */
    private fun reorder(orderedSessionExerciseIds: List<Long>) {
        val state = screenState.value
        if (state.isBusy || state.isReordering) return
        screenState.update { it.copy(isReordering = true, message = null) }

        viewModelScope.launch {
            reorderExercises(orderedSessionExerciseIds)
                .onSuccess { screenState.update { it.copy(isReordering = false) } }
                .onFailure {
                    screenState.update {
                        it.copy(
                            isReordering = false,
                            message = ActiveSessionMessage.REORDER_FAILED,
                        )
                    }
                }
            saveDraft()
        }
    }

    /**
     * Puts a different exercise in one slot of the workout.
     *
     * Its half-typed set does not travel with it: a weight standing in the field was typed for
     * the movement that was there, and carrying it over would log somebody's bench press weight
     * as their first set of incline. Cleared on success only — a refused swap leaves the slot
     * holding what it held, entry included.
     *
     * The refusal worth explaining is the one that is a rule: a slot with sets logged against it
     * cannot be swapped, because the sets would stay and be attributed to a movement they were
     * not performed on. See `SwapSessionExerciseUseCase`.
     */
    private fun swap(sessionExerciseId: Long, exerciseId: Long) {
        val state = screenState.value
        if (state.isBusy || state.swappingExerciseId != null) return
        screenState.update { it.copy(swappingExerciseId = sessionExerciseId, message = null) }

        viewModelScope.launch {
            swapExercise(sessionExerciseId, exerciseId)
                .onSuccess {
                    screenState.update {
                        it.copy(
                            swappingExerciseId = null,
                            entries = it.entries - sessionExerciseId,
                        )
                    }
                    saveDraft()
                }
                .onFailure { failure ->
                    screenState.update {
                        it.copy(
                            swappingExerciseId = null,
                            message = when (failure) {
                                is SessionError.ExerciseHasLoggedSets ->
                                    ActiveSessionMessage.SWAP_HAS_SETS

                                else -> ActiveSessionMessage.SWAP_FAILED
                            },
                        )
                    }
                }
        }
    }

    /**
     * Pairs an exercise with the one after it, or stops pairing them.
     *
     * One method for both directions because the two use cases have the same shape, the same
     * guard and the same failure — what the user tapped is a *join*, and its two states are the
     * same control. See `LinkSupersetUseCase` for why the pair is the unit of the operation.
     *
     * Nothing is written back into [screenState] on success: the grouping lives on
     * `session_exercise`, which is read as a `Flow`, so the labels and the joins on screen come
     * from the store the same way the sets do.
     */
    private fun regroup(sessionExerciseId: Long, linked: Boolean) {
        val state = screenState.value
        if (state.isBusy || state.isRegrouping) return
        screenState.update { it.copy(isRegrouping = true, message = null) }

        viewModelScope.launch {
            val outcome =
                if (linked) linkSuperset(sessionExerciseId) else unlinkSuperset(sessionExerciseId)

            outcome
                .onSuccess { screenState.update { it.copy(isRegrouping = false) } }
                .onFailure {
                    screenState.update {
                        it.copy(
                            isRegrouping = false,
                            message = ActiveSessionMessage.SUPERSET_FAILED,
                        )
                    }
                }
            saveDraft()
        }
    }

    /**
     * The four events that only concern what the session is called, handled together.
     *
     * Split off [onEvent] for the reason [onEntryEvent] is: one concern, one dialog, and a
     * `when` that branched twenty ways over four unrelated subjects was one nobody could read.
     *
     * @return whether [event] was one of them.
     */
    private fun onNameEvent(event: ActiveSessionEvent): Boolean {
        when (event) {
            // Seeded from what is stored, which is the name a user expects to see. Ignored
            // before the row has loaded — there is nothing to edit yet.
            ActiveSessionEvent.NameEditRequested -> loaded.value?.session?.let { session ->
                screenState.update { it.copy(nameDraft = session.name.orEmpty()) }
            }

            is ActiveSessionEvent.NameChanged ->
                screenState.update { it.copy(nameDraft = event.text) }

            ActiveSessionEvent.NameEditCancelled ->
                if (!screenState.value.isRenaming) {
                    screenState.update { it.copy(nameDraft = null) }
                }

            ActiveSessionEvent.NameConfirmed -> rename()
            else -> return false
        }
        return true
    }

    /**
     * Writes the name, leaving the dialog up and inert while the write is in flight.
     *
     * Closed on success only, and the new title is drawn from the re-read rather than copied
     * into local state — the same shape the recap's note save uses, and for the same reason:
     * two copies of one value are two things to keep in step.
     */
    private fun rename() {
        val draft = screenState.value.nameDraft ?: return
        if (screenState.value.isRenaming) return
        screenState.update { it.copy(isRenaming = true) }

        viewModelScope.launch {
            renameSession(sessionId, draft)
                .onSuccess { screenState.update { it.copy(nameDraft = null, isRenaming = false) } }
                .onFailure {
                    // The dialog stays open with the text still in it: it is the only copy of
                    // what the user wrote.
                    screenState.update {
                        it.copy(isRenaming = false, message = ActiveSessionMessage.RENAME_FAILED)
                    }
                }
        }
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────────────────

    /**
     * Suspends the workout, or picks it back up.
     *
     * The direction is taken from [ScreenState.pausedAt] — what the control the user tapped is
     * *drawn* as — rather than from the state machine, so a tap can never do the opposite of what
     * its label says. [realignPause] is what keeps the two answers the same; this is the half
     * that makes a disagreement visible as a refusal rather than as a pause the user asked to
     * end.
     */
    private fun togglePause() {
        if (screenState.value.isPausing) return
        val paused = screenState.value.pausedAt != null
        screenState.update { it.copy(isPausing = true) }

        viewModelScope.launch {
            // Wrapped the way [finish] and [discard] are, and for the same reason: these are
            // Room writes, a store that cannot be written throws rather than returning a
            // refusal, and there is no `CoroutineExceptionHandler` on this scope to catch it.
            val outcome = try {
                if (paused) resumeSession() else pauseSession()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                screenState.update {
                    it.copy(isPausing = false, message = ActiveSessionMessage.PAUSE_FAILED)
                }
                return@launch
            }

            outcome
                .onSuccess {
                    val now = clock.nowMillis()
                    screenState.update { state ->
                        if (paused) {
                            state.bankPause(now).copy(isPausing = false)
                        } else {
                            state.copy(isPausing = false, pausedAt = now)
                        }
                    }
                    saveDraft()
                }
                .onFailure {
                    screenState.update {
                        it.copy(isPausing = false, message = ActiveSessionMessage.PAUSE_FAILED)
                    }
                }
        }
    }

    /**
     * Closes the workout out and reports the session to recap.
     *
     * A refusal for having logged nothing is shown inline and goes no further —
     * `SessionError.NoSetsLogged` is not an error the user made, it is the screen saying there
     * is nothing to save yet, and the way past it is either a set or the discard action.
     */
    private fun finish() {
        // The set write is the one in-flight write a finish has to wait for. `LogSetUseCase`
        // reads the session state once and never re-checks it, so a finish that overtakes an
        // insert leaves the set in a session already stamped closed — undercounted in the
        // recap, or, if it was the only one, refused as a workout with nothing logged. END is
        // drawn inert for the same reason; this is what stops the event dispatched just before
        // it was.
        if (!screenState.value.canFinish) return
        screenState.update { it.copy(isFinishing = true, message = null) }

        viewModelScope.launch {
            val outcome = try {
                finishSession()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                screenState.update {
                    it.copy(isFinishing = false, message = ActiveSessionMessage.FINISH_FAILED)
                }
                return@launch
            }

            outcome
                // isFinishing stays true: the screen is on its way out, and re-enabling the
                // button for the frame before the pop invites a second finish.
                .onSuccess { finished ->
                    screenState.update { it.copy(finishedSessionId = finished.summary.sessionId) }
                }
                .onFailure { failure ->
                    screenState.update {
                        it.copy(
                            isFinishing = false,
                            message = when (failure) {
                                SessionError.NoSetsLogged -> ActiveSessionMessage.NO_SETS_LOGGED
                                else -> ActiveSessionMessage.FINISH_FAILED
                            },
                        )
                    }
                }
        }
    }

    private fun discard() {
        if (screenState.value.isBusy) return
        screenState.update { it.copy(isDiscarding = true, message = null) }

        viewModelScope.launch {
            val outcome = try {
                discardSession()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                screenState.update {
                    it.copy(
                        isDiscarding = false,
                        confirmingDiscard = false,
                        message = ActiveSessionMessage.DISCARD_FAILED,
                    )
                }
                return@launch
            }

            outcome
                // The dialog goes with it: the screen is on its way out, and a confirmation
                // whose buttons are both inert — `isDiscarding` stays true — would sit over the
                // whole pop transition.
                .onSuccess {
                    screenState.update { it.copy(isDiscarded = true, confirmingDiscard = false) }
                }
                .onFailure {
                    screenState.update { state ->
                        state.copy(
                            isDiscarding = false,
                            confirmingDiscard = false,
                            message = ActiveSessionMessage.DISCARD_FAILED,
                        )
                    }
                }
        }
    }

    // ─── The draft ──────────────────────────────────────────────────────────────────────

    /**
     * Puts the state machine back over a session that is still running on disk.
     *
     * A no-op in the ordinary case, where the user has just walked in from the new-session flow
     * and the state is already `Active` on this session. It matters after a process restart, and
     * after the user has been somewhere else in the app long enough for nothing to be holding
     * the state — see the class note.
     */
    private suspend fun reattach() {
        val current = sessionStateRepository.observe().first()
        val alreadyAttached = when (current) {
            is SessionState.Active -> current.sessionId == sessionId
            is SessionState.Paused -> current.sessionId == sessionId
            SessionState.Idle, is SessionState.Finished -> false
        }
        if (!alreadyAttached) {
            // Refusals are swallowed on purpose. The failure case is a session that is not in
            // progress at all — one finished in another process, say — and the screen already
            // draws that as a session that is gone, which is a better answer than a message
            // about a transition the user did not ask for.
            resumeSession()
        }
    }

    /**
     * Reads the stored draft back, if it describes this session.
     *
     * A draft naming another session is left alone rather than cleared: at most one session runs
     * at a time, so a draft for a different one means the screen was opened on a session it
     * should not have been, and deleting somebody's place in a workout to tidy up is the worse
     * of the two mistakes.
     */
    private suspend fun restoreDraft() {
        val draft = inProgressSessionRepository.load()?.takeIf { it.sessionId == sessionId }
            ?: return

        // One entry per exercise that was mid-set, from the trailing incomplete set the draft
        // format carries for exactly this. An exercise whose last set is completed was not being
        // typed into and gets nothing rather than an empty row.
        val entries = draft.exercises.mapNotNull { exercise ->
            val typed = exercise.sets.lastOrNull()?.takeIf { !it.isCompleted } ?: return@mapNotNull null
            val id = exercise.sessionExerciseId ?: return@mapNotNull null
            id to typed.toEntry(unitSystem.value)
        }.toMap()

        screenState.update { state ->
            state.copy(
                pausedDuration = draft.pausedDuration,
                pausedAt = draft.pausedAt,
                // Merged under what is already there rather than over it: a keystroke landing
                // between the screen opening and this read coming back is the user's, and the
                // draft is only ever the older copy of it.
                entries = entries + state.entries,
            )
        }
    }

    /**
     * Puts the state machine on the same side of the pause as the restored draft.
     *
     * [reattach] and [restoreDraft] answer two halves of a process restart and can disagree
     * about one thing: the state machine is in-memory, so a workout paused before the kill comes
     * back `Idle` and is resumed to `Active`, while the draft brings its `pausedAt` back and the
     * header draws it as paused. Left alone, that is a screen saying "paused" over a session
     * every write path considers running, and a resume control whose first tap *pauses* —
     * banking nothing and jumping the clock forward by however long the process was dead.
     *
     * The draft wins, because it is the copy that remembers what the user did. A refused
     * transition is the one case where it cannot: the state machine is what the writes check, so
     * the screen gives way and banks the stretch rather than showing a pause that is not real.
     */
    private suspend fun realignPause() {
        val pausedOnScreen = screenState.value.pausedAt != null
        val inDomain = sessionStateRepository.observe().first() as? SessionState.Paused
        if (pausedOnScreen == (inDomain != null)) return

        val refused = when {
            pausedOnScreen -> pauseSession().isFailure
            else -> resumeSession().isFailure
        }
        if (!refused) return

        screenState.update { state ->
            when (inDomain) {
                null -> state.bankPause(clock.nowMillis())
                else -> state.copy(pausedAt = inDomain.pausedAt)
            }
        }
        saveDraft()
    }

    /**
     * The save in flight or waiting, so the next one can supersede it.
     *
     * One job for both paths, which is what makes [saveDraft] cancel a [saveDraftSoon] that has
     * not fired: they write the same document from the same state, and the later call is always
     * the more current of the two.
     */
    private var draftSave: Job? = null

    /**
     * Writes the draft once the keystrokes stop.
     *
     * The typing path, and the only one that is debounced. Every character of the weight, the
     * reps, the RPE and the note routes through [editEntry], and an immediate save is a full
     * encode of the session, an `fd.sync()` and an atomic rename — typing `75` and then `10`
     * is four of those for one set, on the device's own storage, in the middle of a workout.
     * Nothing on screen reads the draft, so the delay costs nothing visible.
     *
     * What it does cost is the last [DRAFT_QUIET] of typing if the process dies inside that
     * window, which is the trade the debounce is: a half-typed weight, against writing the
     * whole workout to disk per keystroke. Everything that is not a keystroke — a set logged,
     * an exercise added or removed, a superset, a pause — still saves immediately through
     * [saveDraft], so nothing the user has *committed* is ever inside the window.
     */
    private fun saveDraftSoon() {
        draftSave?.cancel()
        draftSave = viewModelScope.launch {
            delay(DRAFT_QUIET)
            writeDraft()
        }
    }

    /**
     * Writes the draft now, superseding a debounced save that has not fired.
     *
     * Called after every change that is not a keystroke, which is what
     * `InProgressSessionRepository.save` is built for — it is documented as being called on
     * every logged set, and its atomicity requirement exists because of it.
     */
    private fun saveDraft() {
        draftSave?.cancel()
        draftSave = viewModelScope.launch { writeDraft() }
    }

    /**
     * The draft, whole.
     *
     * The draft's exercises are rebuilt from what is on disk plus the row being typed, rather
     * than maintained alongside it: the rows are the record, and a second copy of them here is
     * a second thing to keep in step.
     */
    private suspend fun writeDraft() {
        val session = loaded.value?.session ?: return
        val state = screenState.value
        // Nothing is written once the workout is on its way out. The layout actions save in
        // their coroutine tail, and their in-flight flags are not part of [ScreenState.isBusy],
        // so an append confirmed just before END returns after `FinishSessionUseCase` has
        // cleared the draft — and would write a new one naming a session that is over. That is
        // the draft-outliving-the-session state the use case exists to prevent.
        if (state.isBusy) return

        val draft = ActiveSession(
            sessionId = sessionId,
            startedAt = session.createdAt,
            pausedDuration = state.pausedDuration,
            pausedAt = state.pausedAt?.coerceAtLeast(session.createdAt),
            exercises = loaded.value?.draftExercises(
                entries = state.entries,
                unitSystem = unitSystem.value,
            ).orEmpty(),
            // Zero, always. The field records which exercise the user was looking at, and this
            // screen no longer has an answer to that — every exercise is on the page and every
            // one of them takes sets. What was being typed is not lost with it: each entry
            // travels on its own exercise, as an incomplete set.
            currentExerciseIndex = 0,
        )

        // Best-effort: the draft is a snapshot of a screen, and a save that fails costs the
        // user their place in the workout rather than any of their sets. Reporting it would be
        // a message about a mechanism they cannot act on.
        runCatching { inProgressSessionRepository.save(draft) }
    }

    // ─── Assembling the state ───────────────────────────────────────────────────────────

    private fun build(
        loaded: Loaded?,
        catalogue: Catalogue?,
        screen: ScreenState,
        now: Long,
    ): ActiveSessionUiState {
        val base = ActiveSessionUiState(
            nameDraft = screen.nameDraft,
            isRenaming = screen.isRenaming,
            isAddingExercises = screen.isAddingExercises,
            isRegrouping = screen.isRegrouping,
            isReordering = screen.isReordering,
            swappingExerciseId = screen.swappingExerciseId,
            isPaused = screen.pausedAt != null,
            loggingExerciseId = screen.loggingExerciseId,
            isFinishing = screen.isFinishing,
            isDiscarding = screen.isDiscarding,
            confirmingDiscard = screen.confirmingDiscard,
            finishedSessionId = screen.finishedSessionId,
            isDiscarded = screen.isDiscarded,
            message = screen.message,
        )

        if (loaded == null || catalogue == null) return base
        val session = loaded.session ?: return base.copy(isLoading = false)

        val rows = loaded.rows(catalogue, screen.entries)

        return base.copy(
            header = ActiveSessionHeader(
                name = session.name,
                startedAt = session.createdAt,
                elapsed = screen.elapsedAt(now, session.createdAt),
                progress = SessionProgress(
                    // "Done" is at least one *completed* set, which is the rule
                    // `SessionStats.exerciseBreakdown` applies four lines below over the same
                    // data. Counting every persisted set instead would let one that is logged
                    // but not ticked fill a sliver of the rail that the recap of the same
                    // workout leaves empty.
                    //
                    // One flag per exercise rather than a count, in the order they are
                    // performed, so that a reorder moves the marks with the exercises they are
                    // about — see [SessionProgress].
                    exercises = rows.map { it.completedSetCount > 0 },
                ),
            ),
            stats = SessionStatsCalculator.calculate(
                sets = loaded.sets,
                startedAt = session.createdAt,
                endedAt = now,
            ),
            exercises = rows,
            isLoading = false,
        )
    }

    /**
     * The state with its clocks read at [now], and every other figure left as assembled.
     *
     * The two fields a second changes, and they are not the same arithmetic: the header shows
     * time spent *working out*, which is wall clock less every paused stretch, and the stats
     * carry the raw window the session has been open for. Both are cheap, which is the whole
     * point of folding them on here rather than rebuilding the state around them.
     *
     * A screen with no header has nothing running to draw — the session is still loading, or it
     * is gone — and comes back untouched.
     */
    private fun ActiveSessionUiState.clockedAt(
        now: Long,
        screen: ScreenState,
    ): ActiveSessionUiState {
        val header = header ?: return this

        return copy(
            header = header.copy(elapsed = screen.elapsedAt(now, header.startedAt)),
            stats = stats?.copy(
                durationSeconds = SessionStatsCalculator.durationSeconds(header.startedAt, now),
            ),
        )
    }

    private companion object {
        /**
         * Long enough to survive a rotation without re-reading the session, short enough that a
         * backgrounded screen is not holding database observers open. The other screens' number,
         * for the same reasons.
         */
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds

        /** How often the elapsed clock is recomputed. It is drawn to the second. */
        val TICK = 1.seconds

        /**
         * How long the typing has to stop before the draft is written.
         *
         * Long enough that a number typed at speed is one save rather than one per digit, short
         * enough to be over before a lifter who has finished typing reaches the tick — which is
         * what makes the immediate save on [log] the one that matters.
         */
        val DRAFT_QUIET = 300.milliseconds
    }
}

/**
 * What the user is in the middle of.
 *
 * @param entries the half-typed set of each exercise, keyed by `session_exercise` id. Absent
 *   means an untouched row, which is why reads go through [entryFor] rather than the map: an
 *   exercise the user has not reached yet and one they have typed into and cleared are the same
 *   empty row, and storing a default for every exercise would be a second copy of the layout.
 */
private data class ScreenState(
    val entries: Map<Long, SetEntry> = emptyMap(),
    val nameDraft: String? = null,
    val isRenaming: Boolean = false,
    val isAddingExercises: Boolean = false,
    val isRegrouping: Boolean = false,
    val isReordering: Boolean = false,
    val swappingExerciseId: Long? = null,
    val removingExerciseIds: Set<Long> = emptySet(),
    val pausedDuration: Duration = Duration.ZERO,
    val pausedAt: Long? = null,
    val loggingExerciseId: Long? = null,
    val isPausing: Boolean = false,
    val isFinishing: Boolean = false,
    val isDiscarding: Boolean = false,
    val confirmingDiscard: Boolean = false,
    val finishedSessionId: Long? = null,
    val isDiscarded: Boolean = false,
    val message: ActiveSessionMessage? = null,
) {

    /** What is typed against one exercise, which for an untouched one is an empty row. */
    fun entryFor(sessionExerciseId: Long): SetEntry = entries[sessionExerciseId] ?: SetEntry()

    /**
     * The workout running again at [now], with the stretch just served added to the total.
     *
     * Banking it is what stops the elapsed clock jumping by the length of the pause when it
     * picks back up. A no-op on a session that was not paused, which is what makes it safe as
     * the one way out of [pausedAt] — the realignment on open uses it over a stretch the process
     * spent dead, and that stretch is exactly as much not-working-out as a deliberate pause is.
     */
    fun bankPause(now: Long): ScreenState = copy(
        pausedDuration = pausedDuration + (now - (pausedAt ?: now)).milliseconds,
        pausedAt = null,
    )

    /**
     * Whether one exercise's row as it stands can be written: something typed, nothing refused,
     * and no write already in flight against *that* exercise.
     */
    fun canLogNow(sessionExerciseId: Long): Boolean =
        loggingExerciseId != sessionExerciseId && !isBusy && entryFor(sessionExerciseId).canLog

    /**
     * Whether a write is already in flight or the workout is over.
     *
     * The guard every action shares, and the reason it is here rather than only on the state the
     * screen sees: a disabled button stops the second *tap*, this stops the second *event*, and
     * an event dispatched in the frame before a recomposition arrives whether the button was
     * redrawn or not.
     */
    val isBusy: Boolean
        get() = isFinishing || isDiscarding || finishedSessionId != null || isDiscarded

    /**
     * Whether the workout can be closed out right now.
     *
     * [ActiveSessionUiState.canFinish]'s twin, and here for the reason [isBusy] is here as well
     * as there: a disabled END stops the second *tap*, this stops the *event* dispatched in the
     * frame before it was redrawn. A set write in flight is the extra condition — see the note
     * on the state's own copy for what the race costs.
     */
    val canFinish: Boolean
        get() = !isBusy && loggingExerciseId == null

    /**
     * Time actually spent working out, at [now].
     *
     * The same arithmetic `ActiveSession.elapsedAt` does, over the fields the draft would carry,
     * so the clock on screen and the clock a restore comes back with are the same number. A
     * paused session measures to [pausedAt] instead of to now, which is what stops it ticking.
     */
    fun elapsedAt(now: Long, startedAt: Long): Duration {
        val until = pausedAt ?: now
        return ((until - startedAt).milliseconds - pausedDuration).coerceAtLeast(Duration.ZERO)
    }
}

/** The two catalogue-wide reads, as one value. */
private data class Catalogue(
    val exercises: Map<Long, Exercise> = emptyMap(),
    val muscleLinks: Map<Long, List<ExerciseMuscleGroupLink>> = emptyMap(),
)

/**
 * The session as it was read: the row, its exercises and its sets.
 *
 * @param session null until the read comes back **and** for an id that no longer resolves. The
 *   two are told apart one level up, by whether this value exists at all.
 */
private class Loaded(
    val session: WorkoutSession?,
    exercises: List<SessionExercise>,
    val sets: List<WorkoutSet>,
) {

    /** The session's exercises in the order they are performed, which is the `position` column. */
    val ordered: List<SessionExercise> = exercises.sortedBy(SessionExercise::position)

    private val setsByExercise: Map<Long, List<WorkoutSet>> =
        sets.groupBy(WorkoutSet::sessionExerciseId)
            .mapValues { (_, logged) -> logged.sortedBy(WorkoutSet::setNumber) }

    fun setsOf(sessionExerciseId: Long): List<WorkoutSet> =
        setsByExercise[sessionExerciseId].orEmpty()

    /**
     * One row per exercise, resolved against the catalogue, labelled with its superset and
     * carrying whatever is typed against it.
     *
     * The grouping is normalised before anything is read off it, matching the new-session flow:
     * a group left with one member is not a superset and must not be drawn as `A1` with no `A2`
     * under it, nor as a join the user could break.
     */
    fun rows(catalogue: Catalogue, entries: Map<Long, SetEntry>): List<SessionExerciseRow> {
        val grouping = Supersets.normalize(ordered.map(SessionExercise::supersetGroup))
        val labels = supersetLabels(grouping)
        val groupNames = supersetGroupNames(grouping)

        return ordered.mapIndexed { index, exercise ->
            SessionExerciseRow(
                sessionExerciseId = exercise.id,
                position = index,
                exercise = catalogue.exercises[exercise.exerciseId],
                muscleGroups = catalogue.muscleLinks[exercise.exerciseId].orEmpty(),
                supersetLabel = labels[index],
                supersetGroupName = groupNames[index],
                // Read off the neighbour rather than looked up by group, which is sound exactly
                // because members are contiguous — the same reason `Supersets.isRestDueAfter`
                // can. The last exercise is linked to nothing by construction.
                isLinkedToNext = grouping[index] != null &&
                    grouping[index] == grouping.getOrNull(index + 1),
                sets = setsOf(exercise.id),
                entry = entries[exercise.id] ?: SetEntry(),
            )
        }
    }

    /**
     * The session as the draft's own list of exercises, each with its own row being typed
     * appended to it.
     *
     * The un-submitted entry travels as an [ActiveSet] with `isCompleted = false`, which is what
     * the persistence contract calls a half-typed set: it contributes to no figure, and it is
     * exactly the state the contract exists to bring back. There can now be one per exercise,
     * which the format already allowed and which a superset makes ordinary — a lifter alternating
     * two exercises has a weight standing in both.
     */
    fun draftExercises(
        entries: Map<Long, SetEntry>,
        unitSystem: UnitSystem,
    ): List<ActiveExercise> = ordered.map { exercise ->
        val logged = setsOf(exercise.id).map(WorkoutSet::toActiveSet)
        val typed = entries[exercise.id]?.toActiveSets(unitSystem).orEmpty()
        ActiveExercise(
            exerciseId = exercise.exerciseId,
            sessionExerciseId = exercise.id,
            supersetGroup = exercise.supersetGroup,
            notes = exercise.notes,
            sets = logged + typed,
        )
    }
}

/**
 * A field's contents read as a number.
 *
 * Three states, not two, which is why a nullable will not do: a blank field is a dimension the
 * user has not filled in — a legal, absent value — while `null` back from a parser means the
 * text was not a number at all. Collapsing them makes an empty weight box indistinguishable
 * from `abc`, and only one of those is worth a red message under the row.
 */
private class Field<T> private constructor(val value: T?, val isMalformed: Boolean) {
    companion object {
        /** The field is empty: no value, and nothing wrong with it. */
        fun <T> absent(): Field<T> = Field(value = null, isMalformed = false)

        /** [parsed] as the parsers return it: null means the text was not a number. */
        fun <T> of(parsed: T?): Field<T> = Field(value = parsed, isMalformed = parsed == null)
    }
}

/** Which [SetEntryError] a refused write is, if it is one the row can show. */
private fun Throwable.toEntryError(): SetEntryError? =
    (this as? SessionError.SetRejected)?.let { SetEntryError.Refused(it.reason) }

/**
 * The entry as the draft's set, or nothing at all when nothing has been typed.
 *
 * A list rather than a nullable so the caller can concatenate it: an empty entry adds no set,
 * and a draft carrying an empty placeholder would restore a workout with a blank row appended
 * to the exercise every time it was saved.
 */
private fun SetEntry.toActiveSets(unitSystem: UnitSystem): List<ActiveSet> {
    val weightValue = weight.takeIf { it.isNotBlank() }?.let { parseWeightMilligrams(it, unitSystem) }
    val repsValue = reps.takeIf { it.isNotBlank() }?.trim()?.toIntOrNull()
    val note = notes?.trim()?.ifEmpty { null }

    // Nothing typed at all: the draft gets no set rather than an empty placeholder, which would
    // otherwise restore a workout with a blank row appended to the exercise every time it saved.
    if (listOfNotNull(weightValue, repsValue, rpe, note).isEmpty()) return emptyList()

    return listOf(
        ActiveSet(
            reps = repsValue,
            weight = weightValue,
            rpe = rpe,
            setType = setType,
            isCompleted = false,
            notes = note,
        ),
    )
}

/** A restored half-typed set as the fields it was typed into. */
private fun ActiveSet.toEntry(unitSystem: UnitSystem): SetEntry = SetEntry(
    weight = weight?.let { formatWeightValue(it, unitSystem) }.orEmpty(),
    reps = reps?.toString().orEmpty(),
    rpe = rpe,
    setType = setType,
    notes = notes,
)

/** A stored set as the draft's own, which is the same values without the row's identity. */
private fun WorkoutSet.toActiveSet(): ActiveSet = ActiveSet(
    id = id,
    reps = reps,
    weight = weight,
    duration = duration,
    distance = distance,
    heartRateAvg = heartRateAvg,
    heartRateMax = heartRateMax,
    restAfter = restAfter,
    rpe = rpe,
    setType = setType,
    isCompleted = isCompleted,
    completedAt = completedAt.takeIf { isCompleted },
    notes = notes,
)
