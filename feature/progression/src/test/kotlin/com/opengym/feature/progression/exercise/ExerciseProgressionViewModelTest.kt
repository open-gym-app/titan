package com.opengym.feature.progression.exercise

import androidx.lifecycle.SavedStateHandle
import com.opengym.core.domain.model.ExerciseAttempt
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.TrainedExercise
import com.opengym.core.domain.usecase.ExerciseProgressionUseCase
import com.opengym.feature.progression.navigation.NO_EXERCISE
import com.opengym.feature.progression.stats.FixedClock
import com.opengym.feature.progression.stats.ProgressionTimeframe
import com.opengym.feature.progression.stats.StatsTimeframe
import com.opengym.feature.progression.stats.middayOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * What the drill-down makes of the stores behind it.
 *
 * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background
 * first: without a collector the flow never starts and the state stays at its initial value,
 * which would make every assertion here pass for the wrong reason.
 *
 * Under Robolectric because the lift's id is read off the destination the way the app reads it
 * — `SavedStateHandle.toRoute` decodes through a real `Bundle`, which the plain JVM stubs
 * refuse to write a `Long` into. A constructor parameter would test a seam that does not exist.
 *
 * The clock is pinned to a fixed Tuesday, which is what makes "the last ninety days" a range
 * the assertions can be written against.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExerciseProgressionViewModelTest {

    private val progression = FakeExerciseProgressionStore()
    private val records = FakeExerciseRecordStore()
    private val catalogue = FakeExerciseCatalogue(listOf(BENCH, SQUAT))
    private val preferences = FakeSettings()
    private val timeframe = ProgressionTimeframe()
    private val clock = FixedClock(middayOn(TODAY))

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    // ─── Which lift, and how it is chosen ───────────────────────────────────────────────

    @Test
    fun `opened without a lift it draws the chooser rather than guessing at one`() =
        runTest {
            progression.setTrained(trained(BENCH_ID, "Bench Press"), trained(SQUAT_ID, "Back Squat"))
            val state = collect(NO_EXERCISE)

            assertFalse(state().isLoading)
            assertNull(state().exercise)
            assertNull(state().progression)
            assertEquals(listOf(BENCH_ID, SQUAT_ID), state().choices.map(TrainedExercise::exerciseId))
            // Nothing was read for a lift nobody named.
            assertTrue(progression.requestedExercises.isEmpty())
        }

    @Test
    fun `opened on a lift it reads that lift`() =
        runTest {
            seedBench()
            val state = collect(BENCH_ID)

            assertEquals(BENCH, state().exercise)
            assertEquals(listOf(BENCH_ID), progression.requestedExercises.distinct())
        }

    @Test
    fun `picking a lift out of the chooser swaps what is read, without navigating`() =
        runTest {
            seedBench()
            progression.setTrained(trained(BENCH_ID, "Bench Press"))
            val state = collect(NO_EXERCISE)

            state.viewModel.onEvent(ExerciseProgressionEvent.ExerciseSelected(BENCH_ID))

            assertEquals(BENCH, state().exercise)
            assertEquals(2, requireNotNull(state().progression).attempts.size)
        }

    @Test
    fun `the switcher puts the chooser back up and stops reading the lift`() =
        runTest {
            seedBench()
            progression.setTrained(trained(BENCH_ID, "Bench Press"))
            val state = collect(BENCH_ID)

            state.viewModel.onEvent(ExerciseProgressionEvent.ExerciseCleared)

            assertNull(state().exercise)
            assertNull(state().progression)
            // The chooser is still there to pick from — it is not a function of the selection.
            assertEquals(listOf(BENCH_ID), state().choices.map(TrainedExercise::exerciseId))
        }

    @Test
    fun `an id that no longer resolves draws the chooser rather than an empty lift`() =
        runTest {
            progression.setTrained(trained(BENCH_ID, "Bench Press"))
            val state = collect(exerciseId = 404L)

            assertFalse(state().isLoading)
            assertNull(state().exercise)
            assertNull(state().progression)
        }

    @Test
    fun `a lift being switched to does not wear the previous one's figures while it loads`() =
        runTest {
            seedBench()
            progression.setAttempts(SQUAT_ID, attemptOn(TODAY, sessionId = 9, volume = kg(4_000)))
            val state = collect(BENCH_ID)
            // A season of squats does not come back in the frame the tap happened in.
            progression.readLatencyMillis = SLOW_READ_MILLIS

            state.viewModel.onEvent(ExerciseProgressionEvent.ExerciseSelected(SQUAT_ID))

            // Relabelling one lift's records with another's name is worse than a blank frame.
            assertTrue(state().isLoading)
            assertNull(state().exercise)
            assertNull(state().progression)

            advanceUntilIdle()

            assertEquals(SQUAT, state().exercise)
            assertEquals(listOf(9L), requireNotNull(state().progression).attempts.map(ExerciseAttempt::sessionId))
        }

    // ─── The window ─────────────────────────────────────────────────────────────────────

    @Test
    fun `it opens on the window the hub is already showing`() =
        runTest {
            timeframe.select(StatsTimeframe.YEAR)
            seedBench()
            val state = collect(BENCH_ID)

            assertEquals(StatsTimeframe.YEAR, state().timeframe)
            assertEquals(StatsTimeframe.YEAR.period(TODAY), progression.requestedRanges.last())
        }

    @Test
    fun `the window is the days the label promises, not whole weeks`() =
        runTest {
            seedBench()
            collect(BENCH_ID)

            // An attempt is a session on a date rather than a weekly bucket, so there is no
            // partial bucket to round out — the ninety days asked for are the ninety read.
            assertEquals(StatsTimeframe.QUARTER.period(TODAY), progression.requestedRanges.last())
        }

    @Test
    fun `choosing a timeframe re-reads over the new window and tells the hub`() =
        runTest {
            seedBench()
            val state = collect(BENCH_ID)

            state.viewModel.onEvent(ExerciseProgressionEvent.TimeframeSelected(StatsTimeframe.MONTH))

            assertEquals(StatsTimeframe.MONTH, state().timeframe)
            assertEquals(StatsTimeframe.MONTH.period(TODAY), progression.requestedRanges.last())
            // The hub reads this same object; a copy here is the copy that goes stale.
            assertEquals(StatsTimeframe.MONTH, timeframe.selected.value)
        }

    @Test
    fun `all time opens on the day the user first trained`() =
        runTest {
            seedBench()
            progression.setFirstTrainingDay(FIRST_TRAINING_DAY)
            val state = collect(BENCH_ID)

            state.viewModel.onEvent(ExerciseProgressionEvent.TimeframeSelected(StatsTimeframe.ALL))

            assertEquals(FIRST_TRAINING_DAY..TODAY, progression.requestedRanges.last())
        }

    @Test
    fun `the tapped timeframe is the selected one before the reads have answered`() =
        runTest {
            seedBench()
            val state = collect(BENCH_ID)
            progression.readLatencyMillis = SLOW_READ_MILLIS

            state.viewModel.onEvent(ExerciseProgressionEvent.TimeframeSelected(StatsTimeframe.YEAR))

            // The segment lights on the tap, and the figures under it are not the previous
            // window's relabelled — a wrong answer where the loading state is a slow one.
            assertEquals(StatsTimeframe.YEAR, state().timeframe)
            assertTrue(state().isLoading)
            assertNull(state().progression)

            advanceUntilIdle()

            assertEquals(StatsTimeframe.YEAR, state().timeframe)
            assertFalse(state().isLoading)
        }

    // ─── The readings ───────────────────────────────────────────────────────────────────

    @Test
    fun `the window's attempts come back oldest first and the table reads them the other way`() =
        runTest {
            seedBench()
            val state = collect(BENCH_ID)

            val progression = requireNotNull(state().progression)
            assertEquals(listOf(1L, 2L), progression.attempts.map(ExerciseAttempt::sessionId))
            assertEquals(listOf(2L, 1L), state().recentAttempts.map(ExerciseAttempt::sessionId))
        }

    @Test
    fun `the records and the set count are all-time, not the window's`() =
        runTest {
            // A month ago, so the seven-day window below holds none of it.
            progression.setAttempts(
                BENCH_ID,
                attemptOn(TODAY.minusWeeks(4), sessionId = 1),
                attemptOn(TODAY.minusWeeks(3), sessionId = 2),
            )
            progression.setSetCount(BENCH_ID, 142)
            records.setRecords(
                BENCH_ID,
                // Achieved long before any window this screen asks for.
                recordOf(PrType.MAX_WEIGHT, achievedAt = middayOn(FIRST_TRAINING_DAY), weight = kg(85)),
            )
            val state = collect(BENCH_ID)

            state.viewModel.onEvent(ExerciseProgressionEvent.TimeframeSelected(StatsTimeframe.WEEK))

            val progression = requireNotNull(state().progression)
            // The window holds nothing, and the lift's best is still its best.
            assertTrue(progression.isEmpty)
            assertEquals(142, progression.totalSets)
            assertEquals(kg(85), progression.records.getValue(PrType.MAX_WEIGHT).weight)
            assertEquals(ExerciseEmptiness.QUIET_PERIOD, state().emptiness)
        }

    @Test
    fun `a lift never logged is a different empty state from a quiet window`() =
        runTest {
            val state = collect(BENCH_ID)

            assertEquals(ExerciseEmptiness.NEVER_TRAINED, state().emptiness)
        }

    @Test
    fun `one session in the window is not a trend`() =
        runTest {
            progression.setAttempts(BENCH_ID, attemptOn(TODAY, sessionId = 1))
            progression.setSetCount(BENCH_ID, 4)
            val state = collect(BENCH_ID)

            assertEquals(ExerciseEmptiness.SINGLE_SESSION, state().emptiness)
            assertFalse(requireNotNull(state().progression).hasTrend)
        }

    @Test
    fun `two sessions are a trend, with an estimate for each`() =
        runTest {
            seedBench()
            val state = collect(BENCH_ID)

            assertNull(state().emptiness)
            assertEquals(2, requireNotNull(state().progression).estimates.size)
        }

    // ─── Fixtures ───────────────────────────────────────────────────────────────────────

    /** Two bench sessions a fortnight apart, both inside the default window. */
    private fun seedBench() {
        progression.setAttempts(
            BENCH_ID,
            attemptOn(TODAY.minusWeeks(2), sessionId = 1, weight = kg(80), reps = 5),
            attemptOn(TODAY, sessionId = 2, weight = kg(85), reps = 5),
        )
        progression.setSetCount(BENCH_ID, 8)
    }

    private fun trained(
        exerciseId: Long,
        name: String,
    ) = TrainedExercise(
        exerciseId = exerciseId,
        name = name,
        sets = 42,
        lastPerformedAt = middayOn(TODAY),
    )

    /**
     * The ViewModel with its state already flowing.
     *
     * The id arrives the way it does in the app — decoded off the destination's arguments in
     * the `SavedStateHandle` — rather than as a constructor parameter, so a rename of the
     * route's field is a failure here rather than a blank screen at runtime.
     */
    private fun TestScope.collect(exerciseId: Long): StateHandle {
        val viewModel = ExerciseProgressionViewModel(
            savedStateHandle = SavedStateHandle(mapOf("exerciseId" to exerciseId)),
            timeframe = timeframe,
            progression = ExerciseProgressionUseCase(
                progressionRepository = progression,
                personalRecordRepository = records,
                userPreferencesRepository = preferences,
            ),
            progressionRepository = progression,
            exerciseRepository = catalogue,
            clock = clock,
        )
        // Unconfined, so the first assembled value has arrived by the time the test reads it.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return StateHandle(viewModel)
    }

    /** The ViewModel and the current state, so a test reads `state()` rather than re-collecting. */
    private class StateHandle(val viewModel: ExerciseProgressionViewModel) {
        operator fun invoke(): ExerciseProgressionUiState = viewModel.uiState.value
    }

    private companion object {
        /** Long enough that a test can look at the screen while the reads are still in flight. */
        const val SLOW_READ_MILLIS = 500L

        /** A Tuesday, so a seven-day window crosses a Monday. */
        val TODAY: LocalDate = LocalDate.of(2026, 4, 28)

        /** Long before any window this screen asks for. */
        val FIRST_TRAINING_DAY: LocalDate = LocalDate.of(2023, 1, 9)
    }
}
