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
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.LoadSuggestion
import com.opengym.core.domain.model.MobilityExercise
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.SessionError
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionMovement
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.model.WarmupSuggestion
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import com.opengym.core.domain.session.IdleSessionMonitor
import com.opengym.core.domain.stats.SessionStatsCalculator
import com.opengym.core.domain.superset.Supersets
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.timer.BackgroundRest
import com.opengym.core.domain.timer.BackgroundRestTimer
import com.opengym.core.domain.timer.RestSkipRequests
import com.opengym.core.domain.timer.RestTimerController
import com.opengym.core.domain.timer.RestTimerSnapshot
import com.opengym.core.domain.timer.RestTimerState
import com.opengym.core.domain.usecase.AddSessionExercisesUseCase
import com.opengym.core.domain.usecase.DeleteSetUseCase
import com.opengym.core.domain.usecase.DiscardSessionUseCase
import com.opengym.core.domain.usecase.EditSetUseCase
import com.opengym.core.domain.usecase.ExercisePlacement
import com.opengym.core.domain.usecase.FinishSessionUseCase
import com.opengym.core.domain.usecase.LinkSupersetUseCase
import com.opengym.core.domain.usecase.LogSetUseCase
import com.opengym.core.domain.usecase.MarkSetCompletedUseCase
import com.opengym.core.domain.usecase.NextLoadUseCase
import com.opengym.core.domain.usecase.PauseSessionUseCase
import com.opengym.core.domain.usecase.RemoveSessionExerciseUseCase
import com.opengym.core.domain.usecase.RenameSessionUseCase
import com.opengym.core.domain.usecase.ReorderSessionExercisesUseCase
import com.opengym.core.domain.usecase.RestoreSetUseCase
import com.opengym.core.domain.usecase.ResumeSessionUseCase
import com.opengym.core.domain.usecase.SuggestWarmupUseCase
import com.opengym.core.domain.usecase.SwapSessionExerciseUseCase
import com.opengym.core.domain.usecase.UnlinkSupersetUseCase
import com.opengym.core.domain.validation.SetValidator
import com.opengym.core.ui.component.PreviousSet
import com.opengym.core.ui.component.SessionProgress
import com.opengym.core.ui.component.supersetGroupNames
import com.opengym.core.ui.component.supersetLabels
import com.opengym.core.ui.format.formatWeightValue
import com.opengym.core.ui.format.parseWeightMilligrams
import com.opengym.core.ui.state.savedScreenState
import com.opengym.feature.session.SessionProgressionHandoff
import com.opengym.feature.session.navigation.ActiveSessionDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
 * - **The parameter list.** Thirty constructor parameters, and every one is a graph edge: the
 *   saved state, three stores, the catalogue, the settings, seventeen use cases, the progression
 *   handoff, the records store, the rest timer, the idle monitor, the skip channel, a clock and a
 *   time source. Bundling them into a holder to satisfy a count would put a type in the way of
 *   reading what this screen depends on, which is the one thing the list is good for.
 * - **The function count, and the size that follows from it.** The handlers are already
 *   grouped — `onEntryEvent`, `onSetEditEvent`, `onNameEvent` and `onLayoutEvent` each own one
 *   subject — and what is left is one private method per thing the user can do. Splitting
 *   further means a second class over the same `MutableStateFlow`, which is two owners of one
 *   piece of state to satisfy a number.
 */
