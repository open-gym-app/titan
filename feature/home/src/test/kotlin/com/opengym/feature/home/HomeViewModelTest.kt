package com.opengym.feature.home

import com.opengym.core.domain.gamification.Quests
import com.opengym.core.domain.model.ActiveExercise
import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.Quest
import com.opengym.core.domain.model.QuestPeriod
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.UserProgress
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.usecase.DiscardSessionUseCase
import com.opengym.core.domain.usecase.GetTemplateSummariesUseCase
import com.opengym.core.domain.usecase.LatestPersonalRecordUseCase
import com.opengym.core.domain.usecase.QuestProgressUseCase
import com.opengym.core.domain.usecase.ResumeSessionUseCase
import com.opengym.core.domain.usecase.SessionLifecycleLock
import com.opengym.core.domain.usecase.VolumeTrendUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * What Home makes of the stores behind it.
 *
 * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background
 * first: without a collector the flow never starts and the state stays at its initial value,
 * which would make every assertion here pass for the wrong reason.
 *
 * The clock is pinned to a fixed Wednesday, which is what makes "today", the elapsed clock and
 * the week assertable at all. The three dates below are chosen so the week's arithmetic holds
 * **in every locale**: Monday and Wednesday are in the same week whether the locale opens one
 * on Sunday or on Monday, and the Saturday before them is outside it either way. A test that
 * assumed Monday would pass in London and fail in New York.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val sessions = FakeSessionStore()
    private val aggregates = FakeQuestAggregates()
    private val records = FakeQuestRecords()
    private val completions = FakeQuestCompletions()
    private val drafts = FakeDraftStore()
    private val catalogue = FakeExerciseCatalogue(listOf(BENCH_PRESS))
    private val templates = FakeTemplateStore()
    private val state = FakeSessionStateStore()
    private val progress = FakeProgressStore()
    private val records = FakeRecordStore()
    private val volume = FakeVolumeStore()
    private val clock = FixedClock(middayOn(TODAY))

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    // ─── The resume banner ──────────────────────────────────────────────────────────────

    @Test
    fun `nothing in progress leaves the banner off`() =
        runTest {
            sessions.setSessions(listOf(loggedSession(1, TODAY)))

            val home = viewModel().uiState.value

            assertNull(home.resume)
            assertFalse(home.isLoading)
        }

    @Test
    fun `an open session becomes a banner with its name, clock, position and cost`() =
        runTest {
            val startedAt = clock.now - 23.minutes.inWholeMilliseconds
            sessions.setSessions(listOf(openSession(7, startedAt = startedAt)))
            sessions.seedSets(sessionId = 7, count = 11)
            drafts.save(draft(sessionId = 7, startedAt = startedAt, completed = 2, sets = 2))

            val resume = requireNotNull(viewModel().uiState.value.resume)

            assertEquals(7L, resume.sessionId)
            assertEquals("Push Day", resume.name)
            assertEquals(23.minutes, resume.elapsed)
            assertEquals(11, resume.loggedSets)
            // Two ticked off, so the set they came back to do is the third — even though the draft
            // holds two rows and knows of no third. A draft counts what happened, never what was
            // laid out, which is why the banner offers no total to put this out of.
            assertEquals(SessionPosition(exerciseName = "Bench press", setNumber = 3), resume.position)
            assertFalse(resume.isPaused)
        }

    @Test
    fun `an exercise nothing has been logged on yet reads as its first set`() =
        runTest {
            sessions.setSessions(listOf(openSession(7)))
            // One row being typed and nothing ticked: the shape the draft is actually written in
            // when a lifter has just moved onto an exercise.
            drafts.save(draft(sessionId = 7, completed = 0, sets = 1))

            val resume = requireNotNull(viewModel().uiState.value.resume)

            assertEquals(SessionPosition(exerciseName = "Bench press", setNumber = 1), resume.position)
        }

    @Test
    fun `a paused workout says so, and its clock stops where it was paused`() =
        runTest {
            val startedAt = clock.now - 30.minutes.inWholeMilliseconds
            sessions.setSessions(listOf(openSession(7, startedAt = startedAt)))
            drafts.save(
                draft(sessionId = 7, startedAt = startedAt)
                    .copy(pausedAt = startedAt + 8.minutes.inWholeMilliseconds),
            )

            val resume = requireNotNull(viewModel().uiState.value.resume)

            assertTrue(resume.isPaused)
            // Eight minutes in, whatever the wall clock has done in the twenty-two since.
            assertEquals(8.minutes, resume.elapsed)
        }

    @Test
    fun `a session with no draft is still offered, timed from its own row`() =
        runTest {
            // The workout the process died under before anything was written to the draft slot.
            sessions.setSessions(listOf(openSession(7, startedAt = clock.now - 5.minutes.inWholeMilliseconds)))

            val resume = requireNotNull(viewModel().uiState.value.resume)

            assertEquals(5.minutes, resume.elapsed)
            assertNull(resume.position)
        }

    @Test
    fun `a draft belonging to another session is not read onto this one`() =
        runTest {
            sessions.setSessions(listOf(openSession(7)))
            drafts.save(draft(sessionId = 99, completed = 2, sets = 2))

            assertNull(requireNotNull(viewModel().uiState.value.resume).position)
        }

    @Test
    fun `an exercise deleted from the library drops the position rather than blanking it`() =
        runTest {
            sessions.setSessions(listOf(openSession(7)))
            drafts.save(draft(sessionId = 7, exerciseId = 404L))

            assertNull(requireNotNull(viewModel().uiState.value.resume).position)
        }

    // ─── Discarding ─────────────────────────────────────────────────────────────────────

    @Test
    fun `discarding is asked about before anything is deleted`() =
        runTest {
            sessions.setSessions(listOf(openSession(7)))
            val viewModel = viewModel()

            viewModel.onEvent(HomeEvent.DiscardRequested)

            assertTrue(viewModel.uiState.value.confirmingDiscard)
            assertEquals(listOf(7L), sessions.storedSessions().map(WorkoutSession::id))
        }

    @Test
    fun `confirming the discard deletes the session, clears the draft and drops the banner`() =
        runTest {
            sessions.setSessions(listOf(openSession(7)))
            drafts.save(draft(sessionId = 7))
            val viewModel = viewModel()

            viewModel.onEvent(HomeEvent.DiscardRequested)
            viewModel.onEvent(HomeEvent.DiscardConfirmed)

            val home = viewModel.uiState.value
            assertNull(home.resume)
            assertFalse(home.confirmingDiscard)
            assertEquals(emptyList<WorkoutSession>(), sessions.storedSessions())
            assertEquals(1, drafts.clearCount)
            assertEquals(SessionState.Idle, state.current)
        }

    @Test
    fun `a discard reached after a process restart still finds the session to delete`() =
        runTest {
            // The case Home exists for: the row says in progress, the in-memory state machine has
            // been reset to Idle by the restart. Without the resume that precedes the discard,
            // `DiscardSessionUseCase` has no open session and refuses.
            sessions.setSessions(listOf(openSession(7)))
            val viewModel = viewModel(state = FakeSessionStateStore(SessionState.Idle))

            viewModel.onEvent(HomeEvent.DiscardConfirmed)

            assertEquals(emptyList<WorkoutSession>(), sessions.storedSessions())
            assertNull(viewModel.uiState.value.message)
        }

    @Test
    fun `a discard that fails leaves the workout where it is and says so`() =
        runTest {
            sessions.setSessions(listOf(openSession(7)))
            sessions.deleteFailure = IllegalStateException("the store is gone")
            val viewModel = viewModel()

            viewModel.onEvent(HomeEvent.DiscardRequested)
            viewModel.onEvent(HomeEvent.DiscardConfirmed)

            val home = viewModel.uiState.value
            assertEquals(HomeMessage.DISCARD_FAILED, home.message)
            assertEquals(listOf(7L), sessions.storedSessions().map(WorkoutSession::id))
            // The banner is still up, because the workout is still there and still in the way.
            assertEquals(7L, home.resume?.sessionId)
            assertFalse(home.isDiscarding)
        }

    @Test
    fun `a message is dropped once it has been shown`() =
        runTest {
            sessions.setSessions(listOf(openSession(7)))
            sessions.deleteFailure = IllegalStateException("the store is gone")
            val viewModel = viewModel()

            viewModel.onEvent(HomeEvent.DiscardConfirmed)
            viewModel.onEvent(HomeEvent.MessageShown)

            assertNull(viewModel.uiState.value.message)
        }

    // ─── This week ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the week marks the days trained and counts them`() =
        runTest {
            sessions.setSessions(
                listOf(
                    loggedSession(1, MONDAY),
                    loggedSession(2, TODAY),
                    // Outside the week, whichever day the locale opens one on.
                    loggedSession(3, LAST_SATURDAY),
                ),
            )

            val week = requireNotNull(viewModel().uiState.value.week)

            assertEquals(7, week.days.size)
            assertEquals(2, week.sessionCount)
            assertEquals(1, week.days.single { it.date == MONDAY }.sessionCount)
            assertEquals(1, week.days.single { it.date == TODAY }.sessionCount)
            assertTrue(week.days.none { it.date == LAST_SATURDAY })
            assertTrue(week.days.single { it.date == TODAY }.isToday)
        }

    @Test
    fun `the days the week has not reached are marked, today is not one of them`() =
        runTest {
            sessions.setSessions(listOf(loggedSession(1, MONDAY)))

            val week = requireNotNull(viewModel().uiState.value.week)

            // Without this the strip cannot tell a day that was skipped from one that has not
            // happened, and a Monday morning reads as a week already lost — see WeekStrip.
            assertTrue(week.days.single { it.date == TOMORROW }.isFuture)
            assertFalse(week.days.single { it.date == TODAY }.isFuture)
            assertFalse(week.days.single { it.date == MONDAY }.isFuture)
        }

    @Test
    fun `two sessions on one day count twice on one cell`() =
        runTest {
            sessions.setSessions(listOf(loggedSession(1, TODAY), loggedSession(2, TODAY)))

            val week = requireNotNull(viewModel().uiState.value.week)

            assertEquals(2, week.sessionCount)
            assertEquals(2, week.days.single { it.date == TODAY }.sessionCount)
        }

    @Test
    fun `a user who has never finished a session gets no week at all`() =
        runTest {
            // Not an empty strip over a zero — see HomeUiState.week. The open session below is
            // deliberately in the store: a workout that has not happened yet is not a week's
            // worth of training.
            sessions.setSessions(listOf(openSession(7)))

            val home = viewModel().uiState.value

            assertNull(home.week)
            assertFalse(home.isLoading)
        }

    @Test
    fun `the session still running is not counted in the week`() =
        runTest {
            sessions.setSessions(listOf(loggedSession(1, TODAY), openSession(7)))

            val week = requireNotNull(viewModel().uiState.value.week)

            assertEquals(1, week.sessionCount)
        }

    @Test
    fun `midnight moves the week on with nobody touching the screen`() =
        runTest {
            // Home is the screen the app is left sitting on. Nothing else re-emits overnight, so a
            // day read while assembling would leave the ring on yesterday until something did.
            sessions.setSessions(listOf(loggedSession(1, TODAY)))
            val viewModel = viewModel()
            assertTrue(requireNotNull(viewModel.uiState.value.week).days.single { it.date == TODAY }.isToday)

            clock.now = middayOn(TOMORROW)
            // Past the midnight twelve hours out, and short of the one after it.
            advanceTimeBy(25.hours)

            val week = requireNotNull(viewModel.uiState.value.week)
            assertTrue(week.days.single { it.date == TOMORROW }.isToday)
            assertFalse(week.days.single { it.date == TODAY }.isToday)
        }

    // ─── Quick start ────────────────────────────────────────────────────────────────────

    @Test
    fun `the quick-start strip holds the templates, cut to what fits`() =
        runTest {
            val home = viewModel(templates = eightTemplates()).uiState.value

            assertEquals(6, home.templates.size)
            // Nothing has been trained from, so nothing outranks anything and the name order the
            // repository promises is what is left.
            assertEquals("Template 1", home.templates.first().template.name)
        }

    @Test
    fun `the strip leads with the templates most recently trained from`() =
        runTest {
            // The finding the alphabetical cut hid: a user with more templates than the strip holds
            // saw the first six names for ever, and never their own rotation.
            sessions.setSessions(
                listOf(
                    loggedSession(1, LAST_SATURDAY, templateId = 8L),
                    loggedSession(2, TODAY, templateId = 7L),
                ),
            )

            val home = viewModel(templates = eightTemplates()).uiState.value

            assertEquals(
                listOf("Template 7", "Template 8") + (1..4).map { "Template $it" },
                home.templates.map { it.template.name },
            )
        }

    @Test
    fun `a template is ranked on the last time it was trained from, not the first`() =
        runTest {
            sessions.setSessions(
                listOf(
                    loggedSession(1, TODAY, templateId = 8L),
                    loggedSession(2, LAST_SATURDAY, templateId = 8L),
                    loggedSession(3, MONDAY, templateId = 7L),
                ),
            )

            val home = viewModel(templates = eightTemplates()).uiState.value

            assertEquals(
                listOf("Template 8", "Template 7"),
                home.templates.take(2).map { it.template.name },
            )
        }

    @Test
    fun `no saved templates is a real answer rather than a loading one`() =
        runTest {
            val home = viewModel().uiState.value

            assertEquals(emptyList<Long>(), home.templates.map { it.template.id })
            assertFalse(home.isLoading)
        }

    // ─── The progression cards ──────────────────────────────────────────────────────────

    @Test
    fun `the standing becomes a level and the points banked into it`() =
        runTest {
            // 1,340 points on a hundred-a-level curve: level 14, forty points in.
            progress.setProgress(standing(totalXp = 1_340, level = 14))

            val level = requireNotNull(viewModel().uiState.value.level)

            assertEquals(14, level.level)
            assertEquals(40, level.xpIntoLevel)
            assertEquals(100, level.xpForLevel)
        }

    @Test
    fun `nothing banked yet draws no header at all`() =
        runTest {
            // Not `LVL 1 · 0 / 100 XP`. A user who has done nothing is told what they can do,
            // not what they have not — see HomeUiState.level.
            val home = viewModel().uiState.value

            assertNull(home.level)
            assertFalse(home.isLoading)
        }

    @Test
    fun `a streak counted today is still running, and is drawn`() =
        runTest {
            sessions.setSessions(listOf(loggedSession(1, TODAY)))
            progress.setProgress(standing(currentStreak = 12, lastCountedAt = TODAY))

            assertEquals(12, requireNotNull(viewModel().uiState.value.week).streak)
        }

    @Test
    fun `a streak counted yesterday is still running`() =
        runTest {
            // The boundary the whole mechanic turns on: the day after counts, so a lifter who
            // trained yesterday and has not trained today has not lost anything yet.
            sessions.setSessions(listOf(loggedSession(1, TODAY)))
            progress.setProgress(standing(currentStreak = 4, lastCountedAt = TODAY.minusDays(1)))

            assertEquals(4, requireNotNull(viewModel().uiState.value.week).streak)
        }

    @Test
    fun `a streak nobody kept up is not drawn, however long it was`() =
        runTest {
            // The stored figure is still 12: a streak is only recomputed when a session
            // finishes, and nothing has. Drawing it would tell somebody who last trained five
            // days ago that they are twelve days deep.
            sessions.setSessions(listOf(loggedSession(1, TODAY)))
            progress.setProgress(standing(currentStreak = 12, lastCountedAt = TODAY.minusDays(5)))

            assertNull(requireNotNull(viewModel().uiState.value.week).streak)
        }

    @Test
    fun `a user who has never had a streak is not shown a zero`() =
        runTest {
            sessions.setSessions(listOf(loggedSession(1, TODAY)))
            progress.setProgress(standing(currentStreak = 0, lastCountedAt = null))

            assertNull(requireNotNull(viewModel().uiState.value.week).streak)
        }

    @Test
    fun `the newest record is the one the card draws`() =
        runTest {
            records.setRecords(listOf(record(id = 2, weight = 142_000_000), record(id = 1, weight = 135_000_000)))

            val record = requireNotNull(viewModel().uiState.value.recentRecord)

            assertEquals(2L, record.record.id)
        }

    @Test
    fun `a user who has broken no record gets no card`() =
        runTest {
            val home = viewModel().uiState.value

            assertNull(home.recentRecord)
            assertFalse(home.isLoading)
        }

    @Test
    fun `the chart covers eight weeks, the untrained ones as zeroes`() =
        runTest {
            // Nine weeks seeded, one of them empty: the ninth is outside the window and the
            // empty one is a bucket rather than a gap, or the bars either side of it would be
            // drawn as if training had carried on through it.
            volume.setWeeks(
                lastWeekOf = TODAY,
                volumes = listOf(90_000, 10_000, 20_000, 30_000, 40_000, 0, 60_000, 70_000, 80_000),
            )

            val weeks = viewModel().uiState.value.volume

            assertEquals(8, weeks.size)
            assertEquals(
                listOf(10_000L, 20_000L, 30_000L, 40_000L, 0L, 60_000L, 70_000L, 80_000L),
                weeks.map { it.totalVolume },
    // ─── The quest card ─────────────────────────────────────────────────────────────────

    @Test
    fun `a board nobody has touched draws no card at all`() =
        runTest {
            // Every quest at zero and none cleared: #110's "no chart of zeros" rule, applied to
            // a board — see [QuestSummary]. The card appears the moment something moves.
            val viewModel = viewModel(questCatalogue = Quests.ALL)

            assertNull("nothing on the board is nothing to draw", viewModel.uiState.value.quests)
        }

    @Test
    fun `one finished session is enough to bring the card out`() =
        runTest {
            aggregates.sessionsFinished.value = 1

            val viewModel = viewModel(questCatalogue = Quests.ALL)

            val quests = requireNotNull(viewModel.uiState.value.quests) { "the card is drawn" }
            assertEquals(QuestPeriod.WEEKLY, quests.period)
            assertEquals(0, quests.clearedThisPeriod)
        }

    @Test
    fun `the card scores the weekly cadence, not the monthly one`() =
        runTest {
            // The near deadline. A monthly score moves too slowly to earn a place on a screen
            // the user opens every day — see [HomeViewModel.summarise].
            aggregates.sessionsFinished.value = 3
            completions.completed.value = setOf("weekly_sessions")

            val viewModel = viewModel(questCatalogue = Quests.ALL)

            val quests = requireNotNull(viewModel.uiState.value.quests) { "the card is drawn" }
            assertEquals(QuestPeriod.WEEKLY, quests.period)
            assertEquals(WEEKLY_QUEST_COUNT, quests.totalThisPeriod)
            assertEquals(1, quests.clearedThisPeriod)
        }

    @Test
    fun `the quests listed are the ones nearest to done, hardest last`() =
        runTest {
            // Two of three sessions and a fifth of the tonnage: "train three times" is the
            // thing that could be finished today, so it leads.
            aggregates.sessionsFinished.value = 2
            aggregates.volumeMilligrams.value = 4_000_000L

            val viewModel = viewModel(questCatalogue = Quests.ALL)

            val quests = requireNotNull(viewModel.uiState.value.quests) { "the card is drawn" }
            assertEquals("weekly_sessions", quests.next.first().quest.id)
            assertTrue(
                "ordered by how close each one is",
                quests.next.map { it.fraction } == quests.next.map { it.fraction }.sortedDescending(),
            )
        }

    @Test
    fun `xp banked behind the screen moves the header with nobody revisiting it`() =
        runTest {
            // The post-session cascade writes while Home is the screen underneath — the recap
            // is over it, and backing out of the recap lands here without a fresh read. All
            // four cards are flows for this reason; the header is the one with a figure that
            // moves on every set.
            val viewModel = viewModel()
            assertNull(viewModel.uiState.value.level)

            progress.setProgress(standing(totalXp = 1_340, level = 14))

            assertEquals(14, viewModel.uiState.value.level?.level)
        }

    @Test
    fun `a record broken behind the screen appears with nobody revisiting it`() =
        runTest {
            val viewModel = viewModel()
            assertNull(viewModel.uiState.value.recentRecord)

            records.setRecords(listOf(record(id = 1, weight = 142_000_000)))

            assertEquals(1L, viewModel.uiState.value.recentRecord?.record?.id)
        }

    @Test
    fun `eight weeks with nothing in them draw no chart`() =
        runTest {
            // A brand-new user and a lapsed one both land here, and neither is told anything by
            // eight noughts — see HomeUiState.volume.
            val home = viewModel().uiState.value

            assertEquals(emptyList<Long>(), home.volume.map { it.totalVolume })
            assertFalse(home.isLoading)
    fun `the card lists a handful, not the whole board`() =
        runTest {
            aggregates.sessionsFinished.value = 1

            val viewModel = viewModel(questCatalogue = Quests.ALL)

            val quests = requireNotNull(viewModel.uiState.value.quests) { "the card is drawn" }
            assertEquals(QUEST_PREVIEW_COUNT, quests.next.size)
        }

    @Test
    fun `a cleared quest leaves the list and is counted instead`() =
        runTest {
            aggregates.sessionsFinished.value = 3
            completions.completed.value = setOf("weekly_sessions")

            val viewModel = viewModel(questCatalogue = Quests.ALL)

            val quests = requireNotNull(viewModel.uiState.value.quests) { "the card is drawn" }
            assertEquals(1, quests.clearedThisPeriod)
            assertTrue(
                "a banked quest is not offered again",
                quests.next.none { it.quest.id == "weekly_sessions" },
            )
        }

    @Test
    fun `clearing every weekly quest is its own state`() =
        runTest {
            aggregates.sessionsFinished.value = 5
            completions.completed.value = Quests.ALL
                .filter { it.period == QuestPeriod.WEEKLY }
                .mapTo(mutableSetOf()) { it.id }

            val viewModel = viewModel(questCatalogue = Quests.ALL)

            val quests = requireNotNull(viewModel.uiState.value.quests) { "the card is drawn" }
            assertTrue("the cadence is cleared", quests.isCleared)
            assertEquals(emptyList<Any>(), quests.next)
        }

    @Test
    fun `a quest cleared while Home is open moves the score`() =
        runTest {
            aggregates.sessionsFinished.value = 3
            val viewModel = viewModel(questCatalogue = Quests.ALL)
            assertEquals(0, requireNotNull(viewModel.uiState.value.quests).clearedThisPeriod)

            completions.completed.value = setOf("weekly_sessions")

            assertEquals(1, requireNotNull(viewModel.uiState.value.quests).clearedThisPeriod)
        }

    @Test
    fun `the dashboard draws before the board has answered`() =
        runTest {
            // The board's aggregates are the slowest read Home makes, and `combine` waits for
            // every source — so the quest flow starts with a null rather than holding the whole
            // screen back. See [HomeViewModel.quests].
            val viewModel = viewModel()

            assertFalse("the rest of the screen has loaded", viewModel.uiState.value.isLoading)
            assertNull("with no card yet", viewModel.uiState.value.quests)
        }

    // ─── Rig ────────────────────────────────────────────────────────────────────────────

    /** The stored progress row, with only the figures a test cares about spelled out. */
    private fun standing(
        totalXp: Long = 0,
        level: Int = 1,
        currentStreak: Int = 0,
        lastCountedAt: LocalDate? = null,
    ) = UserProgress(
        totalXp = totalXp,
        level = level,
        currentStreak = currentStreak,
        lastCountedAt = lastCountedAt?.let { Instant.ofEpochMilli(middayOn(it)) },
    )

    /** A heaviest-lift record of [weight] milligrams, set today. */
    private fun record(
        id: Long,
        weight: Long,
    ) = PersonalRecordEntry(
        record = PersonalRecord(
            id = id,
            exerciseId = 1L,
            workoutSetId = id,
            type = PrType.MAX_WEIGHT,
            weight = weight,
            achievedAt = clock.now,
        ),
        exerciseName = "Deadlift",
        previousValue = null,
        isRecent = true,
    )

    /** Eight saved templates, `Template 1` to `Template 8` — two more than the strip holds. */
    private fun eightTemplates(): FakeTemplateStore {
        val saved = (1..8).map { template(it.toLong(), "Template $it") }
        return FakeTemplateStore(
            templates = saved.map { it.first },
            exercises = saved.flatMap { it.second },
        )
    }

    private fun TestScope.viewModel(
        sessions: FakeSessionStore = this@HomeViewModelTest.sessions,
        drafts: FakeDraftStore = this@HomeViewModelTest.drafts,
        templates: FakeTemplateStore = this@HomeViewModelTest.templates,
        state: FakeSessionStateStore = this@HomeViewModelTest.state,
        // Empty by default, so the quest card is absent and every test in this suite stays
        // about what it is named for. The board's own reads are never touched with no
        // catalogue to read them for — see `QuestProgressUseCase.at`.
        questCatalogue: List<Quest> = emptyList(),
    ): HomeViewModel {
        // A lock of this test's own rather than the shared one, so two tests running in the
        // same JVM cannot wait on each other's transitions.
        val lock = SessionLifecycleLock()
        val viewModel = HomeViewModel(
            sessionRepository = sessions,
            inProgressSessionRepository = drafts,
            exerciseRepository = catalogue,
            gamificationRepository = progress,
            getTemplateSummaries = GetTemplateSummariesUseCase(templates, FakeSettings()),
            latestPersonalRecord = LatestPersonalRecordUseCase(records),
            volumeTrend = VolumeTrendUseCase(volume),
            questProgress = QuestProgressUseCase(
                progressionRepository = aggregates,
                personalRecordRepository = records,
                questRepository = completions,
                clock = clock,
                catalogue = questCatalogue,
            ),
            resumeSession = ResumeSessionUseCase(sessions, state, clock, lock),
            discardSession = DiscardSessionUseCase(sessions, state, drafts, lock),
            clock = clock,
        )
        // Unconfined *on the test scheduler*, so the flow is started and has emitted by the
        // time this returns.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return viewModel
    }

    /** A workout that is over: the rows the week is counted from. */
    private fun loggedSession(
        id: Long,
        date: LocalDate,
        templateId: Long? = null,
    ) = WorkoutSession(
        id = id,
        templateId = templateId,
        name = "Session $id",
        isInProgress = false,
        completedAt = middayOn(date) + 52.minutes.inWholeMilliseconds,
        lastActiveAt = middayOn(date),
        createdAt = middayOn(date),
        updatedAt = middayOn(date),
    )

    /** The workout the app is still holding open. */
    private fun openSession(
        id: Long,
        startedAt: Long = clock.now,
    ) = WorkoutSession(
        id = id,
        name = "Push Day",
        isInProgress = true,
        lastActiveAt = startedAt,
        createdAt = startedAt,
        updatedAt = startedAt,
    )

    /**
     * A draft holding [sets] rows on one exercise, [completed] of them ticked.
     *
     * [sets] is what a draft actually carries — the rows already written plus at most one being
     * typed, per `ActiveSessionViewModel.draftExercises` — and not a plan. A test that hands
     * over four rows with two ticked is describing a draft the app never writes.
     */
    private fun draft(
        sessionId: Long,
        startedAt: Long = clock.now,
        completed: Int = 0,
        sets: Int = 0,
        exerciseId: Long = BENCH_PRESS.id,
    ) = ActiveSession(
        sessionId = sessionId,
        startedAt = startedAt,
        exercises = listOf(
            ActiveExercise(
                exerciseId = exerciseId,
                sessionExerciseId = 1L,
                sets = List(sets) { index ->
                    val done = index < completed
                    ActiveSet(isCompleted = done, completedAt = startedAt.takeIf { done })
                },
            ),
        ),
    )

    private companion object {
        /**
         * A Wednesday, and "today" for every test here.
         *
         * With [MONDAY] two days before it and [LAST_SATURDAY] four, the first two fall in the
         * same week and the third outside it under both a Sunday-opening and a Monday-opening
         * locale — which is what keeps these assertions from depending on where CI runs.
         */
        val TODAY: LocalDate = LocalDate.of(2026, 4, 29)
        val MONDAY: LocalDate = LocalDate.of(2026, 4, 27)
        val LAST_SATURDAY: LocalDate = LocalDate.of(2026, 4, 25)

        /** The Thursday after [TODAY], which is in the same week under either locale too. */
        val TOMORROW: LocalDate = LocalDate.of(2026, 4, 30)

        val BENCH_PRESS = Exercise(id = 1L, name = "Bench press", type = ExerciseType.STRENGTH)

        /**
         * Midday on [date] in the device's own zone.
         *
         * Local midday rather than midday UTC: what is being asserted is which *calendar day*
         * a session lands on, and that is read in the default zone — a timestamp fixed in UTC
         * would drift onto the day before in Auckland.
         */
        fun middayOn(date: LocalDate): Long =
            date.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

/** How many quests the shipped catalogue puts on the weekly board. */
private const val WEEKLY_QUEST_COUNT = 4

/** How many open quests the card lists. Stated here rather than read off the ViewModel, so a
 * change to the cut shows up as a failing test rather than as a test that agrees with itself. */
private const val QUEST_PREVIEW_COUNT = 3
