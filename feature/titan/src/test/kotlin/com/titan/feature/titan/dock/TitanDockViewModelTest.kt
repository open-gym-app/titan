package com.titan.feature.titan.dock

import app.cash.turbine.test
import com.titan.core.domain.gamification.TitanRules
import com.titan.core.domain.model.Pet
import com.titan.core.domain.model.PetStats
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanPlace
import com.titan.feature.titan.tab.FakeLoadout
import com.titan.feature.titan.tab.FakePets
import com.titan.feature.titan.tab.FakeTitans
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class TitanDockViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val pets = FakePets()
    private val titans = FakeTitans(NOW)
    private val loadout = FakeLoadout()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = TitanDockViewModel(pets, titans, loadout).also { it.refresh = Duration.INFINITE }

    @Test
    fun `with no titan the dock is hidden`() =
        runTest(dispatcher) {
            viewModel().uiState.test {
                assertEquals(TitanDockUiState.Hidden, awaitItem())
                dispatcher.scheduler.advanceUntilIdle()
                expectNoEvents()
            }
        }

    @Test
    fun `with a titan the dock shows it, what it wears and its alert`() =
        runTest(dispatcher) {
            pets.pets.value = listOf(BRICK)
            loadout.rows.value = mapOf("head" to "crown", "habitat" to "pool")

            viewModel().uiState.test {
                var state = awaitItem()
                while (state !is TitanDockUiState.Shown) state = awaitItem()
                assertEquals("Brick", state.name)
                assertEquals(TitanActivity.IDLE, state.activity)
                assertEquals("crown", state.head)
                assertEquals(TitanPlace.POOL, state.place)
                assertEquals(null, state.face)
                assertEquals(TitanRules.alertFor(state.vitals), state.alert)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `with no habitat chosen the dock stands the titan on the default one`() =
        runTest(dispatcher) {
            pets.pets.value = listOf(BRICK)

            viewModel().uiState.test {
                var state = awaitItem()
                while (state !is TitanDockUiState.Shown) state = awaitItem()
                assertEquals(TitanPlace.DEFAULT, state.place)
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        const val NOW = 1_788_603_600_000L
        val BRICK = Pet(
            1,
            "iron_cub",
            Instant.ofEpochMilli(NOW),
            xp = 0,
            stats = PetStats.ZERO,
            nickname = "Brick",
            isActive = true,
        )
    }
}
