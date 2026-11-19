package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.Achievements
import com.titan.core.domain.gamification.DropRates
import com.titan.core.domain.gamification.DropTableEvaluator
import com.titan.core.domain.integration.FakeExerciseRepository
import com.titan.core.domain.model.ActiveSession
import com.titan.core.domain.model.FinishOrigin
import com.titan.core.domain.model.SessionError
import com.titan.core.domain.model.SessionProgression
import com.titan.core.domain.model.SessionProgressionStep
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.model.SessionSummary
import com.titan.core.domain.model.StreakResult
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.ProgressionAnnouncer
import com.titan.core.domain.random.RandomSource
import com.titan.core.domain.repository.FakeBodyMeasurementRepository
import com.titan.core.domain.repository.FakeInProgressSessionRepository
import com.titan.core.domain.stats.CalorieEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class FinishSessionUseCaseTest {

    private val clock = MutableClock(now = 5_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val drafts = FakeInProgressSessionRepository()
    private val startSession = StartSessionUseCase(sessions, state, clock)
    private val pauseSession = PauseSessionUseCase(sessions, state, clock)
    private val finishSession = FinishSessionUseCase(sessions, state, drafts, clock)

    private val personalRecords = FakePersonalRecordRepository()
    private val gamification = FakeGamificationRepository()
    private val quests = FakeQuestRepository()
    private val awardXp = AwardXpUseCase(gamification)

    /**
     * The cascade, as a property rather than inline, so the two use cases below can be the
     * same one with and without something listening for its result.
     */
    private val cascade = SessionCompletionPipeline(
        detectPersonalRecords = PrDetectionUseCase(
            sessionRepository = sessions,
            // Empty: an exercise the catalogue does not hold is judged as a lift, which is what
            // every set in this suite is.
            exerciseRepository = FakeExerciseRepository(),
            personalRecordRepository = personalRecords,
            userPreferencesRepository = FakeUserPreferencesRepository(),
        ),
        awardXp = awardXp,
        triggerAchievements = AchievementTriggerUseCase(gamification, awardXp),
        // Over an empty catalogue: this suite is about finishing a session, not about what a
        // quest is worth, and the real catalogue would complete `weekly_record` on every
        // session here that breaks one. See `SessionCompletionPipelineTest.Rig`, which makes
        // the same call for the same reason.
        completeQuests = QuestCompletionUseCase(
            questProgress = QuestProgressUseCase(
                progressionRepository = FakeProgressionRepository(),
                personalRecordRepository = personalRecords,
                // The same store the pass writes to, so the board could see a completion it
                // banked — two instances would let the filter read a table nothing ever writes.
                questRepository = quests,
                catalogue = emptyList(),
            ),
            questRepository = quests,
            awardXp = awardXp,
        ),
        // Over a table that drops nothing, for the reason the quest catalogue is empty: this
        // suite is about finishing a session, and a drop it does not assert would only be a row
        // nobody reads. `SessionCompletionPipelineTest` is where the roll is tested.
        rollLoot = LootRollUseCase(
            dropTable = DropTableEvaluator(RandomSource { DropRates.TOTAL_WEIGHT - 1 }, emptyList()),
            collectedItemRepository = FakeCollectedItemRepository(),
            gamificationRepository = gamification,
        ),
        sessionRepository = sessions,
        personalRecordRepository = personalRecords,
        gamificationRepository = gamification,
        // A goal of one, so the finished session is a week met and the streak moves.
        userPreferencesRepository = FakeUserPreferencesRepository(UserPreferences(weeklyWorkoutGoal = 1)),
    )

    /**
     * The same use case with the gamification cascade wired in, which the app had no binding
     * for until #35 landed. [finishSession] is deliberately the plain one: the session's own
     * behaviour is not supposed to change with a cascade attached, and a suite that only ever
     * ran the wired variant could not tell.
     */
    private val finishWithCascade = FinishSessionUseCase(sessions, state, drafts, clock, completeSession = cascade)

    private val announcer = RecordingProgressionAnnouncer()

    /** The cascade-wired use case with something listening for what it reported. */
    private val finishWithAnnouncer =
        FinishSessionUseCase(sessions, state, drafts, clock, completeSession = cascade, announce = announcer)

    /** The same, with nothing pricing the session — so there is no payoff to announce. */
    private val finishAnnouncedWithoutCascade =
        FinishSessionUseCase(sessions, state, drafts, clock, completeSession = null, announce = announcer)

    // --- announcing the payoff --------------------------------------------

    @Test
    fun `the payoff is handed to the announcer exactly as the cascade reported it`() =
        runTest {
            // The one thing this use case owes #179: the value it already has, passed on
            // unchanged. What is worth saying about it, and whether anything is at all, is the
            // announcer's own — nothing here inspects it.
            startWithSets()

            val finished = finishWithAnnouncer().getOrThrow()

            assertEquals(listOf(finished.progression), announcer.announced)
        }

    @Test
    fun `the announcer is told how the finish was asked for`() =
        runTest {
            // The whole of what the announcer needs to know: this one has no recap behind it,
            // so it is the announcement or nothing. Passing it is the caller's job — see
            // `FinishOrigin` — and this use case only carries it through.
            startWithSets()

            finishWithAnnouncer(FinishOrigin.NOTIFICATION_ACTION).getOrThrow()

            assertEquals(listOf(FinishOrigin.NOTIFICATION_ACTION), announcer.origins)
        }

    @Test
    fun `a finish that says nothing about its origin is the workout screen's`() =
        runTest {
            // The default, and the ordinary case: END was pressed and the recap that opens next
            // is about to celebrate this. A caller with no recap has to say so, so the default
            // is the one whose mistake is a notification not posted rather than one posted over
            // a screen already saying the same thing.
            startWithSets()

            finishWithAnnouncer().getOrThrow()

            assertEquals(listOf(FinishOrigin.WORKOUT_SCREEN), announcer.origins)
        }

    @Test
    fun `a finish with no progression announces nothing`() =
        runTest {
            // Nothing was priced, so there is nothing to say. The announcer is not called with
            // an empty result either, which would make "nothing happened" and "nobody worked
            // out whether anything happened" the same event to it.
            startWithSets()

            finishAnnouncedWithoutCascade().getOrThrow()

            assertTrue(announcer.announced.isEmpty())
        }

    @Test
    fun `a refused finish announces nothing`() =
        runTest {
            // No sets, so the finish is refused and the session stays open. Announcing a payoff
            // for a workout that was not closed out would be announcing one that does not exist.
            startWithSets(sets = 0)

            val outcome = finishWithAnnouncer()

            assertTrue(outcome.isFailure)
            assertTrue(announcer.announced.isEmpty())
        }

    @Test
    fun `an announcer that throws leaves the workout finished`() =
        runTest {
            // By the time it runs the row is closed out and the payoff is banked. A
            // notification that could not be posted must not come back as a finish that did
            // not happen — the same trade the draft clear and the cascade both make.
            val sessionId = startWithSets()
            announcer.failure = IllegalStateException("the notification manager is gone")

            val finished = finishWithAnnouncer().getOrThrow()

            assertEquals(sessionId, finished.summary.sessionId)
            assertEquals(false, sessions.stored(sessionId)?.isInProgress)
        }

    @Test
    fun `a cancellation while announcing is rethrown rather than swallowed`() =
        runTest {
            // Best-effort covers a store or a notification manager that refused; a caller whose
            // scope went away is neither, and `runCatching` cannot tell the two apart. Since the
            // announcement is the last thing the finish does, swallowing it would hand a
            // cancelled coroutine a finish that came back normally — which is the answer
            // `ActiveSessionViewModel` rethrows around this very call to avoid.
            startWithSets()
            announcer.failure = CancellationException("the screen went away")

            val thrown = runCatching { finishWithAnnouncer() }.exceptionOrNull()

            assertTrue("expected the cancellation to propagate, got $thrown", thrown is CancellationException)
        }

    private val healthConnectSync = RecordingHealthConnectSyncScheduler()

    /** The finish with the Health Connect queue wired behind it. */
    private val finishWithHealthConnect =
        FinishSessionUseCase(sessions, state, drafts, clock, healthConnectSync = healthConnectSync)

    @Test
    fun `a finished session is queued for Health Connect`() =
        runTest {
            val sessionId = startWithSets()

            finishWithHealthConnect().getOrThrow()

            assertEquals(listOf(sessionId), healthConnectSync.requested)
        }

    @Test
    fun `a refused finish queues nothing for Health Connect`() =
        runTest {
            startSession().getOrThrow()

            finishWithHealthConnect()

            assertTrue(healthConnectSync.requested.isEmpty())
        }

    @Test
    fun `a queue that refuses the request leaves the workout finished`() =
        runTest {
            val sessionId = startWithSets()
            healthConnectSync.failure = IllegalStateException("WorkManager is not initialised")

            val finished = finishWithHealthConnect().getOrThrow()

            assertEquals(sessionId, finished.summary.sessionId)
            assertEquals(false, sessions.stored(sessionId)?.isInProgress)
        }

    /** [ProgressionAnnouncer] as a list of what it was handed. */
    private class RecordingProgressionAnnouncer : ProgressionAnnouncer {

        val announced = mutableListOf<SessionProgression>()

        /** The origin each of [announced] came with, in the same order. */
        val origins = mutableListOf<FinishOrigin>()

        /** Thrown instead of recording, when set. */
        var failure: Throwable? = null

        override suspend fun announce(
            progression: SessionProgression,
            origin: FinishOrigin,
        ) {
            failure?.let { throw it }
            announced += progression
            origins += origin
        }
    }

    /** Starts a session with one exercise carrying [sets] completed sets, and returns its id. */
    private suspend fun startWithSets(sets: Int = 1): Long {
        val started = startSession().getOrThrow()
        val exerciseId = sessions.addExercise(started.sessionId)
        repeat(sets) { sessions.addSet(exerciseId) }
        return started.sessionId
    }

    // --- estimating the calories ------------------------------------------

    private fun estimator(failing: Boolean = false) =
        EstimateSessionCaloriesUseCase(
            sessionRepository = sessions,
            exerciseRepository = FakeExerciseRepository(),
            userPreferencesRepository = FakeUserPreferencesRepository(),
            bodyMeasurementRepository = FakeBodyMeasurementRepository(failing = failing),
        )

    @Test
    fun `finishing writes the estimated calories onto a session that has none`() =
        runTest {
            val sessionId = startWithSets(sets = 3)

            FinishSessionUseCase(sessions, state, drafts, clock, estimateCalories = estimator())().getOrThrow()

            // Three sets of ten on the shipped default rest, at the reference body mass.
            val seconds = 3 * 30 + 2 * UserPreferences.DEFAULT_REST_DURATION.inWholeSeconds
            val expected = CalorieEstimate.STRENGTH_MET * CalorieEstimate.REFERENCE_BODY_MASS_MG / 1_000_000 *
                seconds / 3_600
            assertEquals(expected.roundToInt(), sessions.getById(sessionId).first()?.calories)
        }

    @Test
    fun `a calorie figure already on the session is the user's, and is kept`() =
        runTest {
            val sessionId = startWithSets()
            RecordSessionEffortUseCase(sessions)(sessionId, calories = 640, rating = null).getOrThrow()

            FinishSessionUseCase(sessions, state, drafts, clock, estimateCalories = estimator())().getOrThrow()

            assertEquals(640, sessions.getById(sessionId).first()?.calories)
        }

    @Test
    fun `an estimate that cannot be made leaves the calories empty and the session finished`() =
        runTest {
            val sessionId = startWithSets()

            val finished = FinishSessionUseCase(
                sessions,
                state,
                drafts,
                clock,
                estimateCalories = estimator(failing = true),
            )().getOrThrow()

            val session = sessions.getById(sessionId).first()
            assertNull(session?.calories)
            assertEquals(false, session?.isInProgress)
            assertEquals(sessionId, finished.summary.sessionId)
        }

    @Test
    fun `with no estimator the calories are left as they were`() =
        runTest {
            val sessionId = startWithSets()

            finishSession().getOrThrow()

            assertNull(sessions.getById(sessionId).first()?.calories)
        }

    @Test
    fun `finishing an active session with logged sets succeeds`() =
        runTest {
            startWithSets()
            clock.advance(60_000L)

            val finished = finishSession().getOrThrow()

            assertEquals(
                SessionSummary(
                    sessionId = 1L,
                    startedAt = 5_000L,
                    finishedAt = 65_000L,
                    durationMillis = 60_000L,
                    exerciseCount = 1,
                    totalSets = 1,
                    totalVolume = 500_000L,
                ),
                finished.summary,
            )
        }

    @Test
    fun `finishing publishes the finished state`() =
        runTest {
            startWithSets()

            val finished = finishSession().getOrThrow()

            assertEquals(finished.state, state.current)
        }

    @Test
    fun `finishing closes the session row out`() =
        runTest {
            val sessionId = startWithSets()
            clock.advance(60_000L)

            finishSession().getOrThrow()

            val stored = sessions.stored(sessionId)
            assertEquals(false, stored?.isInProgress)
            assertEquals(65_000L, stored?.completedAt)
            assertEquals(65_000L, stored?.lastActiveAt)
            assertEquals(65_000L, stored?.updatedAt)
        }

    @Test
    fun `finishing clears the in-progress draft`() =
        runTest {
            // A draft outliving its session is what makes the next launch offer to resume a
            // workout that is already over.
            val sessionId = startWithSets()
            drafts.save(ActiveSession(sessionId = sessionId, startedAt = 5_000L))

            finishSession().getOrThrow()

            assertNull(drafts.load())
        }

    @Test
    fun `a draft that will not clear does not undo a finish that happened`() =
        runTest {
            // By the time the clear runs, the row is closed out and the state is Finished. Letting
            // an IO failure out here would report "nothing was saved" over a saved session — and
            // strand the caller, since every other way off the workout screen is refused for a
            // session that is no longer open.
            val sessionId = startWithSets()
            drafts.save(ActiveSession(sessionId = sessionId, startedAt = 5_000L))
            drafts.clearFailure = IllegalStateException("the draft file is not writable")

            val finished = finishSession().getOrThrow()

            assertEquals(sessionId, finished.summary.sessionId)
            assertEquals(finished.state, state.observe().first())
            assertEquals(false, sessions.loadSession(sessionId).getOrThrow().isInProgress)
        }

    @Test
    fun `a refused finish leaves the draft alone`() =
        runTest {
            startSession().getOrThrow()
            val draft = ActiveSession(sessionId = 1L, startedAt = 5_000L)
            drafts.save(draft)

            assertTrue(finishSession().isFailure)

            assertEquals(draft, drafts.load())
        }

    @Test
    fun `a finished session no longer resolves as active`() =
        runTest {
            startWithSets()

            finishSession().getOrThrow()

            assertNull(sessions.getActive().first())
        }

    @Test
    fun `a paused session can be finished`() =
        runTest {
            startWithSets()
            pauseSession().getOrThrow()

            val finished = finishSession().getOrThrow()

            assertEquals(1L, finished.summary.sessionId)
        }

    @Test
    fun `duration spans the paused stretch`() =
        runTest {
            startWithSets()
            clock.advance(60_000L)
            pauseSession().getOrThrow()
            clock.advance(600_000L)

            val finished = finishSession().getOrThrow()

            assertEquals(660_000L, finished.summary.durationMillis)
        }

    @Test
    fun `a session with no exercises at all is refused`() =
        runTest {
            startSession().getOrThrow()

            val result = finishSession()

            assertEquals(SessionError.NoSetsLogged, result.exceptionOrNull())
        }

    @Test
    fun `a session whose exercises have no sets is refused`() =
        runTest {
            val started = startSession().getOrThrow()
            sessions.addExercise(started.sessionId)

            val result = finishSession()

            assertEquals(SessionError.NoSetsLogged, result.exceptionOrNull())
        }

    @Test
    fun `a session holding only uncompleted placeholder sets is refused`() =
        runTest {
            val started = startSession().getOrThrow()
            val exerciseId = sessions.addExercise(started.sessionId)
            sessions.addSet(exerciseId, isCompleted = false)

            val result = finishSession()

            assertEquals(SessionError.NoSetsLogged, result.exceptionOrNull())
        }

    @Test
    fun `a refused finish leaves the session in progress and the state untouched`() =
        runTest {
            val started = startSession().getOrThrow()
            val updatesBefore = sessions.updateCount

            val result = finishSession()

            assertTrue(result.isFailure)
            assertEquals(true, sessions.stored(started.sessionId)?.isInProgress)
            assertEquals(updatesBefore, sessions.updateCount)
            assertEquals(started, state.current)
        }

    @Test
    fun `finishing an idle session is refused`() =
        runTest {
            val result = finishSession()

            assertEquals(SessionError.SessionNotActive(SessionState.Idle), result.exceptionOrNull())
        }

    @Test
    fun `finishing an already finished session is refused`() =
        runTest {
            startWithSets()
            val finished = finishSession().getOrThrow()

            val result = finishSession()

            assertEquals(SessionError.SessionNotActive(finished.state), result.exceptionOrNull())
        }

    @Test
    fun `finishing a session whose row has been deleted is refused`() =
        runTest {
            val sessionId = startWithSets()
            sessions.delete(sessionId)

            val result = finishSession()

            assertEquals(SessionError.SessionNotFound(sessionId = 1L), result.exceptionOrNull())
        }

    @Test
    fun `the no-sets refusal surfaces as a descriptive IllegalStateException`() =
        runTest {
            startSession().getOrThrow()

            val refused = finishSession()

            val thrown = assertThrows(IllegalStateException::class.java) { refused.getOrThrow() }
            assertEquals("cannot finish a session with no completed sets", thrown.message)
        }

    @Test
    fun `only completed sets are counted`() =
        runTest {
            val started = startSession().getOrThrow()
            val exerciseId = sessions.addExercise(started.sessionId)
            sessions.addSet(exerciseId, reps = 10, weight = 50_000L, isCompleted = true)
            sessions.addSet(exerciseId, reps = 10, weight = 50_000L, isCompleted = false)

            val summary = finishSession().getOrThrow().summary

            assertEquals(1, summary.totalSets)
            assertEquals(500_000L, summary.totalVolume)
        }

    @Test
    fun `volume and set count sum across exercises`() =
        runTest {
            val started = startSession().getOrThrow()
            val squat = sessions.addExercise(started.sessionId, exerciseId = 1L)
            val bench = sessions.addExercise(started.sessionId, exerciseId = 2L)
            repeat(3) { sessions.addSet(squat, reps = 5, weight = 100_000L) }
            repeat(2) { sessions.addSet(bench, reps = 8, weight = 60_000L) }

            val summary = finishSession().getOrThrow().summary

            assertEquals(2, summary.exerciseCount)
            assertEquals(5, summary.totalSets)
            assertEquals(3 * 5 * 100_000L + 2 * 8 * 60_000L, summary.totalVolume)
        }

    @Test
    fun `exercises with no completed sets are excluded from the exercise count`() =
        runTest {
            val started = startSession().getOrThrow()
            val squat = sessions.addExercise(started.sessionId, exerciseId = 1L)
            val skipped = sessions.addExercise(started.sessionId, exerciseId = 2L)
            sessions.addSet(squat)
            sessions.addSet(skipped, isCompleted = false)

            val summary = finishSession().getOrThrow().summary

            assertEquals(1, summary.exerciseCount)
        }

    @Test
    fun `sets without weight or reps count as zero volume rather than being dropped`() =
        runTest {
            val started = startSession().getOrThrow()
            val plank = sessions.addExercise(started.sessionId)
            sessions.addSet(plank, reps = null, weight = null)

            val summary = finishSession().getOrThrow().summary

            assertEquals(1, summary.totalSets)
            assertEquals(0L, summary.totalVolume)
        }

    @Test
    fun `summarising reads the session's sets once, not once per exercise`() =
        runTest {
            val started = startSession().getOrThrow()
            repeat(5) { sessions.addSet(sessions.addExercise(started.sessionId, exerciseId = it + 1L)) }

            finishSession().getOrThrow()

            assertEquals(1, sessions.setsForSessionReads)
            assertEquals(0, sessions.setsByExerciseReads)
        }

    @Test
    fun `a backwards clock adjustment cannot produce a negative duration`() =
        runTest {
            startWithSets()
            clock.now = 1_000L

            val summary = finishSession().getOrThrow().summary

            assertEquals(0L, summary.durationMillis)
        }

    @Test
    fun `finishing runs the cascade and hands back what it moved`() =
        runTest {
            val sessionId = startWithSets()

            val progression = requireNotNull(finishWithCascade().getOrThrow().progression)

            assertEquals(sessionId, progression.sessionId)
            assertEquals(4, progression.recordsBroken.size)
            assertEquals(StreakResult.Incremented(1), progression.streak?.result)
            assertTrue(progression.xpAwarded > 0)
            assertTrue(progression.isComplete)
        }

    @Test
    fun `the cascade runs against a session already closed out`() =
        runTest {
            startWithSets()

            val progression = requireNotNull(finishWithCascade().getOrThrow().progression)

            // `first_session` is a lifetime count of *finished* sessions, and `first_record`
            // a count of rows detection has written. Unlocking either is only possible
            // because the row was stamped and out of progress before the cascade read the
            // counts; a cascade that ran first would have seen zero of both.
            assertEquals(
                listOf(Achievements.FIRST_SESSION.id, Achievements.FIRST_RECORD.id),
                progression.achievementsUnlocked.map { it.achievement.id },
            )
        }

    @Test
    fun `a cascade that cannot write leaves the workout finished and saved`() =
        runTest {
            // The workout is the user's data and the XP is a consequence the app invented.
            // A refused progress write must not report "nothing was saved" over a saved
            // session — the same trade the draft clear above makes, for the same reason.
            val sessionId = startWithSets()
            gamification.addXpFailure = IllegalStateException("the progress row is locked")

            val finished = finishWithCascade().getOrThrow()

            assertEquals(sessionId, finished.summary.sessionId)
            assertEquals(false, sessions.stored(sessionId)?.isInProgress)
            assertEquals(1, finished.summary.totalSets)
            assertEquals(finished.state, state.current)
            assertEquals(
                listOf(SessionProgressionStep.XP, SessionProgressionStep.ACHIEVEMENTS),
                requireNotNull(finished.progression).failures.map { it.step },
            )
        }

    @Test
    fun `a finish with no cascade wired reports no progression at all`() =
        runTest {
            // Null rather than an empty progression: an empty one is what a cascade that ran
            // and moved nothing returns, and a recap that cannot tell those apart would draw
            // "no records, no XP, no streak" over a session nobody ever priced.
            startWithSets()

            val finished = finishSession().getOrThrow()

            assertNull(finished.progression)
        }
}
