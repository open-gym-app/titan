package com.opengym.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opengym.core.domain.gamification.StreakCalculator
import com.opengym.core.domain.gamification.XpCalculator
import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.QuestProgress
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.domain.model.UserProgress
import com.opengym.core.domain.model.VolumeTrend
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.GamificationRepository
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.stats.TrainingWeek
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.usecase.DiscardSessionUseCase
import com.opengym.core.domain.usecase.GetTemplateSummariesUseCase
import com.opengym.core.domain.usecase.LatestPersonalRecordUseCase
import com.opengym.core.domain.usecase.QuestProgressUseCase
import com.opengym.core.domain.usecase.ResumeSessionUseCase
import com.opengym.core.domain.usecase.VolumeTrendUseCase
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 * The elapsed clock, once a second, and nothing else. Assembling the week walks every session
 * the user has ever logged, and none of that changes because a second has passed, so the tick
 * is folded on afterwards over the banner alone — the same split `ActiveSessionViewModel`
 * makes, and for the same reason.
 *
 * ### The four progression cards are read, not computed
 *
 * The streak, the level, the last record broken and the eight-week volume series all come from
 * engines that already exist — #34's store, #33's curve, #30's table and #31's aggregation —
 * and none of the arithmetic is redone here. The one judgement this class makes over them is
 * whether the *stored* streak is still running, which nothing else can make: a streak is only
 * recomputed when a session finishes, so the row keeps its figure while the days pass. See
 * [liveStreak].
 *
 * ### The day is a source, not something read on the way past
 *
 * Which day it is decides the today ring and which calendar week is being counted, and it
 * changes on its own — at midnight, with nothing else happening. Reading it off the clock while
 * assembling would only ever answer for a screen that was being rebuilt anyway, so a phone left
 * on Home overnight would keep its ring on yesterday and keep counting last week. It is a flow
 * of its own instead, waking once per day at local midnight — see [today] — and it carries the
 * progression window with it, since the eight weeks of volume end on the week it names.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
/*
 * Eleven constructor parameters, and every one is a graph edge: the sessions and their draft,
 * the catalogue the draft's exercise is named from, the templates the quick-start strip offers,
 * the two transitions the banner's Discard runs, the clock the banner is drawn against, the
 * three progression sources #110 added — the standing, the last record broken, and the volume
 * series — and the quest board #104 builds the dashboard card from. Bundling them behind a
 * holder to satisfy the count would put a type in the way of reading what the screen depends
 * on, which is the one thing the list is good for. The same trade `NewSessionViewModel` records.
 */
