package com.opengym.feature.progression.body

import com.opengym.core.domain.model.BodyMeasurement
import com.opengym.feature.progression.stats.FixedClock
import com.opengym.feature.progression.stats.ProgressionTimeframe
import com.opengym.feature.progression.stats.StatsTimeframe
import kotlinx.coroutines.CompletableDeferred
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
import java.time.LocalDate

/**
 * What the body screen makes of the store behind it.
 *
 * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background first:
 * without a collector the flow never starts and the state stays at its initial value, which
 * would make every assertion here pass for the wrong reason.
 *
 * The clock is pinned to a fixed Tuesday, which is what makes "the last ninety days" a window
 * the assertions can be written against.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BodyMeasurementsViewModelTest {

    private val store = FakeBodyMeasurementStore()
    private val timeframe = ProgressionTimeframe()
    private val clock = FixedClock(middayMillis(TODAY))
    private lateinit var viewModel: BodyMeasurementsViewModel

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() = Dispatchers.resetMain()

    // ─── The window ─────────────────────────────────────────────────────────────────────

    @Test
    fun `it opens on ninety days, shared with the hub`() =
        runTest {
            timeframe.select(StatsTimeframe.YEAR)
            val state = collect()

            // Arriving from a hub set to a year, not the enum's own default.
            assertEquals(StatsTimeframe.YEAR, state().timeframe)
        }

    @Test
    fun `the window holds the entries inside it, oldest first`() =
        runTest {
            seedAQuarter()
            val state = collect()

            assertEquals(
                listOf(TODAY.minusDays(60), TODAY.minusDays(30), TODAY).map(::middayMillis),
                state().window.map(BodyMeasurement::createdAt),
            )
            // And the history is the same rows the other way up, as the store hands them over.
            assertEquals(state().window.asReversed(), state().history)
        }

    @Test
    fun `narrowing the window drops the entries outside it and keeps the history whole`() =
        runTest {
            seedAQuarter()
            val state = collect()

            viewModel.onEvent(BodyMeasurementsEvent.TimeframeSelected(StatsTimeframe.WEEK))
            advanceUntilIdle()

            assertEquals(1, state().window.size)
            // The history is not a function of the window: it is every entry, always, because
            // the list underneath is a record rather than a reading of a period.
            assertEquals(3, state().history.size)
        }

    @Test
    fun `all time opens on the first measurement rather than on today`() =
        runTest {
            store.insert(measurementOn(TODAY.minusYears(3), weight = bodyKg(85)))
            store.insert(measurementOn(TODAY, weight = bodyKg(78)))
            val state = collect()

            viewModel.onEvent(BodyMeasurementsEvent.TimeframeSelected(StatsTimeframe.ALL))
            advanceUntilIdle()

            // A three-year-old weigh-in is outside every bounded window and inside this one,
            // which is the whole of what `ALL` is for.
            assertEquals(2, state().window.size)
        }

    // ─── The readings ───────────────────────────────────────────────────────────────────

    @Test
    fun `each figure is carried down from wherever it was last measured`() =
        runTest {
            store.insert(measurementOn(TODAY.minusDays(20), chest = bodyCm(102), waist = bodyCm(83)))
            store.insert(measurementOn(TODAY.minusDays(10), waist = bodyCm(82)))
            store.insert(measurementOn(TODAY, weight = bodyKg(78, 4)))
            val state = collect()

            val readings = state().readings
            // The newest entry holds a weight and nothing else. Reading the tiles off it would
            // blank a chest measurement the user took three weeks ago and has not been asked to
            // retake.
            assertEquals(bodyKg(78, 4), readings.weight)
            assertEquals(bodyCm(82), readings.waist)
            assertEquals(bodyCm(102), readings.chest)
            assertNull(readings.arm)
        }

    @Test
    fun `lean mass is the last one that could be computed, not two figures from two dates`() =
        runTest {
            store.insert(measurementOn(TODAY.minusDays(30), weight = bodyKg(80), bodyFat = 160))
            store.insert(measurementOn(TODAY, weight = bodyKg(78)))
            val state = collect()

            // Both inputs are on screen — this month's weight and March's body fat — and pairing
            // them would report a lean mass the user has never had. The figure is the last one a
            // single entry carried both halves of.
            assertEquals(bodyKg(80) * 840 / 1_000, state().readings.leanMass)
        }

    @Test
    fun `the weight series skips entries that measured no weight`() =
        runTest {
            store.insert(measurementOn(TODAY.minusDays(2), weight = bodyKg(79)))
            store.insert(measurementOn(TODAY.minusDays(1), waist = bodyCm(82)))
            store.insert(measurementOn(TODAY, weight = bodyKg(78)))
            val state = collect()

            // A tape session is not a day the user weighed nothing, and a line dropping to the
            // floor on it would be the chart inventing a crash diet.
            assertEquals(2, state().weights.size)
            assertEquals(bodyKg(-1), state().weightChange)
        }

    @Test
    fun `one weigh-in is no change at all rather than a change of zero`() =
        runTest {
            store.insert(measurementOn(TODAY, weight = bodyKg(78)))
            val state = collect()

            assertNull(state().weightChange)
        }

    @Test
    fun `a user who has logged nothing is empty, and one who is still loading is not`() =
        runTest {
            assertTrue(BodyMeasurementsUiState().isLoading)
            assertFalse(BodyMeasurementsUiState().isEmpty)

            val state = collect()

            assertFalse(state().isLoading)
            assertTrue(state().isEmpty)
        }

    // ─── The form ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a new entry is pre-filled with the last value of every figure, and no note`() =
        runTest {
            store.insert(measurementOn(TODAY.minusDays(7), chest = bodyCm(102), notes = "after a deload"))
            store.insert(measurementOn(TODAY.minusDays(1), weight = bodyKg(78, 4)))
            collect()

            viewModel.onEvent(BodyMeasurementsEvent.LogRequested)

            val form = requireNotNull(viewModel.uiState.value.form)
            assertEquals(NEW_ENTRY, form.id)
            assertEquals(bodyKg(78, 4), form.weight)
            assertEquals(bodyCm(102), form.chest)
            // A note annotates the measurement it was written about; carrying it forward would
            // turn one remark into a history of them.
            assertNull(form.notes)
            assertEquals(middayMillis(TODAY), form.createdAt)
        }

    @Test
    fun `editing opens the form on the entry itself`() =
        runTest {
            val entry = measurementOn(TODAY, id = 7, weight = bodyKg(78))
            store.insert(entry)
            collect()

            viewModel.onEvent(BodyMeasurementsEvent.EditRequested(entry))

            assertEquals(entry, viewModel.uiState.value.form)
        }

    @Test
    fun `saving a new entry inserts it and closes the form`() =
        runTest {
            collect()
            viewModel.onEvent(BodyMeasurementsEvent.LogRequested)

            val form = requireNotNull(viewModel.uiState.value.form)
            viewModel.onEvent(BodyMeasurementsEvent.Saved(form.copy(weight = bodyKg(78, 4))))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.form)
            assertEquals(1, viewModel.uiState.value.history.size)
            assertEquals(bodyKg(78, 4), viewModel.uiState.value.readings.weight)
        }

    @Test
    fun `a second tap while the write is in flight does not log the measurement twice`() =
        runTest {
            val state = collect()
            viewModel.onEvent(BodyMeasurementsEvent.LogRequested)
            val entry = requireNotNull(viewModel.uiState.value.form).copy(weight = bodyKg(78, 4))

            // The store still answering — the window a real one opens by suspending onto its IO
            // dispatcher, and the whole of the window the sheet is up for, since the form is
            // closed on success only.
            val write = CompletableDeferred<Unit>()
            store.gate = write

            viewModel.onEvent(BodyMeasurementsEvent.Saved(entry))
            assertTrue(state().isSaving)

            // The second half of a double tap, on a button the sheet is still drawing.
            viewModel.onEvent(BodyMeasurementsEvent.Saved(entry))

            write.complete(Unit)
            advanceUntilIdle()

            // One row, not two identical ones both dated now for the user to prune.
            assertEquals(1, store.writes.size)
            assertEquals(1, state().history.size)
            assertNull(state().form)
            assertFalse(state().isSaving)
        }

    @Test
    fun `saving a correction updates the row rather than adding one`() =
        runTest {
            val entry = measurementOn(TODAY, id = 1, weight = bodyKg(80))
            store.insert(entry)
            val state = collect()

            viewModel.onEvent(BodyMeasurementsEvent.Saved(entry.copy(weight = bodyKg(78, 4))))
            advanceUntilIdle()

            assertEquals(1, state().history.size)
            assertEquals(bodyKg(78, 4), state().readings.weight)
        }

    @Test
    fun `an entry recording nothing is refused, and the form stays open on it`() =
        runTest {
            collect()
            viewModel.onEvent(BodyMeasurementsEvent.LogRequested)
            val form = requireNotNull(viewModel.uiState.value.form)

            viewModel.onEvent(BodyMeasurementsEvent.Saved(form))
            advanceUntilIdle()

            // `BodyMeasurementValidator` refuses a dated row with no measurement in it. The form
            // will not offer to save one either — this is the last line rather than the one the
            // user meets — and dropping it silently must not also drop what they typed.
            assertTrue(store.writes.isEmpty())
            assertEquals(form, viewModel.uiState.value.form)
        }

    @Test
    fun `a figure past its bound is refused rather than stored`() =
        runTest {
            collect()
            viewModel.onEvent(BodyMeasurementsEvent.LogRequested)
            val form = requireNotNull(viewModel.uiState.value.form)

            // A stray digit on a waist typed in millimetres: past the five-metre ceiling the
            // validator holds circumferences to.
            viewModel.onEvent(BodyMeasurementsEvent.Saved(form.copy(waist = 82_000)))
            advanceUntilIdle()

            assertTrue(store.writes.isEmpty())
        }

    @Test
    fun `dismissing the form throws the draft away`() =
        runTest {
            collect()
            viewModel.onEvent(BodyMeasurementsEvent.LogRequested)

            viewModel.onEvent(BodyMeasurementsEvent.FormDismissed)

            assertNull(viewModel.uiState.value.form)
            assertTrue(store.writes.isEmpty())
        }

    @Test
    fun `deleting removes the entry from the history`() =
        runTest {
            store.insert(measurementOn(TODAY.minusDays(1), id = 1, weight = bodyKg(79)))
            store.insert(measurementOn(TODAY, id = 2, weight = bodyKg(78)))
            val state = collect()
            val doomed = state().history.first().id

            viewModel.onEvent(BodyMeasurementsEvent.Deleted(doomed))
            advanceUntilIdle()

            assertEquals(listOf(doomed), store.deletions)
            assertEquals(1, state().history.size)
        }

    // ─── Fixtures ───────────────────────────────────────────────────────────────────────

    private fun TestScope.collect(): () -> BodyMeasurementsUiState {
        viewModel = BodyMeasurementsViewModel(timeframe = timeframe, repository = store, clock = clock)
        // Unconfined, so the first assembled value has arrived by the time the test reads it:
        // `stateIn(WhileSubscribed)` never starts without a collector, and a queued one would
        // leave every assertion below passing against the initial state.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return { viewModel.uiState.value }
    }

    /** Three weigh-ins across a quarter: today, a month back, and two. */
    private suspend fun seedAQuarter() {
        store.insert(measurementOn(TODAY.minusDays(60), weight = bodyKg(80)))
        store.insert(measurementOn(TODAY.minusDays(30), weight = bodyKg(79)))
        store.insert(measurementOn(TODAY, weight = bodyKg(78)))
    }

    private companion object {
        /** A Tuesday, so a seven-day window crosses a Monday. */
        val TODAY: LocalDate = LocalDate.of(2026, 4, 28)
    }
}
