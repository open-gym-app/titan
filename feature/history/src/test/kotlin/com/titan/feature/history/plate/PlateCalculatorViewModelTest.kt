package com.titan.feature.history.plate

import androidx.lifecycle.SavedStateHandle
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.plate.PlateSolution
import com.titan.core.domain.plate.PlateStock
import com.titan.feature.history.afterProcessDeath
import com.titan.feature.history.navigation.NO_SUGGESTED_TARGET
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * The plate calculator's ViewModel against an in-memory store.
 *
 * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background first:
 * without a collector the flow is not started and the state stays at its initial value, which
 * would make every assertion below pass for the wrong reason.
 *
 * Under Robolectric, like the exercise detail's ViewModel test, because this one reads its
 * opening weight off the destination — and `SavedStateHandle.toRoute` decodes through a real
 * `Bundle`, which the plain JVM stubs refuse to write a `Long` into.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlateCalculatorViewModelTest {

    private val preferences = FakeUserPreferencesRepository()

    @Before
    fun setUpMainDispatcher() {
        // `viewModelScope` dispatches on Main; unconfined so a launched write has run by the
        // time the call that started it returns.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    // ─── The opening weight ─────────────────────────────────────────────────────────────

    @Test
    fun `a calculator opened cold starts on a plate a side`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            // 20 kg bar, 20 kg the heaviest plate on the default rack.
            assertEquals(60_000_000L, viewModel.uiState.value.target)
        }

    @Test
    fun `the opening weight follows the equipment, not a constant`() =
        runTest {
            val viewModel = viewModel(
                preferences = FakeUserPreferencesRepository(
                    UserPreferences(
                        barWeight = 15_000_000,
                        plateInventory = PlateInventory.of(
                            listOf(PlateStock(weight = 10_000_000, pairs = 2)),
                        ),
                    ),
                ),
            )
            collectState(viewModel)

            assertEquals(35_000_000L, viewModel.uiState.value.target)
        }

    @Test
    fun `an imperial user opens on a hundred and thirty five pounds`() =
        runTest {
            val viewModel = viewModel(
                preferences = FakeUserPreferencesRepository(
                    UserPreferences(unitSystem = UnitSystem.IMPERIAL, barWeight = 20_411_657),
                ),
            )
            collectState(viewModel)

            // A 45 lb bar and a 45 lb plate a side. Asserted as a solved bar rather than as a
            // milligram figure, because "135 lb" is the claim and the milligrams are two roundings.
            val state = viewModel.uiState.value
            assertTrue(state.solution is PlateSolution.Loaded)
            assertTrue((state.solution as PlateSolution.Loaded).isExact)
            assertEquals(1, state.solution.perSide.single().count)
        }

    @Test
    fun `a caller that names a weight gets that weight`() =
        runTest {
            val viewModel = viewModel(target = 142_500_000)
            collectState(viewModel)

            assertEquals(142_500_000L, viewModel.uiState.value.target)
        }

    // ─── Stepping ───────────────────────────────────────────────────────────────────────

    @Test
    fun `stepping moves the target from wherever it is`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(PlateCalculatorEvent.TargetStepped(10_000_000))
            assertEquals(70_000_000L, viewModel.uiState.value.target)

            viewModel.onEvent(PlateCalculatorEvent.TargetStepped(-2_500_000))
            assertEquals(67_500_000L, viewModel.uiState.value.target)
        }

    @Test
    fun `the target never goes negative`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(PlateCalculatorEvent.TargetStepped(-1_000_000_000))

            assertEquals(0L, viewModel.uiState.value.target)
        }

    @Test
    fun `a typed target replaces the stepped one and closes the field`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)
            viewModel.onEvent(PlateCalculatorEvent.SheetRequested(PlateCalculatorSheet.TARGET))

            viewModel.onEvent(PlateCalculatorEvent.TargetEntered(102_500_000))

            assertEquals(102_500_000L, viewModel.uiState.value.target)
            assertNull(viewModel.uiState.value.sheet)
        }

    @Test
    fun `changing the bar moves the opening weight, but not one the user chose`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            // Untouched: the target is still derived, so it follows the equipment.
            viewModel.onEvent(PlateCalculatorEvent.BarWeightSelected(15_000_000))
            assertEquals(55_000_000L, viewModel.uiState.value.target)

            // Touched: it is the user's number now, and the bar does not move it. The second bar is
            // one whose derived weight (50 kg) differs from the stepped target, so the assertion
            // cannot pass by the two happening to agree.
            viewModel.onEvent(PlateCalculatorEvent.TargetStepped(5_000_000))
            viewModel.onEvent(PlateCalculatorEvent.BarWeightSelected(10_000_000))
            assertEquals(60_000_000L, viewModel.uiState.value.target)
        }

    // ─── The equipment ──────────────────────────────────────────────────────────────────

    @Test
    fun `the solution follows the rack`() =
        runTest {
            val viewModel = viewModel(target = 100_000_000)
            collectState(viewModel)
            assertTrue((viewModel.uiState.value.solution as PlateSolution.Loaded).isExact)

            // Every 20 taken off the rack. 40 a side is now four 10s.
            viewModel.onEvent(PlateCalculatorEvent.PlatePairsSelected(weight = 20_000_000, pairs = 0))

            val loaded = viewModel.uiState.value.solution as PlateSolution.Loaded
            assertTrue(loaded.isExact)
            assertEquals(listOf(10_000_000L to 4), loaded.perSide.map { it.weight to it.count })
        }

    @Test
    fun `a size switched off keeps its row in the editor`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(PlateCalculatorEvent.PlatePairsSelected(weight = 20_000_000, pairs = 0))

            val rack = viewModel.uiState.value.preferences.plates
            assertEquals(PlateInventory.METRIC.stock.size, rack.stock.size)
            assertEquals(0, rack.stock.first().pairs)
        }

    @Test
    fun `resetting forgets the stored rack rather than storing today's default`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)
            viewModel.onEvent(PlateCalculatorEvent.PlatePairsSelected(weight = 20_000_000, pairs = 0))

            viewModel.onEvent(PlateCalculatorEvent.InventoryReset)

            // Null, not a copy of the metric rack: that is what makes the rack follow a change of
            // unit system again. See `UserPreferences.plateInventory`.
            assertNull(viewModel.uiState.value.preferences.plateInventory)
            assertEquals(PlateInventory.METRIC, viewModel.uiState.value.preferences.plates)
        }

    @Test
    fun `two sizes edited in a row both stick`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)

            viewModel.onEvent(PlateCalculatorEvent.PlatePairsSelected(weight = 20_000_000, pairs = 0))
            viewModel.onEvent(PlateCalculatorEvent.PlatePairsSelected(weight = 10_000_000, pairs = 3))

            // The second edit composes with the first rather than replacing the rack it was working
            // from. A ViewModel that rebuilt the whole inventory out of its own state would put the
            // 20s back here, because its state has not been told about the first edit yet — which is
            // what setting up a rack looks like, two rows in as many seconds.
            val rack = viewModel.uiState.value.preferences.plates
            assertEquals(0, rack.stock.single { it.weight == 20_000_000L }.pairs)
            assertEquals(3, rack.stock.single { it.weight == 10_000_000L }.pairs)
        }

    // ─── When the store will not take it ────────────────────────────────────────────────

    @Test
    fun `a write that fails is reported and changes nothing`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)
            preferences.writeFailure = IOException("no space")

            viewModel.onEvent(PlateCalculatorEvent.BarWeightSelected(15_000_000))

            assertEquals(PlateCalculatorMessage.SAVE_FAILED, viewModel.uiState.value.message)
            // The control has already snapped back, because nothing was mirrored in the first place.
            assertEquals(
                UserPreferences.DEFAULT_BAR_WEIGHT,
                viewModel.uiState.value.preferences.barWeight,
            )
        }

    @Test
    fun `closing the equipment sheet takes its failure with it`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)
            viewModel.onEvent(PlateCalculatorEvent.SheetRequested(PlateCalculatorSheet.EQUIPMENT))
            preferences.writeFailure = IOException("no space")
            viewModel.onEvent(PlateCalculatorEvent.InventoryReset)
            assertEquals(PlateCalculatorMessage.SAVE_FAILED, viewModel.uiState.value.message)

            viewModel.onEvent(PlateCalculatorEvent.SheetDismissed)

            // The sheet is where the failure was shown, so closing it is the user having read it.
            // Left set, it would come back as a snackbar over the screen behind.
            assertNull(viewModel.uiState.value.message)
        }

    @Test
    fun `a message is shown once`() =
        runTest {
            val viewModel = viewModel()
            collectState(viewModel)
            preferences.writeFailure = IOException("no space")
            viewModel.onEvent(PlateCalculatorEvent.InventoryReset)

            viewModel.onEvent(PlateCalculatorEvent.MessageShown)

            assertNull(viewModel.uiState.value.message)
        }

    // ─── Support ────────────────────────────────────────────────────────────────────────

    /**
     * The ViewModel with its route argument in place.
     *
     * The target arrives the way it does in the app — decoded off the destination's arguments in
     * the `SavedStateHandle` — rather than as a constructor parameter, so a rename of the
     * route's field fails here rather than silently dropping the caller's weight at runtime.
     * That decode goes through a real `Bundle`, which is why this class runs under Robolectric.
     */
    private fun viewModel(
        target: Long = NO_SUGGESTED_TARGET,
        preferences: FakeUserPreferencesRepository = this.preferences,
        savedState: SavedStateHandle = SavedStateHandle(mapOf("target" to target)),
    ) = PlateCalculatorViewModel(
        savedStateHandle = savedState,
        preferencesRepository = preferences,
    )

    /** Starts the state flow and leaves it collected for the rest of the test. */
    private fun TestScope.collectState(viewModel: PlateCalculatorViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
    }

    // ─── Process death (#183) ───────────────────────────────────────────────────────────

    @Test
    fun `the target the user stepped to comes back after process death`() =
        runTest {
            val handle = SavedStateHandle(mapOf("target" to NO_SUGGESTED_TARGET))
            val before = viewModel(savedState = handle)
            collectState(before)
            before.onEvent(PlateCalculatorEvent.TargetEntered(102_500_000))

            val restored = viewModel(savedState = handle.afterProcessDeath())
            collectState(restored)

            assertEquals(102_500_000, restored.uiState.value.target)
        }

    @Test
    fun `an open equipment sheet comes back after process death`() =
        runTest {
            val handle = SavedStateHandle(mapOf("target" to NO_SUGGESTED_TARGET))
            val before = viewModel(savedState = handle)
            collectState(before)
            before.onEvent(PlateCalculatorEvent.SheetRequested(PlateCalculatorSheet.EQUIPMENT))

            val restored = viewModel(savedState = handle.afterProcessDeath())
            collectState(restored)

            assertEquals(PlateCalculatorSheet.EQUIPMENT, restored.uiState.value.sheet)
        }
}
