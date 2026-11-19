package com.titan.feature.titan.tab

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.titan.core.domain.gamification.PetNicknameResult
import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.model.Pet
import com.titan.core.domain.model.PetStats
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.usecase.ChooseActivityUseCase
import com.titan.core.domain.usecase.FeedUseCase
import com.titan.core.domain.usecase.HatchEggUseCase
import com.titan.core.domain.usecase.WearGearUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class TitanTabViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val pets = FakePets()
    private val titans = FakeTitans(NOW)
    private val pantry = FakePantry()
    private val unlocks = FakeUnlocks()
    private val loadout = FakeLoadout()
    private val eggs = FakeEggs()
    private val saved = SavedStateHandle()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(handle: SavedStateHandle = saved) =
        TitanTabViewModel(
            petRepository = pets,
            titanStateRepository = titans,
            pantryRepository = pantry,
            unlockRepository = unlocks,
            loadoutRepository = loadout,
            collectedItemRepository = eggs,
            chooseActivity = ChooseActivityUseCase(titans),
            feed = FeedUseCase(pantry, titans),
            hatchEgg = HatchEggUseCase(eggs, pets, random = { 0 }, clock = { NOW }),
            wearGear = WearGearUseCase(pets, unlocks, loadout),
            savedStateHandle = handle,
        ).also {
            // No timed refresh: the store's writes drive every emission these tests wait for, and
            // a loop on the test clock would never let it go idle.
            it.refresh = Duration.INFINITE
        }

    @Test
    fun `with no titan the tab shows the eggs waiting, and hatching one shows the titan`() =
        runTest(dispatcher) {
            eggs.eggs.value = listOf(CollectedItem(7, "pet_egg", "session_finished", Instant.ofEpochMilli(NOW)))
            val vm = viewModel()

            vm.uiState.test {
                assertEquals(1, awaitUntil<TitanTabUiState.NoTitan>().eggs.size)

                vm.onEvent(TitanTabEvent.Hatch(7))
                val titan = awaitUntil<TitanTabUiState.Titan>()
                assertEquals("iron_cub", titan.speciesId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `with no titan and no egg the tab says what a titan is, with no vitals`() =
        runTest(dispatcher) {
            viewModel().uiState.test {
                assertEquals(TitanTabUiState.NoTitan(eggs = emptyList()), awaitUntil<TitanTabUiState.NoTitan>())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `choosing an activity updates the stage and the energy without leaving the screen`() =
        runTest(dispatcher) {
            pets.pets.value = listOf(brick())
            val vm = viewModel()

            vm.uiState.test {
                val before = awaitUntil<TitanTabUiState.Titan>()
                assertEquals(TitanActivity.IDLE, before.activity)

                vm.onEvent(TitanTabEvent.Choose(TitanActivity.RUN))
                val after = awaitUntil<TitanTabUiState.Titan> { it.activity == TitanActivity.RUN }
                assertEquals(before.vitals.energy - TitanActivity.RUN.energyCost, after.vitals.energy)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `feeding shows the effect chip, and the next action clears it`() =
        runTest(dispatcher) {
            pets.pets.value = listOf(brick())
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil<TitanTabUiState.Titan>()
                vm.onEvent(TitanTabEvent.Feed("steak"))
                val fed = awaitUntil<TitanTabUiState.Titan> { it.lastEffect != null }
                assertEquals(TitanActivity.EAT, fed.activity)
                assertEquals(listOf("steak"), pantry.eaten)

                vm.onEvent(TitanTabEvent.Choose(TitanActivity.WALK))
                awaitUntil<TitanTabUiState.Titan> { it.lastEffect == null }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `tapping owned gear wears it and tapping it again takes it off`() =
        runTest(dispatcher) {
            pets.pets.value = listOf(brick())
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil<TitanTabUiState.Titan>()
                vm.onEvent(TitanTabEvent.ToggleGear("headband"))
                awaitUntil<TitanTabUiState.Titan> { it.head == "headband" }
                vm.onEvent(TitanTabEvent.ToggleGear("headband"))
                awaitUntil<TitanTabUiState.Titan> { it.head == null }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `an invalid name is refused with its reason, a valid one is saved`() =
        runTest(dispatcher) {
            pets.pets.value = listOf(brick())
            val vm = viewModel()

            vm.uiState.test {
                awaitUntil<TitanTabUiState.Titan>()
                vm.onEvent(TitanTabEvent.StartRename)
                vm.onEvent(TitanTabEvent.EditRename("x".repeat(25)))
                vm.onEvent(TitanTabEvent.SaveRename)
                awaitUntil<TitanTabUiState.Titan> { it.rename?.refusal == PetNicknameResult.TooLong }

                vm.onEvent(TitanTabEvent.EditRename("  Tank "))
                vm.onEvent(TitanTabEvent.SaveRename)
                val saved = awaitUntil<TitanTabUiState.Titan> { it.rename == null && it.name == "Tank" }
                assertNull(saved.rename)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the supplies tab and an open rename survive process death`() =
        runTest(dispatcher) {
            pets.pets.value = listOf(brick())
            val first = viewModel()
            first.uiState.test {
                awaitUntil<TitanTabUiState.Titan>()
                first.onEvent(TitanTabEvent.SelectTab(SuppliesTab.HABITAT))
                first.onEvent(TitanTabEvent.StartRename)
                first.onEvent(TitanTabEvent.EditRename("Bri"))
                awaitUntil<TitanTabUiState.Titan> { it.rename?.draft == "Bri" }
                cancelAndIgnoreRemainingEvents()
            }

            val restored = viewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
            restored.uiState.test {
                val state = awaitUntil<TitanTabUiState.Titan>()
                assertEquals(SuppliesTab.HABITAT, state.tab)
                assertEquals("Bri", state.rename?.draft)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the gear list puts owned pieces first`() =
        runTest(dispatcher) {
            pets.pets.value = listOf(brick())
            viewModel().uiState.test {
                val state = awaitUntil<TitanTabUiState.Titan>()
                assertTrue(state.gear.take(3).all { it.owned })
                assertTrue(state.gear.drop(3).none { it.owned })
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend inline fun <reified S : TitanTabUiState> ReceiveTurbine<TitanTabUiState>.awaitUntil(
        crossinline accept: (S) -> Boolean = { true },
    ): S {
        while (true) {
            val item = awaitItem()
            if (item is S && accept(item)) return item
        }
    }

    private fun brick() =
        Pet(
            1,
            "iron_cub",
            Instant.ofEpochMilli(NOW),
            xp = 0,
            stats = PetStats.ZERO,
            nickname = "Brick",
            isActive = true,
        )

    private companion object {
        const val NOW = 1_788_603_600_000L
    }
}
