package com.opengym.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.usecase.DiscardSessionUseCase
import com.opengym.core.domain.usecase.GetTemplateSummariesUseCase
import com.opengym.core.domain.usecase.ResumeSessionUseCase
import com.opengym.core.ui.component.WeekDay
import com.opengym.core.ui.format.firstDayOfWeek
import com.opengym.core.ui.format.localDateOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The dashboard: what the app is still holding open, what the week looks like, and the ways
 * into a workout.
 *
 * ### The banner is the only route back to an open session
 *
 * The history lists what has been *finished*, so a workout the process died under is reachable
 * from exactly two places: here, and the new-session flow's refusal. That is why the banner
 * carries a set count it never draws — discarding it is the other half of the offer, and
 * "discard this workout" is a different question when the answer costs eleven sets.
 *
 * ### Why the draft is read rather than observed
 *
 * `InProgressSessionRepository` is a slot with a `load()`, not a flow, and that is enough here:
 * the draft only changes while the workout screen is up, and the workout screen is *over* this
 * one. Re-read whenever the active session row changes, which covers every way the answer can
 * become stale from Home's point of view — a session started, finished or discarded elsewhere.
 *
 * ### What ticks and what does not
 *
 * The elapsed clock, and nothing else. Assembling the week walks every session the user has
 * ever logged, and none of that changes because a second has passed, so the tick is folded on
 * afterwards over the banner alone — the same split `ActiveSessionViewModel` makes, and for the
 * same reason.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
/*
 * Seven constructor parameters, one over the threshold, and every one is a graph edge: the
 * sessions and their draft, the catalogue the draft's exercise is named from, the templates the
 * quick-start strip offers, the two transitions the banner's Discard runs, and the clock the
 * banner is drawn against. Bundling them behind a holder to satisfy the count would put a type
 * in the way of reading what the screen depends on, which is the one thing the list is good
 * for. The same trade `NewSessionViewModel` records.
 */