@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")
class ActiveSessionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val inProgressSessionRepository: InProgressSessionRepository,
    exerciseRepository: ExerciseRepository,
    userPreferencesRepository: UserPreferencesRepository,
    private val logSet: LogSetUseCase,
    private val editSet: EditSetUseCase,
    private val deleteSet: DeleteSetUseCase,
    private val restoreSet: RestoreSetUseCase,
    private val markSetCompleted: MarkSetCompletedUseCase,
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
    nextLoad: NextLoadUseCase,
    suggestWarmup: SuggestWarmupUseCase,
    private val progressionHandoff: SessionProgressionHandoff,
    personalRecordRepository: PersonalRecordRepository,
    private val backgroundRestTimer: BackgroundRestTimer,
    private val idleSessionMonitor: IdleSessionMonitor,
    restSkipRequests: RestSkipRequests,
    private val clock: Clock,
    timeSource: TimeSource,
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.toRoute<ActiveSessionDestination>().sessionId

    /**
     * The rest between two sets, counting down.
     *
     * On [viewModelScope], so the countdown stops when the screen is finally let go of and not
     * a moment before — a rest survives the activity being recreated for the same reason the
     * half-typed sets do. What it does *not* survive is the process, which is
     * [ScreenState.restSnapshot]'s job.
     *
     * [timeSource] is injected rather than defaulted because the default is wrong twice over:
     * `TimeSource.Monotonic` stops while an Android device sleeps, which is most of a rest with
     * the phone in a pocket, and a test needs virtual time. See `ElapsedRealtimeTimeSource`.
     */
    private val restTimer = RestTimerController(scope = viewModelScope, timeSource = timeSource)

    /**
     * How long a rest lasts when the exercise has no opinion of its own.
     *
     * Read off the settings rather than defaulted here, which is what `RestTimerController.start`
     * requires a duration for: a second default at this level is one a user who changed the
     * setting would silently keep getting.
     */
    private var defaultRest: Duration = UserPreferences.DEFAULT_REST_DURATION

    /**
     * The typed input this screen holds that the workout draft does not (#183).
     *
     * The draft carries almost all of it — the half-typed entry row under every exercise, the
     * rest countdown, how long the session has spent paused — because that is what it is for;
     * see `InProgressSessionRepository` and PERSISTENCE.md. Two things fall outside it, and both
     * are text somebody is in the middle of writing: the rename dialog's field, and the figures
     * typed over a set that is *already logged*, which the draft has no place for because the
     * set it is about is a row rather than an entry.
     *
     * Saved state is the right home for exactly those two and the wrong home for the rest. What
     * must not go in here is [ScreenState.entries]: a workout's worth of typed sets in a
     * `Bundle` is how a long session earns a `TransactionTooLargeException`, and the draft
     * already carries them somewhere with no such limit.
     */
    private val typedInput = savedStateHandle.savedScreenState(viewModelScope) { TypedInput() }

    /** What the user is in the middle of, which no store has an opinion about. */
    private val screenState = MutableStateFlow(
        ScreenState(
            nameDraft = typedInput.value.nameDraft,
            setEdits = typedInput.value.setEdits,
            collapsedMobility = typedInput.value.collapsedMobility,
            dismissedMobility = typedInput.value.dismissedMobility,
        ),
    )

    init {
        // One-way, and this is the only writer: [screenState] is the truth while the process
        // lives, and this is what is left of it when the process does not. A `MutableStateFlow`
        // conflates, so a burst of keystrokes costs one encode rather than one per character.
        viewModelScope.launch {
            screenState.collect {
                typedInput.value = TypedInput(
                    nameDraft = it.nameDraft,
                    setEdits = it.setEdits,
                    collapsedMobility = it.collapsedMobility,
                    dismissedMobility = it.dismissedMobility,
                )
            }
        }
    }

    /**
     * Orders [IdleSessionMonitor.arm] and [IdleSessionMonitor.disarm] against each other.
     *
     * `ScreenShown` and `ScreenHidden` each launch their call independently, and `arm` — unlike
     * `disarm` — suspends on two store reads before it touches the alarm. A quick hide-then-show
     * (a recents-app flick, a system dialog stealing focus) can therefore have the *later* call
     * finish *first*: `disarm` returns immediately while `arm` is still reading, so the alarm
     * `disarm` just cancelled comes back a moment later. Serializing the two calls behind one
     * lock, taken before either does anything, keeps them finishing in the order the events
     * arrived in rather than the order their work happened to complete in.
     */
    private val idleSessionMutex = Mutex()

    /**
     * The correction being written for each row, so the next one can queue behind it.
     *
     * Kept here rather than in [ScreenState] because it is not state the screen has a use for —
     * what a row *draws* while a correction is out is [ScreenState.savingSetIds]. These are the
     * handles, and there are two things done with them: waiting, in [enqueueSetEdit], and waiting
     * for all of them at once, in [flushSetEdits]. A plain map is enough because every touch of
     * it is on the main dispatcher, which is where `viewModelScope` puts them.
     */
    private val setEditWrites = mutableMapOf<Long, Job>()

    /**
     * What the last correction to each row actually wrote, for as long as the read is behind it.
     *
     * The store is the answer to "is there anything to write", and for a moment after a write it
     * is out of date: Room re-emits a query in its own time, so a correction queued behind one
     * that has just landed would look at a row still holding the old values and write the same
     * thing over again. This is what it is compared against instead. It goes the moment the read
     * catches up, which is the same moment the draft does — see [writeSetEdit].
     */
    private val setEditWritten = mutableMapOf<Long, ActiveSet>()

    /**
     * One renumbering of a set table at a time, deletions and undos together.
     *
     * Both [delete] and [restoreDeletedSet] read an exercise's table and then write numbers
     * computed from that reading, so two of them overlapping renumber one table from two
     * readings, and whichever lands second is computed against numbers that have already moved —
     * duplicate or gapped `set_number`s in a table nobody typed into. The in-flight flags each
     * one carries are what keep a *second tap* out; they say nothing about the other action, and
     * the pairing is reachable: an undo offer stands for several seconds, which is long enough
     * to swipe a second set away and then think better of the first.
     *
     * A lock rather than a third flag, because the undo is a snackbar action and a refused one
     * would be a tap that dismisses the offer and does nothing — the set the user asked for back
     * would simply be gone. Made to wait, it lands a moment later against a table that has
     * settled.
     */
    private val setTableWrites = Mutex()

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
        // The fourth read, and the one that is about a workout other than this one: what each
        // of these exercises did last time. One query for the whole session rather than a
        // lookup per exercise — see `SessionRepository.getPreviousSets` — and combined in here
        // rather than collected on its own so the reference and the sets it sits beside are
        // one value. A screen built from two flows would draw a `PREV` column for an exercise
        // that had already been swapped out of the slot.
        sessionRepository.getPreviousSets(sessionId),
    ) { session, exercises, sets, previous ->
        Loaded(session = session, exercises = exercises, sets = sets, previous = previous)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    /** The catalogue rows the session's exercises are drawn from. */
    private val catalogue: StateFlow<Catalogue?> = combine(
        exerciseRepository.getAll(),
        exerciseRepository.getAllMuscleGroupLinks(),
        personalRecordRepository.getHeadlineRecords(),
    ) { exercises, links, records ->
        Catalogue(
            exercises = exercises.associateBy(Exercise::id),
            muscleLinks = links,
            records = records,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

    /**
     * What the next set of each exercise should be (#M7-16), keyed by *catalogue* exercise id.
     *
     * One [NextLoadUseCase] flow per distinct exercise in the workout, held open for as long as
     * the screen is — which is what "cached in the ViewModel for the session's life" comes to.
     * It is keyed off [loaded] only through the *set* of exercise ids: a set logged, a rename or
     * a reorder re-emits `loaded` and changes nothing here, and a keystroke never reaches this
     * at all, because it is folded in downstream in [assembled] rather than recomputed there.
     * What does restart the read is the workout changing shape — an exercise added, swapped or
     * removed — since that is a new question.
     *
     * The use case excludes this session's own sets, so what a lifter logs today does not move
     * the target under their thumb; the engine is judging last time, not this time. And it is
     * answered per catalogue exercise rather than per slot on purpose: an exercise that appears
     * twice in one workout has one history, and asking twice would be two identical reads.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val targets: StateFlow<Map<Long, LoadSuggestion?>> = loaded
        .map { loaded -> loaded?.ordered?.mapTo(LinkedHashSet(), SessionExercise::exerciseId).orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { exerciseIds ->
            if (exerciseIds.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(exerciseIds.map { exerciseId -> nextLoad(exerciseId, sessionId) }) { suggestions ->
                    exerciseIds.zip(suggestions.asList()).toMap()
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyMap())

    /**
     * The workout as the warm-up planner reads it — the exercises, what they train, and what
     * each is about to be loaded with.
     *
     * Assembled here rather than read again in [SuggestWarmupUseCase] because this screen is
     * already holding every part of it: the exercises are [loaded]'s, the muscle links and
     * catalogue rows are [catalogue]'s, and the working weight is either the session's own sets
     * or the target [targets] already computed. A second set of observers on the same tables
     * would sometimes disagree with what is drawn.
     *
     * ### Why the working weight is read this way
     *
     * The heaviest weight the exercise's sets carry in *this* session — which for a workout
     * started from a template is the plan, laid down before the first rep — and failing that,
     * what the next-load engine says the next set should be. A session built by hand has no sets
     * at all when the card is first drawn, so without the second half there would be nothing to
     * take half of and the ramp-up would never appear outside a template.
     *
     * [distinctUntilChanged] is what keeps this from restarting the suggestion on every
     * keystroke: the value only moves when the workout's shape or its planned load does, and
     * both are rare.
     */
    private val movements: StateFlow<List<SessionMovement>> = combine(
        loaded,
        catalogue,
        targets,
    ) { loaded, catalogue, targets ->
        loaded?.movements(catalogue, targets).orEmpty()
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    /**
     * What to warm up with and what to stretch afterwards (#214), or an empty suggestion.
     *
     * Empty covers three different situations the screen does not have to tell apart: the
     * setting is off, the workout has nothing to warm up for, and the reads have not arrived.
     * All three draw no card.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val suggestion: StateFlow<WarmupSuggestion> = movements
        .flatMapLatest { movements ->
            if (movements.isEmpty()) flowOf(WarmupSuggestion()) else suggestWarmup(sessionId, movements)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), WarmupSuggestion())

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
     * The two things this screen works out from *outside* its own rows, as one value: what the
     * next set of each exercise should be, and what to warm up and stretch with.
     *
     * Paired rather than combined separately because [assembled] is already at `combine`'s five
     * typed arguments, and a sixth would drop it into the untyped `Array<Any?>` overload — five
     * casts in the place the whole screen is built. They belong together anyway: both are advice
     * about the workout rather than a reading of it.
     */
    private val advice: StateFlow<Advice> = combine(targets, suggestion) { targets, suggestion ->
        Advice(targets = targets, suggestion = suggestion)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), Advice())

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
        // Collected here as well as read synchronously, because it is now half of what a set
        // *reads* as: every logged row is drawn from the store through this, so a lifter who
        // switches to pounds mid-workout has to see the table they are looking at change.
        unitSystem,
        advice,
    ) { loaded, catalogue, screen, unitSystem, advice ->
        build(loaded, catalogue, screen, unitSystem, advice, clock.nowMillis())
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
            // What this collector has seen, rather than what [unitSystem] holds. The field opens
            // on the shipped default, so comparing against it would make the *first* read of a
            // lifter who trains in pounds look like a switch to pounds — and the clear below
            // would take the corrections restored from saved state with it. A first value is
            // not a change; only a second, different one is. See [typedInput].
            var seen: UnitSystem? = null
            userPreferencesRepository.observe().collect { preferences ->
                // Read before the return below, which is about the *unit system* changing: the
                // rest a set gets is a second setting off the same document, and a lifter who
                // lengthens their rests without also switching to pounds must still get them.
                defaultRest = preferences.defaultRestDuration
                val previous = seen
                seen = preferences.unitSystem
                unitSystem.value = preferences.unitSystem
                if (previous == null || previous == preferences.unitSystem) return@collect
                // Every correction half-typed into a logged row was seeded from the store in the
                // unit that has just stopped being the unit. Left alone, a row the user touched
                // would keep showing `70` next to rows that had become `154`. They all go, and
                // the rows fall back to reading themselves off the store.
                //
                // The ones being *written* go too, though this is the only copy of them: a write
                // already out carries its own values — it was handed a draft, not this map — so
                // what is dropped here is a number in the old unit that nothing needs any more.
                // Keeping it is worse than losing it, twice over: the row would go on drawing
                // `100` under a header that now says lb, and the next commit would read that
                // `100` back as pounds and quietly write 45.4 kg over a 100 kg set.
                setEditWritten.clear()
                screenState.update { it.copy(setEdits = emptyMap()) }
            }
        }
        // The countdown, published into the screen's state so the sheet and the header's pill
        // are drawn from the same value. Only the transitions are written down — the snapshot
        // the draft carries is a *deadline*, which a tick does not move — so this does not put
        // the store under a write a second. See [captureRest].
        viewModelScope.launch {
            restTimer.state.collect { rest ->
                screenState.update { it.copy(rest = rest) }
                // The one transition nothing commanded: a rest that simply ran out. Everything
                // else that lands here is captured by the command that caused it.
                if (rest is RestTimerState.Finished) captureRest()
            }
        }
        // "Skip rest", pressed on the notification rather than on the sheet. The durable half
        // of that press — the draft forgetting its countdown — is done by whoever handled the
        // broadcast, because it has to work with this screen destroyed; this is the live half,
        // and only this object can do it, since it owns the running countdown.
        //
        // Filtered on the id: at most one session runs at a time, but a notification can outlive
        // the workout it was posted for, and a press on a stale one must not cancel a rest in
        // the workout that replaced it.
        viewModelScope.launch {
            restSkipRequests.skipped.collect { skipped ->
                if (skipped == sessionId) skipRest()
            }
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
                val preferences = userPreferencesRepository.observe().first()
                unitSystem.value = preferences.unitSystem
                defaultRest = preferences.defaultRestDuration
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
        if (handledBySubject(event)) return

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

            // Nobody needs telling their workout is open while they are looking at it, and
            // leaving is the moment it becomes worth saying — see `IdleSessionMonitor`. Both
            // are wrapped: an alarm that could not be set is not worth taking a workout down
            // for, and neither is one that could not be cancelled.
            ActiveSessionEvent.ScreenShown ->
                viewModelScope.launch {
                    idleSessionMutex.withLock { runCatching { idleSessionMonitor.disarm() } }
                }

            ActiveSessionEvent.ScreenHidden ->
                viewModelScope.launch {
                    idleSessionMutex.withLock { runCatching { idleSessionMonitor.arm() } }
                }

            else -> Unit
        }
    }

    /**
     * Whether one of the four grouped handlers took [event].
     *
     * Named rather than written into the `if` above it, because four handlers chained with `||`
     * is a condition nobody reads as a condition: what it says is "this event belongs to a
     * subject", and the subjects are the four `when`s below.
     */
    private fun handledBySubject(event: ActiveSessionEvent): Boolean =
        onEntryEvent(event) || onSetEditEvent(event) || onNameEvent(event) ||
            onLayoutEvent(event) || onRestEvent(event)

    /**
     * The six events that only change the row being typed, handled together.
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

            is ActiveSessionEvent.TargetFilled -> fillTarget(event.sessionExerciseId)

            else -> return false
        }
        return true
    }

    /**
     * Puts the exercise's target into its entry row, as the text the lifter would have typed.
     *
     * Through [editEntry], so it is validated and drafted exactly as two keystrokes would be —
     * and refused exactly when they would be, on a paused or closing session. The weight is
     * formatted into the unit the field is typed in, which is also the form its placeholder
     * showed: what the tap writes is what the empty field was already saying. A field the
     * lifter has already typed into is theirs and is left alone — the tap fills what is still
     * blank, per field, the same as the placeholder it replaces. The rating, the type and the
     * note are the lifter's too, and the tick stays unpressed because a target is not a set —
     * see [ActiveSessionEvent.TargetFilled].
     *
     * Silent for an exercise with no target. The line that raises this is only drawn where
     * there is one, so reaching here without is an event dispatched a frame before the row was
     * redrawn without it.
     */
    private fun fillTarget(sessionExerciseId: Long) {
        val exercise = exerciseRow(sessionExerciseId) ?: return
        val target = targets.value[exercise.exerciseId] ?: return
        val weight = target.weight?.let { formatWeightValue(it, unitSystem.value) }.orEmpty()
        editEntry(sessionExerciseId) {
            it.copy(
                weight = it.weight.ifBlank { weight },
                reps = it.reps.ifBlank { target.reps.toString() },
            )
        }
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
    private fun editEntry(
        sessionExerciseId: Long,
        change: (SetEntry) -> SetEntry,
    ) {
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
    private fun weightField(text: String): Field<Long> =
        when {
            text.isBlank() -> Field.absent()
            else -> Field.of(parseWeightMilligrams(text, unitSystem.value))
        }

    private fun repsField(text: String): Field<Int> =
        when {
            text.isBlank() -> Field.absent()
            else -> Field.of(text.trim().toIntOrNull())
        }

    /**
     * Writes one exercise's entry as a set.
     *
     * Guarded on [ScreenState.loggingExerciseIds] rather than only by the disabled tick, for the
     * reason the new-session flow guards START: a double tap dispatched before recomposition
     * would otherwise reach the use case twice and log the set twice, which — unlike a refused
     * second start — the user would not notice until the recap.
     *
     * The guard is per exercise rather than screen-wide, because the rows are independent: a
     * write in flight against one member of a superset must not swallow the tick on the other.
     * It is a *set* of ids for the other half of that: both really can be in flight at once, and
     * a single id would be overwritten by the second tick and then cleared by whichever write
     * came back first — re-arming the other exercise's tick over an insert still running, which
     * is the double-logged set the guard exists to prevent.
     *
     * The entry is also checked against the exercise's logging mode, which is the half
     * [SetEntry.canLog] cannot see. Refused silently: the tick is not live for a set that fails
     * it, so reaching here means an event dispatched before the recomposition that disabled it.
     */
    private fun log(sessionExerciseId: Long) {
        val state = screenState.value
        val exercise = exerciseRow(sessionExerciseId) ?: return
        val entry = state.entryFor(sessionExerciseId)
        val exerciseType = catalogue.value?.exercises?.get(exercise.exerciseId)?.type

        if (!state.canLogNow(sessionExerciseId) || !entry.records(exerciseType)) return

        screenState.update { it.copy(loggingExerciseIds = it.loggingExerciseIds + sessionExerciseId) }

        viewModelScope.launch {
            val draft = ActiveSet(
                reps = repsField(entry.reps).value,
                weight = weightField(entry.weight).value,
                rpe = entry.rpe,
                setType = entry.setType,
                notes = entry.notes?.trim()?.ifEmpty { null },
            )

            logSet(exercise.id, draft, exerciseType)
                .onSuccess { logged ->
                    screenState.update { state ->
                        state.copy(
                            loggingExerciseIds = state.loggingExerciseIds - sessionExerciseId,
                            // Cleared except for the set type, which carries over: a lifter
                            // logging a warm-up is usually about to log another, and re-picking
                            // it every set is the sort of friction that gets a set logged wrong.
                            entries = state.entries + (
                                sessionExerciseId to
                                    SetEntry(setType = state.entryFor(sessionExerciseId).setType)
                                ),
                        )
                    }
                    // Before the save, which it makes redundant: [startRest] writes the draft
                    // itself, and it is the same draft with the countdown in it.
                    //
                    // The number of the set that was just written, plus one. Taken from the
                    // *write* rather than counted off the read flow: Room re-emits a query in
                    // its own time, so the table this screen can see is still one set short at
                    // this point — and the notification would say the lifter was coming back to
                    // the set they had just done.
                    startRest(sessionExerciseId, nextSetNumber = logged.setNumber + 1)
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
                            loggingExerciseIds = state.loggingExerciseIds - sessionExerciseId,
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

    // ─── Correcting a set that is already logged ────────────────────────────────────────

    /**
     * The events about a set that has already been written, handled together.
     *
     * Split off [onEvent] for the reason [onEntryEvent] is, and every one of them names its own
     * row: there is no open editor for them to be about. Four of them type into a row and a
     * fifth writes what was typed; the rest change a row without typing at all — the drag that
     * deletes one, the tick that un-performs one, and the undo, whose row has gone altogether.
     * The delete and the undo especially have to be read together to see that neither of them is
     * a confirmation dialog.
     *
     * @return whether [event] was one of them.
     */
    private fun onSetEditEvent(event: ActiveSessionEvent): Boolean {
        when (event) {
            is ActiveSessionEvent.SetEditWeightChanged ->
                typeIntoSet(event.setId) { it.copy(weight = event.text) }

            is ActiveSessionEvent.SetEditRepsChanged ->
                typeIntoSet(event.setId) { it.copy(reps = event.text) }

            // Picked, not typed, so there is nothing to leave the row to finish — see
            // [ActiveSessionEvent.SetEditRpeChanged]. The change and the write are one event.
            is ActiveSessionEvent.SetEditRpeChanged -> {
                typeIntoSet(event.setId) { it.copy(rpe = event.rpe) }
                commitSet(event.sessionExerciseId, event.setId)
            }

            is ActiveSessionEvent.SetEditTypeChanged -> {
                typeIntoSet(event.setId) { it.copy(setType = event.setType) }
                commitSet(event.sessionExerciseId, event.setId)
            }

            is ActiveSessionEvent.SetEditCommitted ->
                commitSet(event.sessionExerciseId, event.setId)

            is ActiveSessionEvent.SetDeleted -> delete(event.sessionExerciseId, event.setId)

            is ActiveSessionEvent.SetCompletionToggled ->
                toggleCompletion(event.sessionExerciseId, event.setId, event.isCompleted)

            ActiveSessionEvent.SetDeleteUndone -> restoreDeletedSet()

            ActiveSessionEvent.SetDeleteDismissed ->
                screenState.update { it.copy(deletedSet = null) }

            else -> return false
        }
        return true
    }

    /**
     * Applies [change] to one logged row and re-validates it, against the same rules the entry
     * row is checked against.
     *
     * The first keystroke on an untouched row seeds it from what is stored, which is why this
     * reaches for the set rather than only for the map: until the user types, a logged row has no
     * state of its own — its fields are read off the store every time the screen is built, so
     * there is nothing to keep in step. The weight is seeded formatted into the unit it will be
     * typed in, the way a restored draft is: what comes out of the store is milligrams, and a
     * lifter on pounds correcting their 175 lb set must not find `79.4` in the field.
     *
     * No draft is saved. The draft carries the workout as it stands plus what is being *typed
     * into* it, and a correction being typed is neither: the set it is about is already a row on
     * disk, and a half-typed correction restored after a process kill would put values back over
     * a set the user has no memory of touching.
     */
    private fun typeIntoSet(
        setId: Long,
        change: (SetEntry) -> SetEntry,
    ) {
        val stored = loaded.value?.setById(setId) ?: return

        screenState.update { state ->
            val edited = change(state.setEdits[setId] ?: stored.toEntry(unitSystem.value))
            state.copy(
                setEdits = state.setEdits + (setId to edited.copy(error = validate(edited))),
                message = null,
            )
        }
    }

    /**
     * Writes one logged row over the set it was read from, if there is anything to write.
     *
     * Raised when the row is left rather than by a button, because there is no button: the row is
     * the editor and it is never closed — see [ActiveSessionEvent.SetEditCommitted]. That makes
     * this the one write on the screen the user does not press anything to cause, so it is
     * deliberately quiet in every case where there is nothing to say:
     *
     * - **Untouched.** No draft, so nothing was typed. The commonest case by far, since focus
     *   passes through rows a lifter is only reading.
     * - **Unchanged.** Typed into and typed back, or the field was tabbed through. Compared
     *   against the *stored* values rather than against the text, because `70` and `70.0` are one
     *   set, and a write per glance is a write the store does not need. The draft goes with it,
     *   so the row falls back to reading itself off the store.
     * - **Not a set.** `SetValidator` would refuse it. The draft *stays*, with the reason under
     *   the row, because it is the only copy of what the user typed — dropping it here would
     *   silently undo a correction the moment they looked away from a typo.
     *
     * The values that are not on screen travel with it. `EditSetUseCase` replaces every dimension
     * of the set from what it is handed, so the draft is built from the *stored* row with the four
     * edited fields laid over it — anything else would quietly clear the note the user typed when
     * they logged it, and the rest a set can carry.
     *
     * Those cases are decided in [writeSetEdit], which is where the write actually is; this is
     * the gate in front of it. Reached through [enqueueSetEdit], so that two corrections to one
     * row take turns rather than race. Refused outright over a workout on its way out, which
     * [finish] makes safe by flushing what is held before it closes the session.
     */
    private fun commitSet(
        sessionExerciseId: Long,
        setId: Long,
    ) {
        val state = screenState.value
        if (state.isBusy || setId !in state.setEdits) return
        enqueueSetEdit(sessionExerciseId, setId)
    }

    /**
     * Puts one row's correction behind whatever is already being written for that row.
     *
     * Queued rather than dropped, which is the difference between a slow write and a lost one.
     * The two menus write on the pick — see [ActiveSessionEvent.SetEditRpeChanged] — so a rating
     * and a set type chosen one after the other are two commits a hundred milliseconds apart, and
     * the second one lands while the first is still out. Refusing it there would leave the row
     * *showing* the type the user picked, from a draft, over a store that never heard about it.
     *
     * Per row, because that is the width of the hazard: the writes race only with each other, and
     * two rows being corrected have nothing to say to one another. What is queued is the row, not
     * the values — [writeSetEdit] reads the draft when its turn comes, so a queue three deep on
     * one row collapses into one write of what the row finally says, and the rest find it already
     * written and clear the draft.
     */
    private fun enqueueSetEdit(
        sessionExerciseId: Long,
        setId: Long,
    ) {
        val previous = setEditWrites[setId]
        // Started only once it is in the map, so a write that gets all the way through without
        // suspending cannot tidy away an entry that has not been made yet.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                writeSetEdit(sessionExerciseId, setId)
            } finally {
                setEditWrites.remove(setId, coroutineContext.job)
            }
        }
        setEditWrites[setId] = job
        job.start()
    }

    /**
     * Writes what the row says *now* over the set it was read from, if there is anything to write.
     *
     * The draft is read here rather than handed in because this can have waited: see
     * [enqueueSetEdit]. By the time a queued write runs, the row may have been typed into again,
     * corrected back to what it was, deleted, or emptied by a switch of units — and every one of
     * those is answered by asking the state again instead of writing what was true when the user
     * left the row.
     */
    private suspend fun writeSetEdit(
        sessionExerciseId: Long,
        setId: Long,
    ) {
        val entry = screenState.value.setEdits[setId] ?: return
        val target = editTarget(sessionExerciseId, setId, entry) ?: return
        val draft = target.stored.toActiveSet().copy(
            weight = weightField(entry.weight).value,
            reps = repsField(entry.reps).value,
            rpe = entry.rpe,
            setType = entry.setType,
        )
        if (draft.matches(target.stored)) {
            screenState.update { it.copy(setEdits = it.setEdits - setId) }
            setEditWritten.remove(setId)
            return
        }
        // Already written, by the commit this one queued behind, and the read has not come round
        // yet. The draft stays: it is still what the row draws until the store agrees with it.
        if (setEditWritten[setId]?.let(draft::matches) == true) return

        screenState.update { it.copy(savingSetIds = it.savingSetIds + setId, message = null) }

        editSet(sessionExerciseId, setId, draft, target.exerciseType)
            // The draft is *kept* on success rather than dropped. What it holds is what was
            // just written, so it draws the same row either way — and dropping it here would
            // fall back to the store for the frame or two before the read comes round with
            // the new values, which is the old weight flashing back under the user's thumb.
            // The next commit finds it unchanged and clears it.
            .onSuccess {
                setEditWritten[setId] = draft
                screenState.update { it.copy(savingSetIds = it.savingSetIds - setId) }
                saveDraft()
            }
            .onFailure { failure ->
                val inline = failure.toEntryError()
                screenState.update { current ->
                    // The row can have gone while the write was out — deleted, or its
                    // exercise removed. There is nothing left to put an inline refusal
                    // under, so it is said once in the bar instead; silence would be a
                    // correction the user believes they made.
                    val refused = current.setEdits[setId] ?: return@update current.copy(
                        savingSetIds = current.savingSetIds - setId,
                        message = ActiveSessionMessage.SET_EDIT_REJECTED,
                    )
                    current.copy(
                        savingSetIds = current.savingSetIds - setId,
                        setEdits = current.setEdits +
                            (setId to refused.copy(error = inline ?: refused.error)),
                        message = if (inline == null) {
                            ActiveSessionMessage.SET_EDIT_REJECTED
                        } else {
                            null
                        },
                    )
                }
            }
    }

    /**
     * Writes every correction still held on screen, and does not come back until they have landed.
     *
     * The one caller is [finish], and what it needs is the *store* right — the recap it opens is
     * built from what `FinishSessionUseCase` reads, so a correction still in the map when that
     * read happens is a set the user watched themselves change and will not find. Waiting is
     * therefore the point rather than an implementation detail.
     *
     * It goes through [enqueueSetEdit] like every other commit, so a row already being written
     * is not written twice over — it is joined. The loop is what covers the queue growing while
     * it drains: a row waiting behind a slow write is not in the map yet when the round it
     * belongs to begins, and a job takes itself out of the map before its `join` returns, so an
     * empty map is the honest end of it. Rows whose values `SetValidator` refuses write nothing
     * and hold nothing up, exactly as they do when the row is left — the session closes on what
     * is stored, which for those rows is what was there before.
     */
    private suspend fun flushSetEdits() {
        screenState.value.setEdits.keys.forEach { setId ->
            val exerciseId = loaded.value?.setById(setId)?.sessionExerciseId ?: return@forEach
            enqueueSetEdit(exerciseId, setId)
        }
        while (true) {
            val pending = setEditWrites.values.toList()
            if (pending.isEmpty()) return
            pending.forEach { it.join() }
        }
    }

    /**
     * The stored row a correction is about and how its exercise is logged, or null when there is
     * nothing to write.
     *
     * Three ways there is nothing: the exercise has left the workout, the set has, or the values
     * as typed are not a set the write path would take. All three are silent, and the third is
     * the ordinary one — a row left mid-correction, with the reason already under it. See
     * [commitSet].
     */
    private fun editTarget(
        sessionExerciseId: Long,
        setId: Long,
        entry: SetEntry,
    ): EditTarget? {
        val exercise = exerciseRow(sessionExerciseId) ?: return null
        val exerciseType = catalogue.value?.exercises?.get(exercise.exerciseId)?.type
        if (!entry.canLog || !entry.records(exerciseType)) return null

        return loaded.value?.setsOf(sessionExerciseId)
            ?.firstOrNull { it.id == setId }
            ?.let { EditTarget(stored = it, exerciseType = exerciseType) }
    }

    /**
     * Takes one set out of the workout, and holds it for the undo.
     *
     * Named rather than derived from what the user was typing, because what raises it is a drag
     * across a row — see `SwipeToDelete` — and the row dragged is as likely as not to be one
     * nobody has touched.
     *
     * A half-typed correction *to that row* goes straight away rather than on the write coming
     * back: the row it belongs to is on its way out, and a draft outliving it would be typing
     * held against a set that is no longer in the table. One against another row is left exactly
     * as it is: a correction being typed into set 4 is not something a deletion of set 2 should
     * throw away. The offer to undo is what covers a mistake, and it goes up with the set in hand
     * — see [ActiveSessionUiState.deletedSet].
     *
     * One at a time, which [ScreenState.isDeletingSet] is for: two deletions in flight against one
     * exercise renumber the same table from two readings of it, and the second one to land would
     * be computed against numbers that have already moved. An undo is the same write from the
     * other end and is held apart from this one by [setTableWrites].
     */
    private fun delete(
        sessionExerciseId: Long,
        setId: Long,
    ) {
        val state = screenState.value
        if (state.isBusy || state.isDeletingSet) return
        setEditWritten.remove(setId)
        screenState.update {
            it.copy(
                isDeletingSet = true,
                setEdits = it.setEdits - setId,
                message = null,
            )
        }

        viewModelScope.launch {
            setTableWrites.withLock { deleteSet(sessionExerciseId, setId) }
                // A renumbering that failed over a set that has already gone is still a set that
                // has gone, and the offer is the only copy of it left — so it goes up exactly as
                // it would have on success. `DELETE_SET_FAILED` says the set is still in the
                // workout, which on this branch it is not, and dropping the undo here would lose
                // it for good. The gap left in the numbers is closed by the next write to that
                // table; a set nobody was offered back is not.
                .recoverCatching { failure ->
                    (failure as? SessionError.SetTableNotRenumbered)?.set ?: throw failure
                }
                .onSuccess { deleted ->
                    screenState.update { it.copy(isDeletingSet = false, deletedSet = deleted) }
                    saveDraft()
                }
                .onFailure {
                    screenState.update {
                        it.copy(
                            isDeletingSet = false,
                            message = ActiveSessionMessage.DELETE_SET_FAILED,
                        )
                    }
                }
        }
    }

    /**
     * Puts the last deleted set back where it was.
     *
     * The offer is consumed on the way in rather than on the way back, because it is an offer
     * rather than a record: a second tap on an undo that is still on screen would restore the
     * set twice, and the user would have to delete one of them to find out.
     *
     * Taken straight away and written when the table is free — see [setTableWrites]. A deletion
     * still in flight is a renumbering of the same table, so the two are made to take turns
     * rather than to race; from the user's side the offer is accepted the moment it is tapped
     * either way.
     */
    private fun restoreDeletedSet() {
        val state = screenState.value
        val deleted = state.deletedSet ?: return
        // Withdrawn rather than ignored. Every other control on this screen goes inert over a
        // workout that is finishing, being discarded or over, but the undo is a snackbar action
        // and nothing disables one — so an offer left standing here would be a button whose only
        // outcome is nothing happening, over a set `RestoreSetUseCase` would refuse to write
        // back anyway. The screen withdraws it on sight for the same reason; this is the tap
        // that beat it by a frame.
        if (state.isBusy) {
            screenState.update { it.copy(deletedSet = null) }
            return
        }
        if (state.isRestoringSet) return
        screenState.update { it.copy(isRestoringSet = true, deletedSet = null, message = null) }

        viewModelScope.launch {
            setTableWrites.withLock { restoreSet(deleted.sessionExerciseId, deleted) }
                .onSuccess {
                    screenState.update { it.copy(isRestoringSet = false) }
                    saveDraft()
                }
                .onFailure { failure ->
                    screenState.update {
                        it.copy(
                            isRestoringSet = false,
                            // Two failures with opposite advice in them. `RESTORE_SET_FAILED`
                            // says to log the set again, which is right only where nothing was
                            // written; over a set the insert did land for it would have the user
                            // record it twice.
                            message = when (failure) {
                                is SessionError.SetTableNotRenumbered ->
                                    ActiveSessionMessage.SET_RESTORED_AT_END

                                else -> ActiveSessionMessage.RESTORE_SET_FAILED
                            },
                        )
                    }
                }
        }
    }

    /**
     * Ticks a logged set off, or takes the tick back off it.
     *
     * The one write on this screen that changes a set without anything being typed, and the only
     * one that goes *back*: `MarkSetCompletedUseCase` is what un-ticks a set that was ticked by
     * accident, leaving the row in its table and out of every figure.
     *
     * Nothing is held on screen for it. There is no draft of a tick — the row is the record, and
     * the store re-emits — and there is no undo, because the action is its own undo: the control
     * that took the tick off is the control that puts it back, in the same place, one tap later.
     *
     * The exercise's logging mode travels with it so a set being marked *performed* is held to the
     * completeness rule every other write path holds it to. Un-ticking is never refused for it —
     * see `SetValidator`.
     */
    private fun toggleCompletion(
        sessionExerciseId: Long,
        setId: Long,
        isCompleted: Boolean,
    ) {
        val state = screenState.value
        if (state.isBusy || setId in state.togglingSetIds) return
        val exerciseType = exerciseTypeOf(sessionExerciseId)

        screenState.update {
            it.copy(togglingSetIds = it.togglingSetIds + setId, message = null)
        }

        viewModelScope.launch {
            markSetCompleted(sessionExerciseId, setId, isCompleted, exerciseType)
                .onSuccess {
                    screenState.update { it.copy(togglingSetIds = it.togglingSetIds - setId) }
                    if (isCompleted) {
                        // The same event [log] raises it on: a set has been marked done, and
                        // what follows a set that is done is a rest. A set being *un*-ticked is
                        // not the opposite — it is a correction to a row logged some time ago,
                        // and cancelling a rest the user is in the middle of because of it would
                        // be the screen reading a typo as a decision.
                        //
                        // The ticked row is already on disk, so its number is readable without
                        // waiting for anything — unlike [log], where the set has only just been
                        // written.
                        startRest(
                            sessionExerciseId,
                            nextSetNumber = loaded.value?.setById(setId)?.setNumber?.plus(1),
                        )
                    } else {
                        // Rewritten the way it is after every other write to the workout: the
                        // draft carries the sets as they stand alongside what is being typed,
                        // and a tick is a change to the first half of that.
                        saveDraft()
                    }
                }
                .onFailure {
                    screenState.update {
                        it.copy(
                            togglingSetIds = it.togglingSetIds - setId,
                            message = ActiveSessionMessage.SET_COMPLETION_FAILED,
                        )
                    }
                }
        }
    }

    /**
     * How one exercise of this session is logged, or null when the catalogue has no row for it.
     *
     * Null is a real answer rather than a missing one: an exercise deleted from the library since
     * the session started still has its sets, and what a set of it must record is a question with
     * nobody left to answer it. Every use case takes null to mean "check the bounds alone".
     */
    private fun exerciseTypeOf(sessionExerciseId: Long): ExerciseType? {
        val exercise = exerciseRow(sessionExerciseId) ?: return null
        return catalogue.value?.exercises?.get(exercise.exerciseId)?.type
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

    // ─── The rest between two sets ──────────────────────────────────────────────────────

    /**
     * The five events about the countdown, handled together.
     *
     * Split off [onEvent] for the reason the other four groups are, and they belong together
     * for a reason of their own: three of them command the timer and two only say whether the
     * sheet is on screen, and the whole design of this feature is that those are separate
     * questions. A swipe that cancelled the rest, or a skip that only hid the sheet, would each
     * be one line's confusion between the two halves.
     *
     * @return whether [event] was one of them.
     */
    private fun onRestEvent(event: ActiveSessionEvent): Boolean {
        when (event) {
            is ActiveSessionEvent.RestAdjusted -> {
                restTimer.adjust(event.delta)
                captureRest()
            }

            ActiveSessionEvent.RestPauseToggled -> {
                // Read off the timer rather than off what the button was drawn as, which is
                // safe here in a way the *session's* pause is not: the two answers cannot
                // disagree, because nothing but this object writes the countdown.
                if (restTimer.state.value is RestTimerState.Running) {
                    restTimer.pause()
                } else {
                    restTimer.resume()
                }
                captureRest()
            }

            ActiveSessionEvent.RestSkipped -> skipRest()

            // The countdown is untouched by both of these. What the sheet is doing on screen
            // and what the timer is doing are two facts, and the header's pill exists because
            // of it.
            ActiveSessionEvent.RestSheetDismissed ->
                screenState.update { it.copy(isRestSheetOpen = false) }

            ActiveSessionEvent.RestSheetRequested ->
                screenState.update { it.copy(isRestSheetOpen = true) }

            else -> return false
        }
        return true
    }

    /**
     * Starts the rest that follows a set on [sessionExerciseId], and raises the sheet over it.
     *
     * Called from the two places a set becomes *done* — [log] and the ticking half of
     * [toggleCompletion] — rather than from an event of its own, because starting a rest is not
     * something a lifter asks for. It is what finishing a set means.
     *
     * ### Where no rest is started
     *
     * After the first exercise of a superset. Its members are alternated set for set, so what
     * follows a set on `A1` is a set on `A2` and not a pause — `Supersets.isRestDueAfter` is the
     * same rule `ActiveSession` states, asked here of the session as it stands on disk. The rest
     * comes after the *last* member of the group, where this returns true again.
     *
     * ### How long
     *
     * The exercise's own `defaultRest` if it carries one, and the user's setting otherwise. In
     * seconds, per PERSISTENCE.md's units table. A stored rest of zero or less is treated as no
     * opinion rather than as an instant rest: the column is the user's to fill in and a zero in
     * it is far likelier to be an empty answer than a request for no rest at all.
     */
    private fun startRest(
        sessionExerciseId: Long,
        nextSetNumber: Int?,
    ) {
        val duration = restAfter(sessionExerciseId)
        if (duration == null) {
            // No rest here, but a set was still logged: the draft is written either way, since
            // what changed is the entry row this screen has just cleared. [captureRest] is the
            // same save with the countdown in it.
            saveDraft()
            return
        }

        restTimer.start(duration)
        screenState.update {
            it.copy(
                isRestSheetOpen = true,
                restExerciseId = sessionExerciseId,
                restNextSetNumber = nextSetNumber,
            )
        }
        captureRest()
    }

    /**
     * How long the rest after a set on [sessionExerciseId] should be, or **null** where there
     * is no rest to start.
     *
     * Null covers the two of them: an exercise this session does not have — a set logged
     * against a slot cut out from under it, which is a race rather than a state — and the first
     * member of a superset, which is the one that matters. See [startRest].
     */
    private fun restAfter(sessionExerciseId: Long): Duration? {
        val ordered = loaded.value?.ordered ?: return null
        val index = ordered.indexOfFirst { it.id == sessionExerciseId }
        if (index < 0) return null
        if (!Supersets.isRestDueAfter(ordered.map(SessionExercise::supersetGroup), index)) return null

        return catalogue.value?.exercises?.get(ordered[index].exerciseId)
            ?.defaultRest
            ?.takeIf { it > 0 }
            ?.seconds
            ?: defaultRest
    }

    /**
     * Ends the rest early, which is the user saying they are back under the bar.
     *
     * `cancel` rather than letting it fall through to finished, per `RestTimerController`: a
     * rest that was dismissed did not run out, and the alert on the screen rings for one and
     * not the other.
     */
    private fun skipRest() {
        restTimer.cancel()
        screenState.update {
            it.copy(isRestSheetOpen = false, restExerciseId = null, restNextSetNumber = null)
        }
        captureRest()
    }

    /**
     * Writes down where the rest is now, as the deadline the draft carries.
     *
     * Called after every command and on the one transition no command causes — see the collector
     * in `init`. Never on a tick, which is the whole point of persisting an *instant*: the
     * deadline of a rest that is counting down does not change as it counts, so a draft written
     * once at the start of it is still right ninety seconds later.
     *
     * The reading is taken from the controller rather than from [ScreenState.rest], and taken
     * *now*: a command publishes its new state before it returns, so this sees the exact
     * remainder — including the fraction of a second the last tick has not caught up with —
     * while the copy in the screen's state is at most one tick behind. Deriving the deadline
     * from that copy would push it out by up to a second every time the draft was written, and
     * the draft is written on every keystroke.
     */
    private fun captureRest() {
        val snapshot = RestTimerSnapshot.of(restTimer.state.value, clock.nowMillis())
        screenState.update { it.copy(restSnapshot = snapshot) }
        saveDraft()
        publishRest(snapshot)
    }

    /**
     * Publishes the rest to the operating system: the note in the shade, and the alarm.
     *
     * Called from [captureRest] and therefore from exactly the same places — every command the
     * user gives the timer, and the one transition no command causes. That is deliberate rather
     * than convenient: the *deadline* handed to the alarm is the very [RestTimerSnapshot] the
     * draft is written from, so the two cannot disagree about when the rest ends, and a tick
     * does not reach here at all. See `BackgroundRestTimer`, which is posted once and ticked by
     * the platform.
     *
     * Failures are swallowed. The alarm and the note are a convenience over a countdown that is
     * running correctly either way, and a workout is not worth losing to a notification manager
     * that refused.
     *
     * Serialised through [backgroundRestJob] the way [saveDraft] is through `draftSave`:
     * `backgroundRestTimer.set` suspends inside `Notifier.post` on a store read, so two calls
     * launched close together — three taps of "+15s", or a set logged and then FINISH — can
     * otherwise resume out of order and leave the OS holding a deadline the timer has moved
     * past, or re-post a countdown for a workout that is already over. Cancelling the one in
     * flight makes the later call the winner every time.
     */
    private fun publishRest(snapshot: RestTimerSnapshot) {
        val rest = backgroundRest(snapshot)
        backgroundRestJob?.cancel()
        backgroundRestJob = viewModelScope.launch { runCatching { backgroundRestTimer.set(rest) } }
    }

    /** The rest publication in flight, so the next one supersedes it. See [publishRest]. */
    private var backgroundRestJob: Job? = null

    /**
     * The rest as something outside this app can hold, or **null** when there is nothing to
     * hold.
     *
     * Null for a rest that is over or was never started, and also for one whose exercise this
     * screen cannot name at all — a set logged against a slot cut out from under it, which is
     * the same race [restAfter] returns null for. Both mean "take the note down", which is the
     * honest answer: a notification about a rest in an exercise that is not in the workout is
     * worse than none.
     *
     * The set number is read off the store rather than counted here, so it is the number the
     * next row will actually carry — a table with a set deleted out of the middle of it is
     * renumbered, and `size + 1` would name a set that already exists.
     */
    private fun backgroundRest(snapshot: RestTimerSnapshot): BackgroundRest? {
        if (snapshot == RestTimerSnapshot.Idle) return null
        val exerciseId = screenState.value.restExerciseId ?: return null
        val loaded = loaded.value ?: return null
        val exercise = loaded.ordered.firstOrNull { it.id == exerciseId } ?: return null

        return BackgroundRest(
            sessionId = sessionId,
            // Null for an exercise deleted from the library since the session started. The
            // workout carries on past that everywhere else on this screen, and so does this.
            exerciseName = catalogue.value?.exercises?.get(exercise.exerciseId)?.name,
            // What the set that started this rest said, and otherwise the table as it stands.
            // The fallback is for a rest restored after a process death, where nothing recorded
            // the number and the read flow has long since caught up.
            setNumber = screenState.value.restNextSetNumber
                ?: ((loaded.setsOf(exerciseId).maxOfOrNull(WorkoutSet::setNumber) ?: 0) + 1),
            rest = snapshot,
        )
    }

    /**
     * Takes the note and the alarm down, without writing a draft.
     *
     * For the two ways out of a workout. Both cancel the countdown, which lands on
     * [RestTimerState.Idle] and reaches no capture — the draft is being cleared by the use case
     * they just ran, and writing one here would put back the thing it deleted.
     */
    private fun clearBackgroundRest() {
        backgroundRestJob?.cancel()
        backgroundRestJob = viewModelScope.launch { runCatching { backgroundRestTimer.set(null) } }
    }

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

            is ActiveSessionEvent.MobilityExpanded -> screenState.update {
                it.copy(
                    collapsedMobility = if (event.expanded) {
                        it.collapsedMobility - event.kind
                    } else {
                        it.collapsedMobility + event.kind
                    },
                )
            }

            is ActiveSessionEvent.MobilityAccepted -> acceptMobility(event.kind)

            // No confirmation and no undo: what a dismissal costs is a card, and the same card is
            // back next session. Folding it shut is the softer half, and both are one tap.
            is ActiveSessionEvent.MobilityDismissed -> screenState.update {
                it.copy(dismissedMobility = it.dismissedMobility + event.kind)
            }

            else -> return false
        }
        return true
    }

    /**
     * Puts a suggestion card's movements into the workout, at the end the card belongs to.
     *
     * The warm-up goes in front — a warm-up performed after the working sets is not a warm-up —
     * and carries the ramp-up sets on the exercise they were computed for, which is why the
     * drafts are built here rather than in the planner: a [SessionExerciseDraft] is a write, and
     * the planner does not write.
     *
     * The ramp-up hangs off *the lift*, not off a mobility movement, and that lift is already in
     * the workout. It is therefore not added here at all — the sets are written against the slot
     * it already occupies, which is what makes them show up in its own table rather than in a
     * fourth card. See [addRampUp].
     *
     * Guarded per card, the way [log] is guarded per exercise: two taps dispatched before
     * recomposition would otherwise add the same movements twice, and `session_exercise` is
     * keyed on position rather than on the exercise, so nothing downstream would refuse it.
     */
    private fun acceptMobility(kind: MobilityKind) {
        val state = screenState.value
        if (state.isBusy || state.pausedAt != null || kind in state.addingMobility) return
        val suggestion = suggestion.value
        val movements = when (kind) {
            MobilityKind.WARMUP -> suggestion.warmup
            MobilityKind.COOLDOWN -> suggestion.cooldown
        }
        // Only what the workout does not already hold. `isAdded` is read off the workout, so a
        // card whose movements were accepted and then *partly* cut back out offers itself again
        // — and adding the whole list a second time would put the survivors in twice.
        val inSession = sessionExerciseIds()
        val missing = movements.filterNot { it.exercise.id in inSession }
        if (missing.isEmpty()) return

        screenState.update { it.copy(addingMobility = it.addingMobility + kind, message = null) }

        viewModelScope.launch {
            val added = addExercises(
                exercises = missing.map { SessionExerciseDraft(exerciseId = it.exercise.id) },
                placement = if (kind == MobilityKind.WARMUP) ExercisePlacement.START else ExercisePlacement.END,
            )
            // Only after the movements have landed, only for the warm-up, and only the first
            // time the card is taken — the ramp-up is already under the lift from that first
            // acceptance, and a second copy would be four warm-up sets under one working set.
            // A failure here leaves the movements in the workout without their ramp-up, which is
            // a warm-up the lifter can still perform, where reporting the whole thing as failed
            // over it would say the card did nothing.
            if (added.isSuccess && kind == MobilityKind.WARMUP && missing.size == movements.size) {
                addRampUp(suggestion)
            }

            screenState.update { state ->
                state.copy(
                    addingMobility = state.addingMobility - kind,
                    // The card folds itself away on success, because the workout below is now
                    // the better copy of the same list — `isAdded` is what keeps it shut.
                    collapsedMobility = if (added.isSuccess) state.collapsedMobility + kind else state.collapsedMobility,
                    message = if (added.isSuccess) state.message else ActiveSessionMessage.ADD_EXERCISES_FAILED,
                )
            }
            saveDraft()
        }
    }

    /**
     * The catalogue exercises the workout currently holds.
     *
     * What [MobilityCard.isAdded] is read from, asked again on the write path — and it is what
     * actually stops a double tap. The in-flight flag cannot: the write is over by the time a
     * second event arrives, and what is left to say no is the workout itself.
     */
    private fun sessionExerciseIds(): Set<Long> =
        loaded.value?.ordered?.mapTo(mutableSetOf(), SessionExercise::exerciseId).orEmpty()

    /**
     * Writes the ramp-up sets against the lift they were computed for, under its existing sets.
     *
     * Numbered from the end of the table rather than from 1, unlike every other planned set in
     * this codebase: the exercise has been in the workout since it started and may already have
     * rows. They go *after* those rather than in front, because `set_number` is the order the
     * table is drawn in and renumbering a lifter's logged sets to make room would move rows they
     * have already ticked.
     *
     * Best effort, and silent. A ramp-up that could not be written is two sets the lifter types
     * themselves; it is not worth a message over a card that has otherwise done what it said.
     */
    private suspend fun addRampUp(suggestion: WarmupSuggestion) {
        val exerciseId = suggestion.rampUpExerciseId ?: return
        val loaded = loaded.value ?: return
        val slot = loaded.ordered.firstOrNull { it.exerciseId == exerciseId } ?: return
        val from = loaded.setsOf(slot.id).maxOfOrNull(WorkoutSet::setNumber) ?: 0
        val now = clock.nowMillis()

        runCatching {
            sessionRepository.insertSets(
                suggestion.rampUp.mapIndexed { index, planned ->
                    WorkoutSet(
                        sessionExerciseId = slot.id,
                        setNumber = from + index + 1,
                        reps = planned.reps,
                        weight = planned.weight,
                        setType = planned.setType,
                        // The placeholder `workout_set.completed_at` holds for a set nobody has
                        // performed — see `StartPlannedSessionUseCase`.
                        completedAt = 0L,
                        isCompleted = false,
                        createdAt = now,
                        updatedAt = now,
                    )
                },
            )
        }
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
            // Bare drafts: the picker answers with ids and nothing else, so there is no plan to
            // carry. The warm-up card is the caller that has one — see [acceptMobility].
            addExercises(exerciseIds.map { SessionExerciseDraft(exerciseId = it) })
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
                    // Counted, not just reported. The refusal leaves `session_exercise` exactly
                    // as it was, so nothing the screen reads off the store changes to tell it
                    // that the order it is drawing was turned down — see
                    // [ActiveSessionUiState.refusedReorders].
                    screenState.update {
                        it.copy(
                            isReordering = false,
                            refusedReorders = it.refusedReorders + 1,
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
    private fun swap(
        sessionExerciseId: Long,
        exerciseId: Long,
    ) {
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
    private fun regroup(
        sessionExerciseId: Long,
        linked: Boolean,
    ) {
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
     *
     * Refused over a workout on its way out, the way [finish] and [discard] are. A pause landing
     * behind a finish is either a transition refused for a session that is over — reported as
     * "could not change the session" over a screen that is successfully leaving for the recap —
     * or, if it wins the race, a suspend applied to a session in the middle of being closed out.
     */
    private fun togglePause() {
        if (screenState.value.isBusy || screenState.value.isPausing) return
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
     *
     * Every correction still on screen is written first. A correction is written by *leaving* the
     * row — see [commitSet] — and tapping END does not leave it: a Material button does not take
     * focus off a text field, so a lifter who types `72.5` over their last set and reaches
     * straight for END has made a change that nothing has yet asked to be saved. The departure
     * `LoggedSetTable` reports as the screen comes down arrives after [ScreenState.isFinishing]
     * is set and is refused for it, which is right — a write must not start behind a session
     * being closed out — so this is that write, done in front of it instead.
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
            flushSetEdits()

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
                    // What the finish earned, left for the recap that celebrates it. This is
                    // the only moment the value exists — see `SessionProgressionHandoff` — and
                    // dropping it here is what used to make the payoff unreportable.
                    //
                    // Null when no cascade is wired, which is not an error and leaves the recap
                    // drawing the session's figures and no celebration.
                    finished.progression?.let(progressionHandoff::offer)

                    // The rest goes with the workout. Not to tidy up — `viewModelScope` would
                    // take the countdown with it either way — but so that the sheet is not still
                    // over the screen for the frame or two it takes the recap to open, and so
                    // that nothing rings for a rest between two sets of a session that is over.
                    // The note and the alarm outlive this process entirely, so they have to be
                    // taken down by name rather than left to the scope.
                    restTimer.cancel()
                    clearBackgroundRest()
                    // Awaited rather than launched, unlike everything else on the way out: the
                    // state update below is what navigates away, and the pop takes
                    // `viewModelScope` with it — a coroutine started here and not yet run would
                    // be cancelled, leaving an alarm pending over a workout that is over.
                    runCatching { idleSessionMonitor.disarm() }
                    screenState.update {
                        it.copy(finishedSessionId = finished.summary.sessionId, isRestSheetOpen = false)
                    }
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
                    // As on the way out through the recap: no rest outlives the workout it was
                    // in the middle of. See [finish].
                    restTimer.cancel()
                    clearBackgroundRest()
                    // Awaited, as on the way out through the recap. See [finish].
                    runCatching { idleSessionMonitor.disarm() }
                    screenState.update {
                        it.copy(isDiscarded = true, confirmingDiscard = false, isRestSheetOpen = false)
                    }
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
        //
        // Incomplete is not enough to identify it any more. A logged set can be un-ticked — see
        // [toggleCompletion] — so the last set of an exercise can be an incomplete *row*, and one
        // restored into the entry fields would be a set the user could then log a second copy of.
        // What tells them apart is the id: the draft carries it for a set that is a row, and the
        // half-typed one has never been written and has none.
        val entries = draft.exercises.mapNotNull { exercise ->
            val typed = exercise.sets.lastOrNull()
                ?.takeIf { !it.isCompleted && it.id == null }
                ?: return@mapNotNull null
            val id = exercise.sessionExerciseId ?: return@mapNotNull null
            id to typed.toEntry(unitSystem.value)
        }.toMap()

        // A rest already running is one this process started, and it is newer than anything on
        // disk. The screen goes interactive on `reattach`'s first emission, which is before this
        // read comes back, so a set logged in that window starts a countdown — and restoring
        // over it would cancel a rest the user is watching and pull the sheet out from under it.
        // The same rule the entries follow below, for the same reason: the draft is the older
        // copy of everything it carries.
        val live = restTimer.state.value !is RestTimerState.Idle

        // The rest, resolved against the clock as it is *now*: what was written down is a
        // deadline, so a draft read ten minutes after it was saved restores a rest that is over
        // rather than one with ten minutes still on it. That resolution is
        // `RestTimerSnapshot.toStateAt`'s, and it is the whole reason the draft stores an
        // instant. Restoring a finished rest rings nothing — see `RestExpiryAlert`.
        val rest = draft.restTimerStateAt(clock.nowMillis())
        if (!live) restTimer.restore(rest)
        val resting = rest is RestTimerState.Running || rest is RestTimerState.Paused
        val restedExerciseId = draft.exercises
            .getOrNull(draft.currentExerciseIndex)
            ?.sessionExerciseId
            ?.takeIf { resting }

        screenState.update { state ->
            state.copy(
                pausedDuration = draft.pausedDuration,
                pausedAt = draft.pausedAt,
                // Merged under what is already there rather than over it: a keystroke landing
                // between the screen opening and this read coming back is the user's, and the
                // draft is only ever the older copy of it.
                entries = entries + state.entries,
                // Straight back up over the workout, because that is where the user was: a rest
                // is the one thing on this screen that goes on happening while the app is not
                // running, and coming back to a workout mid-rest with nothing saying so would
                // hide the only fact that changed while they were gone. Left alone where a rest
                // is already running, which is the sheet the countdown above put up.
                isRestSheetOpen = if (live) state.isRestSheetOpen else resting,
                restExerciseId = if (live) state.restExerciseId else restedExerciseId,
            )
        }
        captureRest()
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
            // The exercise the rest belongs to, and zero when there is no rest.
            //
            // The field asks which exercise the user was on, and for most of this screen there
            // is no answer — every exercise is on the page and every one of them takes sets.
            // Between two sets there *is* one: the user has just logged a set on an exercise
            // and is about to log another, which is exactly what the field means and what
            // [ActiveSessionUiState.restTarget] needs back after a restart. Nothing else is
            // lost by it — each half-typed entry travels on its own exercise, as an incomplete
            // set.
            currentExerciseIndex = state.restExerciseId
                ?.let { id -> loaded.value?.ordered?.indexOfFirst { it.id == id } }
                ?.takeIf { it >= 0 }
                ?: 0,
            restTimer = state.restSnapshot,
        )

        // Best-effort: the draft is a snapshot of a screen, and a save that fails costs the
        // user their place in the workout rather than any of their sets. Reporting it would be
        // a message about a mechanism they cannot act on.
        runCatching { inProgressSessionRepository.save(draft) }
    }

    // ─── Assembling the state ───────────────────────────────────────────────────────────

    @Suppress("LongParameterList")
    private fun build(
        loaded: Loaded?,
        catalogue: Catalogue?,
        screen: ScreenState,
        unitSystem: UnitSystem,
        advice: Advice,
        now: Long,
    ): ActiveSessionUiState {
        val base = ActiveSessionUiState(
            nameDraft = screen.nameDraft,
            isRenaming = screen.isRenaming,
            isAddingExercises = screen.isAddingExercises,
            isRegrouping = screen.isRegrouping,
            refusedReorders = screen.refusedReorders,
            isReordering = screen.isReordering,
            swappingExerciseId = screen.swappingExerciseId,
            isPaused = screen.pausedAt != null,
            loggingExerciseIds = screen.loggingExerciseIds,
            togglingSetIds = screen.togglingSetIds,
            isFinishing = screen.isFinishing,
            isDiscarding = screen.isDiscarding,
            confirmingDiscard = screen.confirmingDiscard,
            finishedSessionId = screen.finishedSessionId,
            isDiscarded = screen.isDiscarded,
            deletedSet = screen.deletedSet,
            rest = screen.rest,
            // The flag says the sheet was raised and not since dismissed; whether there is
            // anything to *look* at is the timer's answer. Reading them together here is what
            // makes the sheet come down of its own accord when the countdown reaches zero,
            // without anything having to remember to lower a flag — and what stops a sheet
            // being restored over a rest that ended while the process was dead.
            isRestSheetOpen = screen.isRestSheetOpen && screen.rest.isLive,
            message = screen.message,
        )

        if (loaded == null || catalogue == null) return base
        val session = loaded.session ?: return base.copy(isLoading = false)

        val rows = loaded.rows(
            catalogue = catalogue,
            entries = screen.entries,
            setEdits = screen.setEdits,
            savingSetIds = screen.savingSetIds,
            unitSystem = unitSystem,
            targets = advice.targets,
        )

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
            // The set just logged against the exercise being rested from — see [RestTarget].
            // Derived from the store on every rebuild rather than captured when the rest
            // started, so a set corrected mid-rest corrects what the sheet says is coming.
            restTarget = screen.restExerciseId
                ?.takeIf { screen.rest.isLive }
                ?.let { loaded.setsOf(it).lastOrNull() }
                ?.let { RestTarget(weight = it.weight, reps = it.reps) },
            warmup = advice.suggestion.warmupCard(catalogue, loaded, screen),
            cooldown = advice.suggestion.cooldownCard(loaded, screen),
            isLoading = false,
        )
    }

    /**
     * The warm-up card, or **null** where there is nothing to draw.
     *
     * Null covers the setting being off, a session with no match in the catalogue and a card the
     * user has sent away — three situations the screen does not have to tell apart, because in
     * all three there is no suggestion on the page.
     */
    private fun WarmupSuggestion.warmupCard(
        catalogue: Catalogue,
        loaded: Loaded,
        screen: ScreenState,
    ): MobilityCard? {
        if (!hasWarmup) return null
        return card(
            kind = MobilityKind.WARMUP,
            movements = warmup,
            loaded = loaded,
            screen = screen,
        )?.copy(
            rampUp = rampUp,
            rampUpExerciseName = rampUpExerciseId?.let { catalogue.exercises[it]?.name },
            includeGeneralNote = includeGeneralNote,
        )
    }

    /**
     * The cool-down card, on the same rules — see [warmupCard].
     *
     * No catalogue: there is no ramp-up after the workout, so nothing here has to be resolved
     * back to an exercise's name.
     */
    private fun WarmupSuggestion.cooldownCard(
        loaded: Loaded,
        screen: ScreenState,
    ): MobilityCard? {
        if (!hasCooldown) return null
        return card(kind = MobilityKind.COOLDOWN, movements = cooldown, loaded = loaded, screen = screen)
    }

    /**
     * One card's common half: the movements as rows, and the three states it can be in.
     *
     * "Added" is read off the workout rather than remembered — see [MobilityCard] — so it
     * survives a rotation and a process death without anything being written down, and it goes
     * back to false if the lifter cuts the movements out again.
     */
    private fun card(
        kind: MobilityKind,
        movements: List<MobilityExercise>,
        loaded: Loaded,
        screen: ScreenState,
    ): MobilityCard? {
        if (kind in screen.dismissedMobility) return null
        val inSession = loaded.ordered.mapTo(mutableSetOf(), SessionExercise::exerciseId)
        return MobilityCard(
            movements = movements.map {
                MobilityMovement(exerciseId = it.exercise.id, name = it.exercise.name)
            },
            isExpanded = kind !in screen.collapsedMobility,
            isAdded = movements.isNotEmpty() && movements.all { it.exercise.id in inSession },
            isAdding = kind in screen.addingMobility,
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
 * The part of [ScreenState] that outlives the process in saved instance state.
 *
 * Deliberately these four and no more. Everything else the workout screen holds is either
 * restored from the draft (the entries, the rest, the pause) or is a flag about something that
 * was happening when the process died and therefore is not happening any more (a write in
 * flight, a sheet, a message). See [ActiveSessionViewModel.typedInput] for what must not be
 * added here and why.
 */
@Serializable
private data class TypedInput(
    val nameDraft: String? = null,
    val setEdits: Map<Long, SetEntry> = emptyMap(),
    /**
     * Which suggestion cards are folded shut and which have been sent away (#214).
     *
     * The exception to the rule above, and it earns it: these are not typed input, but they are
     * the one thing about the cards that neither the store nor the suggestion can say. The
     * suggestion itself is recomputed — that is what makes it stable across a process death —
     * and "the user already said no to this" is not recomputable from anything. Two enum names
     * per session is nothing next to what a `Bundle` can hold.
     */
    val collapsedMobility: Set<MobilityKind> = emptySet(),
    val dismissedMobility: Set<MobilityKind> = emptySet(),
)

/**
 * What the user is in the middle of.
 *
 * @param entries the half-typed set of each exercise, keyed by `session_exercise` id. Absent
 *   means an untouched row, which is why reads go through [entryFor] rather than the map: an
 *   exercise the user has not reached yet and one they have typed into and cleared are the same
 *   empty row, and storing a default for every exercise would be a second copy of the layout.
 * @param setEdits what has been typed over a set that is already logged, keyed by `workout_set`
 *   id. Absent — which is nearly every row, nearly always — means a row nobody has touched,
 *   whose fields are read off the store every time the screen is built. There is no "open" row to
 *   be at most one of: every row is live, so what is held is the typing, and only for as long as
 *   it differs from what is on disk. See [commitSet].
 * @param savingSetIds the sets whose correction is being written. A set rather than a flag for
 *   the reason [togglingSetIds] is one: the rows are independent, and a slow write against set 2
 *   has no business freezing the fields of set 3.
 * @param deletedSet the set behind the offer to undo, held until the offer is taken or lapses.
 *   The store no longer has it, so this is the only copy — see [ActiveSessionUiState.deletedSet].
 * @param rest the countdown as the controller last published it, ticked into here so the whole
 *   screen is built from one value. Read-only from this side: what moves it is the controller.
 * @param restSnapshot the same rest as the *deadline* the draft carries, which is a different
 *   type on purpose — see `RestTimerSnapshot`. Updated on the transitions and never on a tick.
 * @param restExerciseId the exercise the current rest follows, or null when nothing is resting.
 *   What the sheet's "up next" is read from, and the one thing about a rest that neither the
 *   controller nor its snapshot carries.
 * @param isRestSheetOpen whether the sheet has been raised and not since swiped away. Not the
 *   whole of whether it is drawn — see [ActiveSessionViewModel.build].
 */
private data class ScreenState(
    val entries: Map<Long, SetEntry> = emptyMap(),
    val setEdits: Map<Long, SetEntry> = emptyMap(),
    val savingSetIds: Set<Long> = emptySet(),
    val isDeletingSet: Boolean = false,
    val deletedSet: WorkoutSet? = null,
    val rest: RestTimerState = RestTimerState.Idle,
    val restSnapshot: RestTimerSnapshot = RestTimerSnapshot.Idle,
    val restExerciseId: Long? = null,
    /**
     * The number of the set the current rest leads back to, or null where nothing recorded it —
     * a rest restored from a draft, which carries the countdown but not this.
     *
     * Held rather than counted, because the count is a read of a table the set that started the
     * rest may not have reached yet. See [ActiveSessionViewModel.startRest].
     */
    val restNextSetNumber: Int? = null,
    val isRestSheetOpen: Boolean = false,
    val isRestoringSet: Boolean = false,
    val nameDraft: String? = null,
    val isRenaming: Boolean = false,
    val isAddingExercises: Boolean = false,
    val isRegrouping: Boolean = false,
    val isReordering: Boolean = false,
    val refusedReorders: Int = 0,
    val swappingExerciseId: Long? = null,
    val removingExerciseIds: Set<Long> = emptySet(),
    val pausedDuration: Duration = Duration.ZERO,
    val pausedAt: Long? = null,
    val loggingExerciseIds: Set<Long> = emptySet(),
    val togglingSetIds: Set<Long> = emptySet(),
    val isPausing: Boolean = false,
    val isFinishing: Boolean = false,
    val isDiscarding: Boolean = false,
    val confirmingDiscard: Boolean = false,
    val finishedSessionId: Long? = null,
    val isDiscarded: Boolean = false,
    /**
     * The suggestion cards the user has folded shut, and the ones they have sent away (#214).
     *
     * Both are the *negative* — collapsed rather than expanded, dismissed rather than shown —
     * because the default is a card that is open and on the page, and an empty set is what a
     * workout nobody has touched the cards on looks like. Both survive a process death; see
     * [TypedInput].
     */
    val collapsedMobility: Set<MobilityKind> = emptySet(),
    val dismissedMobility: Set<MobilityKind> = emptySet(),
    /**
     * The cards whose movements are being written. A set rather than a flag, for the reason
     * [togglingSetIds] is one: the two cards are independent, and the cool-down going inert
     * while the warm-up is written would be a control disabled by an unrelated write.
     */
    val addingMobility: Set<MobilityKind> = emptySet(),
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
    fun bankPause(now: Long): ScreenState =
        copy(
            pausedDuration = pausedDuration + (now - (pausedAt ?: now)).milliseconds,
            pausedAt = null,
        )

    /**
     * Whether one exercise's row as it stands can be written: something typed, nothing refused,
     * and no write already in flight against *that* exercise.
     */
    fun canLogNow(sessionExerciseId: Long): Boolean =
        sessionExerciseId !in loggingExerciseIds && !isBusy && entryFor(sessionExerciseId).canLog

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
        get() = !isBusy && loggingExerciseIds.isEmpty()

    /**
     * Time actually spent working out, at [now].
     *
     * The same arithmetic `ActiveSession.elapsedAt` does, over the fields the draft would carry,
     * so the clock on screen and the clock a restore comes back with are the same number. A
     * paused session measures to [pausedAt] instead of to now, which is what stops it ticking.
     */
    fun elapsedAt(
        now: Long,
        startedAt: Long,
    ): Duration {
        val until = pausedAt ?: now
        return ((until - startedAt).milliseconds - pausedDuration).coerceAtLeast(Duration.ZERO)
    }
}

/**
 * What a correction is written from: the row as stored, and how its exercise is logged.
 *
 * The stored row rather than only its id, because an edit replaces every dimension of the set —
 * see `EditSetUseCase` — so the values the table has no column for have to come from somewhere,
 * and that somewhere is the row being corrected.
 */
private class EditTarget(
    val stored: WorkoutSet,
    val exerciseType: ExerciseType?,
)

/**
 * What this screen is *advised* to do, as opposed to what it has read: the next-load target for
 * each exercise, and the warm-up and cool-down. See `ActiveSessionViewModel.advice`.
 */
private data class Advice(
    val targets: Map<Long, LoadSuggestion?> = emptyMap(),
    val suggestion: WarmupSuggestion = WarmupSuggestion(),
)

/** The two catalogue-wide reads, as one value. */
private data class Catalogue(
    val exercises: Map<Long, Exercise> = emptyMap(),
    val muscleLinks: Map<Long, List<ExerciseMuscleGroupLink>> = emptyMap(),
    /**
     * The headline record per exercise — the mark a lifter is working against.
     *
     * One bulk read keyed by exercise rather than a query per card: a session of eight exercises
     * would otherwise be eight queries re-run on every set logged. Which of an exercise's records
     * is the *headline* one is decided in SQL — see `PersonalRecordDao.getHeadlineRecords`.
     */
    val records: Map<Long, PersonalRecord> = emptyMap(),
)

/**
 * The session as it was read: the row, its exercises and its sets.
 *
 * @param session null until the read comes back **and** for an id that no longer resolves. The
 *   two are told apart one level up, by whether this value exists at all.
 * @param previous what each of the session's exercises did last time, keyed by *catalogue*
 *   exercise id — the key both sessions share, since the `session_exercise` rows behind it
 *   belong to workouts this screen knows nothing else about. An exercise with no entry has
 *   never been performed, which is a different state from an entry holding fewer sets than
 *   today's table; see [rows].
 */
private class Loaded(
    val session: WorkoutSession?,
    exercises: List<SessionExercise>,
    val sets: List<WorkoutSet>,
    private val previous: Map<Long, List<WorkoutSet>>,
) {

    /** The session's exercises in the order they are performed, which is the `position` column. */
    val ordered: List<SessionExercise> = exercises.sortedBy(SessionExercise::position)

    private val setsByExercise: Map<Long, List<WorkoutSet>> =
        sets.groupBy(WorkoutSet::sessionExerciseId)
            .mapValues { (_, logged) -> logged.sortedBy(WorkoutSet::setNumber) }

    fun setsOf(sessionExerciseId: Long): List<WorkoutSet> = setsByExercise[sessionExerciseId].orEmpty()

    /** One set by its own id, wherever in the workout it is. */
    fun setById(setId: Long): WorkoutSet? = sets.firstOrNull { it.id == setId }

    /**
     * One row per exercise, resolved against the catalogue, labelled with its superset and
     * carrying whatever is typed against it — the next set, and the logged one being corrected.
     *
     * The grouping is normalised before anything is read off it, matching the new-session flow:
     * a group left with one member is not a superset and must not be drawn as `A1` with no `A2`
     * under it, nor as a join the user could break.
     */
    @Suppress("LongParameterList")
    fun rows(
        catalogue: Catalogue,
        entries: Map<Long, SetEntry>,
        setEdits: Map<Long, SetEntry>,
        savingSetIds: Set<Long>,
        unitSystem: UnitSystem,
        targets: Map<Long, LoadSuggestion?>,
    ): List<SessionExerciseRow> {
        val grouping = Supersets.normalize(ordered.map(SessionExercise::supersetGroup))
        val labels = supersetLabels(grouping)
        val groupNames = supersetGroupNames(grouping)

        return ordered.mapIndexed { index, exercise ->
            val history = previous[exercise.exerciseId]
            // Today's table and last time's, walked in step. A running count rather than a row
            // index, because the two lists do not line up by position: warm-ups are left out of
            // the reference (see `SessionRepository.getPreviousSets`) and skipped over on this
            // side too, so "the third set today" is compared against the third *working* set of
            // the last session and not against whatever its third row happened to be.
            var working = 0
            val entry = entries[exercise.id] ?: SetEntry()

            // Every logged set as the fields it is drawn in: what has been typed over it if
            // anything has, and otherwise the stored row read out into text. The fallback is
            // the reason an untouched table costs no state at all — see [ScreenState.setEdits].
            val sets = setsOf(exercise.id).map { set ->
                val typed = setEdits[set.id]
                // The type as *shown* rather than as stored, so a row retyped as a warm-up
                // gives up its reference the moment the menu is answered rather than when the
                // correction lands.
                val shown = typed ?: set.toEntry(unitSystem)
                val isWarmUp = shown.setType == SetType.WARMUP
                val reference = if (isWarmUp) null else history?.getOrNull(working)
                if (!isWarmUp) working++

                LoggedSet(
                    id = set.id,
                    entry = shown,
                    isCompleted = set.isCompleted,
                    isEdited = typed != null,
                    isSaving = set.id in savingSetIds,
                    previous = reference?.toPreviousSet(),
                    // Asked of the row as *shown*, so typing the missing reps into a set a
                    // template left blank arms its tick on the keystroke rather than on the
                    // write. Un-ticking is never refused, so a completed row can always be
                    // pressed. See [LoggedSet.canComplete].
                    canComplete = set.isCompleted ||
                        shown.records(catalogue.exercises[exercise.exerciseId]?.type),
                )
            }

            SessionExerciseRow(
                sessionExerciseId = exercise.id,
                position = index,
                exercise = catalogue.exercises[exercise.exerciseId],
                muscleGroups = catalogue.muscleLinks[exercise.exerciseId].orEmpty(),
                record = catalogue.records[exercise.exerciseId],
                supersetLabel = labels[index],
                supersetGroupName = groupNames[index],
                // Read off the neighbour rather than looked up by group, which is sound exactly
                // because members are contiguous — the same reason `Supersets.isRestDueAfter`
                // can. The last exercise is linked to nothing by construction.
                isLinkedToNext = grouping[index] != null &&
                    grouping[index] == grouping.getOrNull(index + 1),
                sets = sets,
                entry = entry,
                entryPrevious = history
                    ?.takeIf { entry.setType != SetType.WARMUP }
                    ?.getOrNull(working)
                    ?.toPreviousSet(),
                // The exercise has been performed before, which is not the same as any row of
                // it having something to compare against — see [SessionExerciseRow.hasHistory].
                hasHistory = history != null,
                // Keyed by catalogue id like the history, and for the same reason: it is a fact
                // about the exercise, and two slots holding the same one get the same answer.
                target = targets[exercise.exerciseId],
            )
        }
    }

    /**
     * The session as the warm-up planner reads it — see
     * [com.opengym.core.domain.model.SessionMovement] and `ActiveSessionViewModel.movements`.
     *
     * An exercise deleted from the catalogue under an open session is left out entirely rather
     * than carried with a placeholder: with no catalogue row there is no mechanic, no equipment
     * and no muscle links, so it can decide nothing the planner asks.
     */
    fun movements(
        catalogue: Catalogue?,
        targets: Map<Long, LoadSuggestion?>,
    ): List<SessionMovement> {
        catalogue ?: return emptyList()
        return ordered.mapNotNull { exercise ->
            val row = catalogue.exercises[exercise.exerciseId] ?: return@mapNotNull null
            SessionMovement(
                exercise = row,
                muscleGroups = catalogue.muscleLinks[exercise.exerciseId].orEmpty(),
                // This session's own plan first — a workout started from a template carries its
                // weights before a rep is performed — and the next-load target behind it, which
                // is what a session built by hand has instead. Zero and null are the same answer
                // here: a set with no weight is nothing to take a fraction of.
                workingWeight = setsOf(exercise.id).mapNotNull(WorkoutSet::weight).maxOrNull()
                    ?.takeIf { it > 0 }
                    ?: targets[exercise.exerciseId]?.weight,
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
    ): List<ActiveExercise> =
        ordered.map { exercise ->
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
private class Field<T> private constructor(
    val value: T?,
    val isMalformed: Boolean,
) {
    companion object {
        /** The field is empty: no value, and nothing wrong with it. */
        fun <T> absent(): Field<T> = Field(value = null, isMalformed = false)

        /** [parsed] as the parsers return it: null means the text was not a number. */
        fun <T> of(parsed: T?): Field<T> = Field(value = parsed, isMalformed = parsed == null)
    }
}

/* Which [SetEntryError] a refused write is, if it is one the row can show. */

/**
 * Whether there is a rest to look at: one counting down, or one held part way through.
 *
 * Finished and idle are both "no", and they are two different noes — a rest that ran out is
 * distinguishable from one that was called off, and `RestExpiryAlert` and the draft both depend
 * on it — but neither is a countdown, and everything on screen that draws one asks this.
 */
private val RestTimerState.isLive: Boolean
    get() = this is RestTimerState.Running || this is RestTimerState.Paused

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

/**
 * A stored set as the fields it is drawn in.
 *
 * The weight goes back into the unit it will be typed in — what is on disk is milligrams, and a
 * lifter on pounds must not find `79.4` where they logged 175 lb.
 *
 * The note is left out on purpose. `SetEntry.notes` is the *entry* row's field, where null draws
 * the "add a note" affordance and empty draws an open field; a logged row has no such field, and
 * seeding it would be inventing an answer to a question the table never asks. What the note does
 * instead is travel through the write untouched, off the stored row — see [ActiveSessionViewModel]'s
 * commit path.
 */
private fun WorkoutSet.toEntry(unitSystem: UnitSystem): SetEntry =
    SetEntry(
        weight = weight?.let { formatWeightValue(it, unitSystem) }.orEmpty(),
        reps = reps?.toString().orEmpty(),
        rpe = rpe,
        setType = setType,
    )

/**
 * Whether this correction says the same thing as the set it was read from.
 *
 * The four dimensions a row can change, compared as *values* rather than as text: `70` and
 * `70.0` are the same weight, and a lifter who tabbed through a field without altering it has
 * made no correction to write.
 */
private fun ActiveSet.matches(stored: WorkoutSet): Boolean =
    weight == stored.weight &&
        reps == stored.reps &&
        rpe == stored.rpe &&
        setType == stored.setType

/** The same four dimensions, against a correction rather than against a stored row. */
private fun ActiveSet.matches(other: ActiveSet): Boolean =
    weight == other.weight &&
        reps == other.reps &&
        rpe == other.rpe &&
        setType == other.setType

/** A restored half-typed set as the fields it was typed into. */
private fun ActiveSet.toEntry(unitSystem: UnitSystem): SetEntry =
    SetEntry(
        weight = weight?.let { formatWeightValue(it, unitSystem) }.orEmpty(),
        reps = reps?.toString().orEmpty(),
        rpe = rpe,
        setType = setType,
        notes = notes,
    )

/**
 * A set from an earlier session as the reference the `PREV` column draws.
 *
 * Only the two dimensions that column has room for. What is dropped is not lost — the set is
 * still whole on the history screen — and carrying its rating or its note into a 52dp cell
 * would be carrying it nowhere.
 */
private fun WorkoutSet.toPreviousSet(): PreviousSet = PreviousSet(weight = weight, reps = reps)

/** A stored set as the draft's own, which is the same values without the row's identity. */
private fun WorkoutSet.toActiveSet(): ActiveSet =
    ActiveSet(
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