@Suppress("LongParameterList")
class HomeViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val inProgressSessionRepository: InProgressSessionRepository,
    private val exerciseRepository: ExerciseRepository,
    private val gamificationRepository: GamificationRepository,
    getTemplateSummaries: GetTemplateSummariesUseCase,
    private val latestPersonalRecord: LatestPersonalRecordUseCase,
    private val volumeTrend: VolumeTrendUseCase,
    questProgress: QuestProgressUseCase,
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
     * The day it is, re-emitted when that stops being true.
     *
     * One wake-up per day rather than a poll: the wait is measured to the next local midnight
     * and the emission is deduplicated, so a clock that jumps — a timezone change, a manual
     * correction — moves the screen onto the right day without a burst of rebuilds.
     *
     * [ZoneId.systemDefault] here and in [localDateOf], which is the zone every session is
     * grouped into a calendar day by. A user who flies somewhere gets the local week, which is
     * the one they are training in.
     */
    private val today: Flow<LocalDate> = flow {
        while (true) {
            val now = clock.nowMillis()
            emit(localDateOf(now))
            delay(millisUntilTomorrow(now))
        }
    }.distinctUntilChanged()

    /**
     * The day it is and everything M5 reads over it, kept together on purpose.
     *
     * The volume series is a *window* — the eight weeks ending with the one the user is living
     * in — so it is a function of the day, and the day already re-emits at local midnight. Made
     * one flow rather than two: [today] is a cold loop with a `delay` in it, and collecting it
     * twice would run two midnight timers for one question. `flatMapLatest` is what re-runs the
     * three reads on the new window when the day turns, and drops the old one's rows rather
     * than racing them.
     *
     * The other two travel with it because there is nowhere cheaper to put them: [combine] takes
     * five typed sources, the outer one below already has five, and a sixth would fall through to
     * the array overload and lose every type on the way.
     */
    private val progression: Flow<Progression> =
        today.flatMapLatest { day -> readProgression(day).map { Progression(day = day, readings = it) } }

    /** The three progression reads, over the window [day] falls at the end of. */
    private fun readProgression(day: LocalDate): Flow<Readings> =
        combine(
            gamificationRepository.observeProgress(),
            latestPersonalRecord(),
            volumeTrend(TrainingWeek.trailing(weeks = VOLUME_WEEKS, endingOn = day)),
        ) { progress, record, volume -> Readings(progress = progress, record = record, volume = volume) }
     * This week's board, or null while it is unknown and for a user with nothing to show.
     *
     * ### Why this is combined in separately
     *
     * [assembled] already takes the five sources `combine` has a typed overload for, and a sixth
     * would push it onto the array form. That is the mechanical reason. The better one is that
     * the board must not be able to hold the *whole screen* back: `combine` waits for every
     * source before its first emission, and the board's aggregate reads are the slowest thing
     * Home asks for. Started with a null, so the dashboard draws the moment the other five
     * answer and the card appears under them when the board lands.
     */
    private val quests: Flow<QuestSummary?> =
        questProgress()
            .map { board -> board.summarise() }
            .onStart { emit(null) }

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
        progression,
    ) { sessions, workout, templates, screen, progression ->
        assemble(sessions, workout, templates, screen, progression)
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
    val uiState: StateFlow<HomeUiState> = combine(
        assembled.flatMapLatest { assembled -> assembled.ticking() },
        quests,
    ) { state, quests -> state.copy(quests = quests) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = HomeUiState(),
        )

    /** One reading, or one a second for as long as this state is the current one. */
    private fun Assembled.ticking(): Flow<HomeUiState> =
        when {
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

            // Ignored while the delete is in flight, as `ActiveSessionViewModel` ignores its
            // own: the dialog disables both buttons and holds its dismiss, but the invariant
            // belongs to the layer that owns the flag rather than to the one drawing it.
            HomeEvent.DiscardDismissed ->
                if (!screenState.value.isDiscarding) {
                    screenState.update { it.copy(confirmingDiscard = false) }
                }

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
            // The *ticked* rows, which is what "logged" means everywhere else — see
            // `MarkSetCompletedUseCase`, where an un-ticked row stays in the table and counts
            // towards nothing. The workout screen asks this same dialog with
            // `SessionStats.totalSets`, and the whole point of sharing it is that the two
            // cannot disagree about what throwing a session away costs.
            loggedSets = sessionRepository.getSetsForSession(session.id)
                .first()
                .count(WorkoutSet::isCompleted),
        )
    }

    /**
     * Where the user left off, or null when the draft cannot say.
     *
     * One catalogue row, read by id rather than by walking the whole catalogue for one name.
     * Null when the exercise has been deleted from the library underneath the workout, which
     * drops the line rather than drawing a position with a blank where the movement goes.
     *
     * **The set they are about to do, and no total to put it out of.** A draft's
     * [ActiveExercise.sets] are the rows already written plus at most one being typed — see
     * `ActiveSessionViewModel.draftExercises` — so it counts what has happened, never what was
     * laid out. Nothing in the app holds a planned set count: a workout takes sets until the
     * user stops, and the session screen itself says "3 sets" rather than "3 of 4". A total
     * invented here would be the one place claiming otherwise, and it would read "set 3 of 3"
     * at the exact moment the user is about to start their fourth.
     */
    private suspend fun position(draft: ActiveSession): SessionPosition? {
        val current = draft.currentExercise ?: return null
        val name = exerciseRepository.getById(current.exerciseId).first()?.name ?: return null

        return SessionPosition(
            exerciseName = name,
            // What is done, plus one: the set they came back to do.
            setNumber = current.completedSets.size + 1,
        )
    }

    /** Assemble everything except the clock. */
    private fun assemble(
        sessions: List<WorkoutSession>,
        workout: ActiveWorkout?,
        templates: List<TemplateSummary>,
        screen: ScreenState,
        progression: Progression,
    ): Assembled =
        Assembled(
            state = HomeUiState(
                level = progression.readings.progress?.standing(),
                week = sessions.weekOf(
                    today = progression.day,
                    weekStart = firstDayOfWeek(),
                    streak = progression.readings.progress.liveStreak(),
                ),
                templates = templates.quickStart(sessions),
                recentRecord = progression.readings.record,
                volume = progression.readings.volume.weeks,
                isLoading = false,
                confirmingDiscard = screen.confirmingDiscard,
                isDiscarding = screen.isDiscarding,
                message = screen.message,
            ),
            workout = workout,
        )

    /**
     * The standing as the header draws it: the level, and how far into it.
     *
     * The points into the level are derived from the *total* rather than trusted to be
     * consistent with the stored level, and then clamped — the two are written together and
     * cannot ordinarily disagree, but a retune of the curve leaves rows that were written under
     * the old one, and a bar drawn past its own end or behind its own start is the visible half
     * of that. See `GamificationRepository.addXp`, which is where the pair is kept honest.
     */
    private fun UserProgress.standing(): LevelState {
        val into = (totalXp - XpCalculator.xpRequiredForLevel(level))
            .coerceIn(0, XpCalculator.XP_PER_LEVEL.toLong())

        return LevelState(
            level = level,
            xpIntoLevel = into.toInt(),
            xpForLevel = XpCalculator.XP_PER_LEVEL,
     * The board as the dashboard shows it, or **null** when there is nothing worth showing.
     *
     * ### Which cadence
     *
     * The **weekly** one, when the catalogue has any: it is the near deadline, and a monthly
     * score moves too slowly to earn a place on a screen the user opens every day. A build whose
     * catalogue is monthly-only falls back to whatever the first cadence is rather than drawing
     * nothing, which keeps this correct without the dashboard knowing what ships.
     *
     * ### Null for a user with nothing on the board
     *
     * Not merely for an empty catalogue: a board where every quest sits at zero and none is
     * cleared is a card that would tell a first-week user their score is `0 / 4` and list four
     * things they have not done. That is #110's "no chart of zeros" rule, and [HomeUiState]
     * applies it to every card here. The first logged set moves a quest, and the card appears.
     *
     * ### Nearest first, cut to three
     *
     * The open quests are ordered by how close they are to done, because the question a
     * dashboard answers is "what could I finish today". Three of them: enough to be a prompt,
     * few enough that the card stays a card. The whole board is one tap away.
     */
    private fun List<QuestProgress>.summarise(): QuestSummary? {
        val period = map { it.quest.period }.minByOrNull { it.ordinal } ?: return null
        val rows = filter { it.quest.period == period }
        if (rows.none { it.isComplete || it.progress > 0 }) return null

        return QuestSummary(
            clearedThisPeriod = rows.count { it.isComplete },
            totalThisPeriod = rows.size,
            period = period,
            next = rows.filterNot { it.isComplete }
                .sortedByDescending { it.fraction }
                .take(QUEST_PREVIEW_COUNT),
        )
    }

    /**
     * The streak, if it is still running, and **null** if it is not.
     *
     * A stored streak is only recomputed when a session finishes, so the row keeps saying
     * twelve for as long as nobody trains — see [StreakCalculator.isLive], which is the rule
     * asked here rather than restated.
     *
     * Asked at assembly, which is every time the tab is opened and again at each local
     * midnight. A streak lapses [StreakCalculator.STREAK_GRACE_HOURS] after the session it was
     * counted from rather than on any boundary this screen wakes for, so a phone left on Home
     * can hold a lapsed figure until the day turns. A timer for that would be a wake-up
     * scheduled per user per streak to take away a number nobody is looking at.
     *
     * Zero reads as no streak rather than as "0 days": the store only ever writes at least
     * [com.opengym.core.domain.model.StreakResult.FIRST_DAY] for a session that counted, so a
     * zero here is a user who has never had one.
     */
    private fun UserProgress?.liveStreak(): Int? {
        val progress = this ?: return null
        if (!StreakCalculator.isLive(progress.lastCountedAt, Instant.ofEpochMilli(clock.nowMillis()))) return null
        return progress.currentStreak.takeIf { it > 0 }
    }

    /**
     * The templates the quick-start strip offers: the ones this user actually trains from,
     * most recently started first.
     *
     * **Which handful, not just how many.** `TemplateRepository.getAll` is ordered by name, so
     * taking the first six off it hands a user with twenty templates the same three A-names
     * every morning and buries the split they are three days into. What a shortcut is for is
     * the rotation, and the rotation is legible without storing anything: a session started
     * from a template keeps its [WorkoutSession.templateId], and the sessions are already read
     * for the week above.
     *
     * The sort is stable and templates nobody has trained from all rank alike, so those keep
     * the name order they arrived in — which is the right order for a template that has no
     * history to be ordered by, and the order a brand-new user sees for all of them.
     *
     * The cut happens here rather than in the screen because it is a decision about *which*
     * templates, which is this class's to make; the strip draws what it is handed.
     */
    private fun List<TemplateSummary>.quickStart(sessions: List<WorkoutSession>): List<TemplateSummary> {
        val lastStarted: Map<Long, Long> = sessions
            .mapNotNull { session -> session.templateId?.let { it to session.createdAt } }
            .groupBy({ (templateId, _) -> templateId }, { (_, startedAt) -> startedAt })
            .mapValues { (_, startedAt) -> startedAt.max() }

        return sortedByDescending { lastStarted[it.template.id] ?: NEVER_STARTED }
            .take(QUICK_START_TEMPLATES)
    }

    /** The day the screen is being assembled for, and everything read over it. */
    private data class Progression(
        val day: LocalDate,
        val readings: Readings,
    )

    /** The three progression reads, as one value — see [readProgression]. */
    private data class Readings(
        val progress: UserProgress?,
        val record: PersonalRecordEntry?,
        val volume: VolumeTrend,
    )

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

        fun at(nowMillis: Long) =
            ResumeState(
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

        /** How many open quests the dashboard card lists. The rest are one tap away. */
        const val QUEST_PREVIEW_COUNT = 3

        /** How often the banner's clock is recomputed. It is drawn to the second. */
        val TICK = 1.seconds

        /**
         * How many templates the quick-start strip offers.
         *
         * Six: enough to hold a lifter's whole rotation, few enough that the strip is a
         * shortcut rather than a list. The way to *all* of them is the action above it, which
         * opens the picker that is built for choosing.
         */
        const val QUICK_START_TEMPLATES = 6

        /** Ranks below every real timestamp, so a template never trained from sorts last. */
        const val NEVER_STARTED = Long.MIN_VALUE

        /**
         * How many weeks of volume the card draws.
         *
         * Eight, as the wireframe has it: two months is long enough for a block of training to
         * have a shape and short enough that eight bars are still legible on a 360dp card. The
         * hub (#32) is where a year is read; this is a glance.
         */
        const val VOLUME_WEEKS = 8
    }
}

/**
 * The seven days of the week [today] falls in, and what was logged in each.
 *
 * Returns **null** for a user who has never finished a session, which is what makes the card
 * disappear rather than render as seven empty squares over a zero — see [HomeUiState.week].
 * A user who has trained before but not this week gets an empty strip, which is a real and
 * useful thing to be told — and their streak, if they still have one, goes with it: the strip
 * and the streak answer the same question over two different lengths of time, which is why the
 * wireframe puts them on one line.
 *
 * The session still in progress is not counted. It is drawn by the banner above, and counting
 * a workout that has not happened yet would put a filled square on today for somebody who has
 * so far only pressed START.
 *
 * All seven days are built, including the ones still ahead — the strip is a week and not a
 * week-so-far — and each of those is marked [WeekDay.isFuture] so it is drawn and announced as
 * a day that has not happened rather than as one that was skipped.
 */
private fun List<WorkoutSession>.weekOf(
    today: LocalDate,
    weekStart: DayOfWeek,
    streak: Int?,
): WeekState? {
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
            // The days the week has not reached. Without this the strip cannot tell a day
            // that was skipped from one that has not happened, and a user opening the app on
            // Monday morning reads six rest days and a week already lost — see [WeekDay].
            isFuture = date.isAfter(today),
        )
    }

    return WeekState(
        days = days,
        sessionCount = days.sumOf(WeekDay::sessionCount),
        streak = streak,
    )
}

private const val DAYS_PER_WEEK = 7

/**
 * How long from [nowMillis] until the next local midnight, in milliseconds.
 *
 * [java.time.ZonedDateTime.toLocalDate] then [java.time.LocalDate.atStartOfDay] rather than
 * adding twenty-four hours: the two days either side of a daylight-saving change are twenty-three
 * and twenty-five hours long, and `atStartOfDay(zone)` also answers for the zones where midnight
 * itself is skipped, by giving the first instant the day does have.
 *
 * Floored at one millisecond so the caller cannot spin: a clock that steps forward over the
 * midnight this was measured to would otherwise return zero for as long as it took to notice.
 */
private fun millisUntilTomorrow(
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Long {
    val tomorrow = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(1)
    return (tomorrow.atStartOfDay(zone).toInstant().toEpochMilli() - nowMillis).coerceAtLeast(1L)
}