@Suppress("LongParameterList")
class HomeViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val inProgressSessionRepository: InProgressSessionRepository,
    private val exerciseRepository: ExerciseRepository,
    getTemplateSummaries: GetTemplateSummariesUseCase,
    private val resumeSession: ResumeSessionUseCase,
    private val discardSession: DiscardSessionUseCase,
    private val clock: Clock,
) : ViewModel() {

    /** What the screen owns, as opposed to what the stores do. */
    private val screenState = MutableStateFlow(ScreenState())

    /**
     * The workout the app is holding open, resolved into everything the banner needs, or null
     * when there is none.
     *
     * Three reads hang off one emission of [SessionRepository.getActive]: the draft for where
     * the user left off, the catalogue for what that exercise is called, and the session's sets
     * for what discarding it would cost. They are read once per *session*, not per second and
     * not per row — none of the three can change while Home is the screen on top.
     */
    private val workout: Flow<ActiveWorkout?> =
        sessionRepository.getActive().map { session -> session?.let { readWorkout(it) } }

    /**
     * Everything the reads decide, with the clock left out of it.
     *
     * The first value arrives only once **every** input has answered, which is what makes
     * [HomeUiState.isLoading] a single flag rather than one per query: `combine` holds its
     * first emission until each source has emitted, and until then the screen is drawing the
     * default state, whose `isLoading` is true.
     */
    private val assembled: StateFlow<Assembled> = combine(
        sessionRepository.getAll(),
        workout,
        getTemplateSummaries(),
        screenState,
    ) { sessions, workout, templates, screen ->
        assemble(sessions, workout, templates, screen)
    }.stateIn(
        scope = viewModelScope,
        // Long enough to survive a rotation without re-reading three queries, short enough that
        // a backgrounded screen is not holding database observers open.
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = Assembled(),
    )

    /**
     * The assembled state with its clock moved on, once a second, and nothing else redone.
     *
     * **The heartbeat exists only while there is a clock to move.** Home is the screen the app
     * spends most of its life on and the banner is the exception rather than the rule, so a
     * timer that beat regardless would wake the process once a second to redraw a screen with
     * no moving parts on it — for every user who does not have a workout open, which is nearly
     * all of them, nearly all of the time. A paused workout is the same case: its clock has
     * stopped by definition, and a beat that redraws the same reading is a beat that does
     * nothing. Both fall out of asking the assembled state whether it has anything to tick.
     */
    val uiState: StateFlow<HomeUiState> = assembled
        .flatMapLatest { assembled -> assembled.ticking() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = HomeUiState(),
        )

    /** One reading, or one a second for as long as this state is the current one. */
    private fun Assembled.ticking(): Flow<HomeUiState> = when {
        workout?.isRunning != true -> flowOf(at(clock.nowMillis()))
        else -> flow {
            while (true) {
                emit(at(clock.nowMillis()))
                delay(TICK)
            }
        }
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            HomeEvent.DiscardRequested -> screenState.update { it.copy(confirmingDiscard = true) }
            HomeEvent.DiscardConfirmed -> discard()
            HomeEvent.DiscardDismissed -> screenState.update { it.copy(confirmingDiscard = false) }
            HomeEvent.MessageShown -> screenState.update { it.copy(message = null) }
        }
    }

    /**
     * Throws the open workout away.
     *
     * **Resumed first, deliberately.** [DiscardSessionUseCase] discards what the state machine
     * says is open, and the state machine is in memory: the case that puts a banner on Home in
     * the first place is a process restart, which leaves it `Idle` over a row still marked in
     * progress. Resuming is what adopts that row — it is the transition written for exactly
     * this — so the result is not read: from an already `Active` state there is nothing to
     * adopt and the refusal is the correct answer, and a row that cannot be loaded fails the
     * discard below, which is where it is reported. The same pair, in the same order and for
     * the same reason, as `NewSessionViewModel.discardRunning`.
     *
     * Nothing clears the banner here. The row is deleted, `getActive()` re-emits null, and the
     * banner goes with it — which is also what makes a discard that half succeeded honest: the
     * session that is still there is still drawn.
     */
    private fun discard() {
        if (screenState.value.isDiscarding) return
        screenState.update { it.copy(isDiscarding = true) }

        viewModelScope.launch {
            resumeSession()
            val message = discardSession().exceptionOrNull()?.let { HomeMessage.DISCARD_FAILED }

            screenState.update {
                it.copy(
                    isDiscarding = false,
                    // Closed either way. On success there is nothing left to ask about; on
                    // failure the question has been answered and repeating it over a workout
                    // that is still there says less than the banner already does.
                    confirmingDiscard = false,
                    message = message ?: it.message,
                )
            }
        }
    }

    /**
     * The open session, and the three things the banner needs that its row does not carry.
     *
     * The draft is confirmed against the session id before it is used: a draft describing a
     * workout that no longer exists is exactly what [InProgressSessionRepository] warns about,
     * and taking its position would put "Bench press · set 3 of 4" under the name of a
     * different session.
     */
    private suspend fun readWorkout(session: WorkoutSession): ActiveWorkout {
        val draft = inProgressSessionRepository.load()?.takeIf { it.sessionId == session.id }

        return ActiveWorkout(
            sessionId = session.id,
            name = WorkoutSession.normalizeName(session.name),
            startedAt = session.createdAt,
            draft = draft,
            position = draft?.let { position(it) },
            // Every row, not only the ticked ones: this figure is what the discard question
            // costs, and the delete cascades to all of them.
            loggedSets = sessionRepository.getSetsForSession(session.id).first().size,
        )
    }

    /**
     * Where the user left off, or null when the draft cannot say.
     *
     * One catalogue row, read by id rather than by walking the whole catalogue for one name.
     * Null when the exercise has been deleted from the library underneath the workout, which
     * drops the line rather than drawing a position with a blank where the movement goes.
     */
    private suspend fun position(draft: ActiveSession): SessionPosition? {
        val current = draft.currentExercise ?: return null
        val name = exerciseRepository.getById(current.exerciseId).first()?.name ?: return null

        return SessionPosition(
            exerciseName = name,
            // The set they are *on*: what is done, plus one, capped so a finished exercise
            // reads "set 4 of 4" rather than "set 5 of 4".
            setNumber = (current.completedSets.size + 1).coerceAtMost(current.sets.size.coerceAtLeast(1)),
            setCount = current.sets.size,
        )
    }

    /**
     * Assemble everything except the clock.
     *
     * The templates are cut here rather than in the screen: the strip holds what it holds, and
     * a `LazyRow` handed sixty templates would compose six of them and keep the other
     * fifty-four alive as items nobody can reach without scrolling past the end of the design.
     */
    private fun assemble(
        sessions: List<WorkoutSession>,
        workout: ActiveWorkout?,
        templates: List<TemplateSummary>,
        screen: ScreenState,
    ): Assembled {
        val today = localDateOf(clock.nowMillis())

        return Assembled(
            state = HomeUiState(
                week = sessions.weekOf(today = today, weekStart = firstDayOfWeek()),
                templates = templates.take(QUICK_START_TEMPLATES),
                isLoading = false,
                confirmingDiscard = screen.confirmingDiscard,
                isDiscarding = screen.isDiscarding,
                message = screen.message,
            ),
            workout = workout,
        )
    }

    /** What the screen owns and what the stores do not know about. */
    private data class ScreenState(
        val confirmingDiscard: Boolean = false,
        val isDiscarding: Boolean = false,
        val message: HomeMessage? = null,
    )

    /**
     * The state, with the open workout kept beside it rather than folded into it.
     *
     * [ActiveWorkout] is what the banner is *computed from*; [HomeUiState.resume] is the
     * banner. Keeping the two apart is what lets the clock move once a second without the week
     * being rebuilt from every session the user has ever logged — see [at].
     */
    private data class Assembled(
        val state: HomeUiState = HomeUiState(),
        val workout: ActiveWorkout? = null,
    ) {

        /** The state as it reads at [nowMillis]. The only thing a tick changes. */
        fun at(nowMillis: Long): HomeUiState = state.copy(resume = workout?.at(nowMillis))
    }

    /**
     * The open workout as the reads found it, before a clock is put to it.
     *
     * @param draft carried whole rather than reduced to its figures, because the elapsed time
     *   is a function of it *and* of the moment it is asked for — a session paused an hour ago
     *   has not been running for that hour, and only the draft knows that.
     */
    private data class ActiveWorkout(
        val sessionId: Long,
        val name: String?,
        val startedAt: Long,
        val draft: ActiveSession?,
        val position: SessionPosition?,
        val loggedSets: Int,
    ) {

        /** Whether its clock is still moving. A paused workout's is not. */
        val isRunning: Boolean get() = draft?.isPaused != true

        fun at(nowMillis: Long) = ResumeState(
            sessionId = sessionId,
            name = name,
            startedAt = startedAt,
            // The draft is the only thing that knows about paused stretches. Without one —
            // a session opened and never worked in — the wall clock since it started is the
            // honest answer, and `coerceAtLeast` keeps a device whose clock has gone
            // backwards from drawing a negative one.
            elapsed = draft?.elapsedAt(nowMillis)
                ?: (nowMillis - startedAt).coerceAtLeast(0).milliseconds,
            isPaused = draft?.isPaused == true,
            loggedSets = loggedSets,
            position = position,
        )
    }

    private companion object {
        /** The other screens' number, for the same reasons. */
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds

        /** How often the banner's clock is recomputed. It is drawn to the second. */
        val TICK = 1.seconds

        /**
         * How many templates the quick-start strip offers.
         *
         * Six: enough that a lifter's whole rotation is usually in there, few enough that the
         * strip is a shortcut rather than a list. The way to *all* of them is the action above
         * it, which opens the picker that is built for choosing.
         */
        const val QUICK_START_TEMPLATES = 6
    }
}

