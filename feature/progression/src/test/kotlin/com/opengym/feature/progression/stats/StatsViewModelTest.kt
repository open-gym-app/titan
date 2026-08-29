package com.opengym.feature.progression.stats

import com.opengym.core.domain.model.RecordTally
import com.opengym.core.domain.stats.TrainingWeek
import com.opengym.core.domain.usecase.ConsistencyUseCase
import com.opengym.core.domain.usecase.VolumeTrendUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.time.LocalDate
import kotlin.time.Duration.Companion.hours

/**
 * What the stats hub makes of the stores behind it.
 *
 * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background
 * first: without a collector the flow never starts and the state stays at its initial value,
 * which would make every assertion here pass for the wrong reason.
 *
 * The clock is pinned to a fixed Tuesday, which is what makes "the last ninety days" a range
 * the assertions can be written against.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private val progression = FakeProgressionStore()
    private val records = FakeRecordStore()
    private val timeframe = ProgressionTimeframe()
    private val clock = FixedClock(middayOn(TODAY))

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    // ─── The window ─────────────────────────────────────────────────────────────────────

    @Test
    fun `it opens on ninety days`() =
        runTest {
            val state = collect()

            assertEquals(StatsTimeframe.QUARTER, state().timeframe)
        }

    @Test
    fun `every read is taken over the same snapped window`() =
        runTest {
            seedAQuarter()
            collect()

            val window = TrainingWeek.snap(StatsTimeframe.QUARTER.period(TODAY))
            val previous = TrainingWeek.preceding(window)

            // Five reads, one window. A muscle map over thirty days beside a chart over five weeks
            // would be two answers to one question, and the ranges are where that would show.
            //
            // The two *series* reads legitimately ask for more: `VolumeTrendUseCase` and
            // `ConsistencyUseCase` fetch the comparison period by widening the query rather than
            // by making a second one, so their range opens on the previous period's Monday. Every
            // range still closes on the window's own last day, and no read opens anywhere else.
            assertTrue(progression.requestedRanges.isNotEmpty())
            assertTrue(progression.requestedRanges.all { it.endInclusive == window.endInclusive })
            assertEquals(
                setOf(window.start, previous.start),
                progression.requestedRanges.map { it.start }.toSet(),
            )
            assertEquals(listOf(window), records.requestedRanges)
        }

    @Test
    fun `choosing a timeframe re-reads everything over the new window`() =
        runTest {
            seedAQuarter()
            val state = collect()

            state.viewModel.onEvent(StatsEvent.TimeframeSelected(StatsTimeframe.MONTH))

            assertEquals(StatsTimeframe.MONTH, state().timeframe)
            assertEquals(
                TrainingWeek.snap(StatsTimeframe.MONTH.period(TODAY)),
                progression.requestedRanges.last(),
            )
        }

    @Test
    fun `the choice is made on the shared holder, so the drill-down sees it too`() =
        runTest {
            seedAQuarter()
            val state = collect()

            state.viewModel.onEvent(StatsEvent.TimeframeSelected(StatsTimeframe.YEAR))

            // #102 reads this same object. A copy held in the ViewModel is the copy that goes stale.
            assertEquals(StatsTimeframe.YEAR, timeframe.selected.value)
        }

    @Test
    fun `all time opens on the day the user first trained`() =
        runTest {
            progression.setHasTrainingHistory(value = true, since = FIRST_TRAINING_DAY)
            progression.setTraining(Triple(TODAY, 3, 3))
            val state = collect()

            state.viewModel.onEvent(StatsEvent.TimeframeSelected(StatsTimeframe.ALL))

            assertEquals(
                TrainingWeek.snap(FIRST_TRAINING_DAY..TODAY),
                progression.requestedRanges.last(),
            )
        }

    // ─── The readings ───────────────────────────────────────────────────────────────────

    @Test
    fun `the hero carries the period's total, its weeks and its change`() =
        runTest {
            // Two weeks in the window at 10 and 20, against two before it at 5 apiece: 30 against
            // 10 is a doubling and a half.
            progression.setVolume(
                TODAY.minusWeeks(1) to 10_000L,
                TODAY to 20_000L,
                TODAY.minusWeeks(20) to 10_000L,
            )
            progression.setTraining(Triple(TODAY, 3, 3))
            val state = collect()

            val volume = requireNotNull(state().volume)
            assertEquals(30_000L, volume.total)
            assertEquals(30_000L, volume.weeks.sumOf { it.totalVolume })
            assertEquals(2.0, requireNotNull(volume.changeRatio), 1e-6)
        }

    @Test
    fun `an untrained week inside the window is a zero rather than a gap`() =
        runTest {
            progression.setVolume(TODAY.minusWeeks(2) to 10_000L, TODAY to 20_000L)
            progression.setTraining(Triple(TODAY, 3, 3))
            val state = collect()

            val weeks = requireNotNull(state().volume).weeks
            // Thirteen or fourteen buckets, contiguous, with the untrained ones present at zero —
            // a rest week drawn as a gap would join the weeks either side with a line claiming
            // training continued.
            assertEquals(TrainingWeek.countIn(TrainingWeek.snap(StatsTimeframe.QUARTER.period(TODAY))), weeks.size)
            assertTrue(weeks.any { it.totalVolume == 0L })
        }

    @Test
    fun `the grid carries the four figures the window produced`() =
        runTest {
            seedAQuarter()
            records.setTally(RecordTally(records = 7, exercises = 5))
            val state = collect()

            val summary = requireNotNull(state().summary)
            assertEquals(9, summary.sessions)
            assertEquals(RecordTally(records = 7, exercises = 5), summary.records)
            assertEquals(4.hours.inWholeMilliseconds, summary.trainingMillis)
            // Nine training days over the window's whole width, empty weeks included.
            assertEquals(9.0 / weeksInWindow(), summary.averagePerWeek, 1e-6)
        }

    @Test
    fun `the muscle map is passed through in the order the store ranked it`() =
        runTest {
            seedAQuarter()
            progression.setMuscles(
                trained(id = 1, name = "CHEST", sets = 18),
                trained(id = 2, name = "TRICEPS", sets = 9, isPrimary = false),
            )
            val state = collect()

            assertEquals(listOf("CHEST", "TRICEPS"), state().muscles.map { it.muscle.name })
        }

    // ─── The empty windows ──────────────────────────────────────────────────────────────

    @Test
    fun `a user who has never trained gets the first-run state and no cards`() =
        runTest {
            val state = collect()

            assertEquals(StatsEmptiness.FIRST_RUN, state().emptiness)
            assertNull(state().volume)
            assertNull(state().summary)
        }

    @Test
    fun `a lapsed user gets a quiet window rather than a prompt to start training`() =
        runTest {
            // Three years of history, none of it inside a ninety-day window: nothing in the window
            // itself can tell this apart from a first run.
            progression.setHasTrainingHistory(value = true, since = FIRST_TRAINING_DAY)
            val state = collect()

            assertEquals(StatsEmptiness.QUIET_PERIOD, state().emptiness)
        }

    @Test
    fun `a window of sessions that lifted nothing is not an empty window`() =
        runTest {
            // All cardio: sessions were finished and no volume was moved. Keying the empty state
            // off volume would tell somebody who trained nine times that they had not trained.
            progression.setTraining(Triple(TODAY, 3, 3), Triple(TODAY.minusWeeks(1), 3, 3))

            val state = collect()

            assertNull(state().emptiness)
            assertEquals(6, requireNotNull(state().summary).sessions)
        }

    @Test
    fun `nothing is drawn until every read has answered`() =
        runTest {
            assertTrue(StatsUiState().isLoading)

            seedAQuarter()
            val state = collect()

            assertFalse(state().isLoading)
        }

    // ─── Fixtures ───────────────────────────────────────────────────────────────────────

    private fun TestScope.collect(): StateHandle {
        val viewModel = StatsViewModel(
            timeframe = timeframe,
            volumeTrend = VolumeTrendUseCase(progression),
            consistency = ConsistencyUseCase(progression),
            progressionRepository = progression,
            personalRecordRepository = records,
            clock = clock,
        )
        // Unconfined, so the first assembled value has arrived by the time the test reads it:
        // `stateIn(WhileSubscribed)` never starts without a collector, and a queued one would
        // leave every assertion below passing against the initial state.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return StateHandle(viewModel)
    }

    /** A quarter with training in three of its weeks: nine sessions over four hours. */
    private fun seedAQuarter() {
        progression.setVolume(
            TODAY.minusWeeks(2) to 10_000L,
            TODAY.minusWeeks(1) to 15_000L,
            TODAY to 20_000L,
        )
        progression.setTraining(
            Triple(TODAY.minusWeeks(2), 3, 3),
            Triple(TODAY.minusWeeks(1), 3, 3),
            Triple(TODAY, 3, 3),
        )
        progression.setTrainingMillis(4.hours.inWholeMilliseconds)
    }

    /** How many whole weeks the default window covers — what the average divides by. */
    private fun weeksInWindow(): Double =
        TrainingWeek.countIn(TrainingWeek.snap(StatsTimeframe.QUARTER.period(TODAY))).toDouble()

    /** The ViewModel and the current state, so a test reads `state()` rather than re-collecting. */
    private class StateHandle(val viewModel: StatsViewModel) {
        operator fun invoke(): StatsUiState = viewModel.uiState.value
    }

    private companion object {
        /** A Tuesday, so a seven-day window crosses a Monday. */
        val TODAY: LocalDate = LocalDate.of(2026, 4, 28)

        /** Long before any window this screen asks for. */
        val FIRST_TRAINING_DAY: LocalDate = LocalDate.of(2023, 1, 9)
    }
}
