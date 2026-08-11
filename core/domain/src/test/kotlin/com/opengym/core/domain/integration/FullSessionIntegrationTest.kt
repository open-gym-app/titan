package com.opengym.core.domain.integration

import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.stats.MuscleVolumeCalculator
import com.opengym.core.domain.stats.SessionStatsCalculator
import com.opengym.core.domain.stats.VolumeCalculator
import com.opengym.core.domain.timer.RestTimerState
import com.opengym.core.domain.timer.record
import com.opengym.core.domain.timer.restTimer
import com.opengym.core.domain.usecase.FakeSessionRepository
import com.opengym.core.domain.usecase.FakeSessionStateRepository
import com.opengym.core.domain.usecase.FinishSessionUseCase
import com.opengym.core.domain.usecase.MutableClock
import com.opengym.core.domain.usecase.PauseSessionUseCase
import com.opengym.core.domain.usecase.ResumeSessionUseCase
import com.opengym.core.domain.usecase.SessionLifecycleLock
import com.opengym.core.domain.usecase.StartSessionUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One workout, start to finish, through the real use cases.
 *
 * The per-use-case tests each pin one transition and its refusals in isolation. This
 * pins what none of them can: that the pieces compose. A session is started, its sets
 * go through [com.opengym.core.domain.validation.SetValidator] on the way in, the rest
 * timer runs between them, the session is paused and resumed mid-workout, and the
 * summary that comes out the far end is the one
 * [SessionStatsCalculator] computes from the same sets — which is the invariant that
 * would break silently if either side of it were changed alone.
 *
 * Nothing inside `:core:domain` is mocked. The fakes stand in only for what this module
 * declares and does not implement: the repositories, the clock, and the scheduler.
 *
 * ### What is not here
 *
 * Hitting a personal record. `:core:domain` has no PR engine — that is M5-01 (#30), and
 * the `personal_record` table it will write to is so far reached only from `:core:data`.
 * The nearest thing this layer can express is the *observation* such an engine makes,
 * which the top-set test below asserts against the domain's own [VolumeCalculator].
 * When the engine lands, that test is the seam to replace with a call into it.
 */
class FullSessionIntegrationTest {

    private val clock = MutableClock(now = 1_700_000_000_000L)
    private val sessions = FakeSessionRepository()
    private val state = FakeSessionStateRepository()
    private val catalogue = Catalogue.repository()

    // A lock of this test's own rather than the shared default: these tests run in the
    // same JVM as every other suite, and a process-wide mutex is not something a test
    // should be sharing with one.
    private val lock = SessionLifecycleLock()
    private val startSession = StartSessionUseCase(sessions, state, clock, lock)
    private val pauseSession = PauseSessionUseCase(sessions, state, clock, lock)
    private val resumeSession = ResumeSessionUseCase(sessions, state, clock, lock)
    private val finishSession = FinishSessionUseCase(sessions, state, clock, lock)

    /** The bench sets this workout logs: 8 and 8 at 80 kg, then a top single of 6 at 100 kg. */
    private val benchSets = listOf(8 to kg(80), 8 to kg(80), 6 to kg(100))

    /** The plank holds: carrying no load, and so no volume, but still sets that happened. */
    private val plankHolds = listOf(60L, 45L)

    /** How far into the session the pause falls: every bench set, and the rest after it. */
    private val pausedAfterMillis: Long
        get() = ((WORK_SET + REST) * benchSets.size).inWholeMilliseconds

    /**
     * Runs the workout: three bench sets with a rest after each, then two planks, with a
     * pause and resume in the middle. Returns the sets as the store holds them.
     */
    private suspend fun TestScope.runWorkout(): List<WorkoutSet> {
        val timeline = Timeline(this, clock)
        val timer = restTimer()
        val restStates = record(timer)

        val started = startSession().getOrThrow()
        val bench = sessions.addExercise(started.sessionId, Catalogue.benchPress.id)
        val benchType = catalogue.exercise(Catalogue.benchPress.id).type

        val logged = mutableListOf<WorkoutSet>()
        benchSets.forEachIndexed { index, (reps, weight) ->
            timeline.advance(WORK_SET)
            logged += sessions.logValidated(
                bench,
                completedSet(completedAt = clock.now, reps = reps, weight = weight, restAfter = REST.inWholeSeconds),
                benchType,
            )

            // The rest a set earns, counted down by the controller rather than assumed:
            // the session clock and the timer advance together, so the session ends up
            // as long as the work plus the rests actually took.
            timer.start(REST)
            assertEquals(RestTimerState.Running(REST, REST), timer.state.value)
            timeline.advance(REST)
            assertEquals("rest ${index + 1} should have run out", RestTimerState.Finished, timer.state.value)
        }

        // The user racks the bar and takes a real break before the next exercise.
        pauseSession().getOrThrow()
        timeline.advance(BREAK)
        resumeSession().getOrThrow()

        val plank = sessions.addExercise(started.sessionId, Catalogue.plank.id)
        val plankType = catalogue.exercise(Catalogue.plank.id).type
        plankHolds.forEach { seconds ->
            timeline.advance(seconds.seconds)
            logged += sessions.logValidated(
                plank,
                completedSet(completedAt = clock.now, duration = seconds),
                plankType,
            )
        }

        assertEquals(
            "the timer should have finished once per bench set and never been cancelled",
            benchSets.size,
            restStates.states.count { it == RestTimerState.Finished },
        )
        assertEquals(logged, sessions.getSetsForSession(started.sessionId).first())
        return logged
    }

    @Test
    fun `a full workout produces the summary the stats calculator computes from its sets`() = runTest {
        val logged = runWorkout()
        val startedAt = (state.current as SessionState.Active).startedAt

        val summary = finishSession().getOrThrow().summary

        val stats = SessionStatsCalculator.calculate(logged, startedAt, summary.finishedAt)
        assertEquals(stats.totalSets, summary.totalSets)
        assertEquals(stats.totalVolume, summary.totalVolume)
        assertEquals(stats.exerciseBreakdown.size, summary.exerciseCount)
        assertEquals(stats.durationSeconds, summary.durationMillis / 1_000L)
    }

    @Test
    fun `the summary counts every logged set across both exercises`() = runTest {
        runWorkout()

        val summary = finishSession().getOrThrow().summary

        assertEquals(benchSets.size + plankHolds.size, summary.totalSets)
        assertEquals(2, summary.exerciseCount)
        // Only the bench sets carry load; the planks are real sets with no volume.
        assertEquals(benchSets.sumOf { (reps, weight) -> weight * reps }, summary.totalVolume)
    }

    @Test
    fun `the session lasts as long as the work, the rests and the break between them`() = runTest {
        val timeline = Timeline(this, clock)
        val started = startSession().getOrThrow()
        sessions.addSet(sessions.addExercise(started.sessionId, Catalogue.benchPress.id))

        timeline.advance(WORK_SET + REST + BREAK)
        val summary = finishSession().getOrThrow().summary

        // Paused stretches count in, which is SessionSummary's documented choice: the
        // figure is how long the workout took, not how long it was moving.
        assertEquals(timeline.elapsedMillis, summary.durationMillis)
    }

    @Test
    fun `the state machine walks idle active paused active finished in order`() = runTest {
        runWorkout()
        val started = state.current as SessionState.Active

        val finished = finishSession().getOrThrow()

        // Resuming restores the original start time rather than restarting the clock, so
        // the two Active values are the same value — which is the point of asserting on
        // the whole sequence instead of on each transition's type.
        assertEquals(
            listOf(
                SessionState.Idle,
                started,
                SessionState.Paused(started.sessionId, pausedAt = started.startedAt + pausedAfterMillis),
                started,
                finished,
            ),
            state.recorded,
        )
    }

    @Test
    fun `finishing closes the row out and frees the next session to start`() = runTest {
        runWorkout()
        val sessionId = (state.current as SessionState.Active).sessionId

        finishSession().getOrThrow()

        val closed = sessions.stored(sessionId)
        assertEquals(false, closed?.isInProgress)
        assertEquals(clock.now, closed?.completedAt)
        assertTrue("a finished session must not block the next one", startSession().isSuccess)
    }

    @Test
    fun `the session's volume attributes to the muscles its exercises trained`() = runTest {
        val logged = runWorkout()
        val summary = finishSession().getOrThrow().summary
        val exercises = sessions.getExercises(summary.sessionId).first()
        val links = exercises.associate { it.exerciseId to catalogue.getMuscleGroupLinks(it.exerciseId).first() }

        val byMuscle = MuscleVolumeCalculator.volumeByMuscle(logged, exercises, links)

        // The primary mover carries the whole of the volume the summary reports, so the
        // two figures reconcile — the attribution is a split of this session, not a
        // separate count of it. Triceps take their documented half on top, which is why
        // the map sums to more than the session's volume.
        assertEquals(summary.totalVolume, byMuscle.getValue(Catalogue.chest))

        // The secondary figure is halved per *set* and then summed, while this expectation
        // sums and then halves. The two agree only because every set volume in these
        // fixtures is even, which is a fact about the fixtures and not about the
        // calculator — MuscleVolumeCalculator truncates towards zero on purpose, matching
        // SQLite's integer division. Pinned here so an odd-volume fixture fails saying
        // that, rather than failing as an off-by-one in the attribution.
        assertTrue(
            "these fixtures must halve exactly for the reconciliation below to hold",
            logged.all { VolumeCalculator.setVolume(it) % 2L == 0L },
        )
        assertEquals(summary.totalVolume / 2, byMuscle.getValue(Catalogue.triceps))
        // The planks carry no load, so abdominals are trained but earn no volume.
        assertEquals(0L, byMuscle.getValue(Catalogue.abdominals))
        assertEquals(
            listOf(Catalogue.chest, Catalogue.triceps, Catalogue.abdominals),
            byMuscle.keys.toList(),
        )
    }

    @Test
    fun `the session's top set beats everything logged before it`() = runTest {
        // The observation a PR engine makes, expressed with the domain primitives that
        // exist today. See the class doc: the engine itself is M5-01.
        //
        // Two of PrType's questions, and deliberately both, because they do not have the
        // same answer: the heaviest set here is the 6 at 100 kg, while the highest-volume
        // one is an 8 at 80 kg. An engine that conflated them would report the wrong set.
        val history = listOf(
            completedSet(completedAt = 1L, reps = 5, weight = kg(90)),
            completedSet(completedAt = 2L, reps = 8, weight = kg(70)),
        )
        val logged = runWorkout()

        val heaviest = logged.mapNotNull(WorkoutSet::weight).max()
        val bestVolume = logged.maxOf(VolumeCalculator::setVolume)

        val previousHeaviest = history.mapNotNull(WorkoutSet::weight).max()
        val previousBestVolume = history.maxOf(VolumeCalculator::setVolume)
        assertEquals(kg(100), heaviest)
        assertEquals(kg(80) * 8, bestVolume)
        assertTrue("$heaviest should beat $previousHeaviest", heaviest > previousHeaviest)
        assertTrue("$bestVolume should beat $previousBestVolume", bestVolume > previousBestVolume)
    }

    @Test
    fun `a rest still running when the session is finished is the caller's to dismiss`() = runTest {
        // The two state machines are independent by design: finishing a session does not
        // reach into the timer, so a caller that wants the countdown gone cancels it.
        val timeline = Timeline(this, clock)
        val timer = restTimer()
        val started = startSession().getOrThrow()
        sessions.addSet(sessions.addExercise(started.sessionId, Catalogue.benchPress.id))

        timer.start(REST)
        timeline.advance(REST / 2)
        finishSession().getOrThrow()

        assertEquals(RestTimerState.Running(REST / 2, REST), timer.state.value)
        timer.cancel()
        assertEquals(RestTimerState.Idle, timer.state.value)
    }

    private companion object {
        /** How long a working set takes to perform. */
        val WORK_SET = 45.seconds

        /** The rest between two sets, the app's default before the user changes it. */
        val REST = UserPreferences.DEFAULT_REST_DURATION

        /** The break between the two exercises, taken with the session paused. */
        val BREAK = 5.minutes
    }
}