/**
 * The seven days of the week [today] falls in, and what was logged in each.
 *
 * Returns **null** for a user who has never finished a session, which is what makes the card
 * disappear rather than render as seven empty squares over a zero — see [HomeUiState.week].
 * A user who has trained before but not this week gets an empty strip, which is a real and
 * useful thing to be told.
 *
 * The session still in progress is not counted. It is drawn by the banner above, and counting
 * a workout that has not happened yet would put a filled square on today for somebody who has
 * so far only pressed START.
 */
private fun List<WorkoutSession>.weekOf(today: LocalDate, weekStart: DayOfWeek): WeekState? {
    val logged = filterNot(WorkoutSession::isInProgress)
    if (logged.isEmpty()) return null

    val start = today.with(TemporalAdjusters.previousOrSame(weekStart))
    // Counted once over the whole history rather than per cell, which would be seven scans of
    // a list that grows for the life of the install.
    val counts = logged.groupingBy { localDateOf(it.createdAt) }.eachCount()
    val days = List(DAYS_PER_WEEK) { offset ->
        val date = start.plusDays(offset.toLong())
        WeekDay(
            date = date,
            sessionCount = counts[date] ?: 0,
            isToday = date == today,
        )
    }

    return WeekState(days = days, sessionCount = days.sumOf(WeekDay::sessionCount))
}

private const val DAYS_PER_WEEK = 7
