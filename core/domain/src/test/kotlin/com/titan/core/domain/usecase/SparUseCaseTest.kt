package com.titan.core.domain.usecase

import app.cash.turbine.test
import com.titan.core.domain.gamification.SparRules
import com.titan.core.domain.gamification.TitanRules
import com.titan.core.domain.model.PowerSnapshot
import com.titan.core.domain.model.SparBout
import com.titan.core.domain.model.SparResult
import com.titan.core.domain.model.SparRivalKind
import com.titan.core.domain.model.TitanChange
import com.titan.core.domain.model.TitanState
import com.titan.core.domain.model.TitanVitals
import com.titan.core.domain.repository.SparRepository
import com.titan.core.domain.repository.TitanStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SparUseCaseTest {

    private val pets = FakePetRepository()
    private val spars = FakeSpars()
    private val titan = MutableStateFlow(TitanRules.starting(NOW).copy(vitals = TitanVitals(60, 80, 50)))
    private val unlocks = FakeUnlockRepository()
    private val gamification = FakeGamificationRepository()
    private var roll = 0

    private val spar = SparUseCase(
        petRepository = pets,
        sparRepository = spars,
        titanStateRepository = OneTitan(titan),
        unlockRepository = unlocks,
        evaluateUnlocks = EvaluateUnlocksUseCase(gamification, FakeSessionRepository(), spars, unlocks),
        random = { roll },
        clock = { NOW },
    )

    @Test
    fun `a won bout is recorded and paid in spirit, and the energy is spent`() =
        runTest {
            val pet = hatch()
            roll = 0

            val result = spar(pet, SparRivalKind.LAST_WEEK) as SparResult.Fought

            assertTrue(result.won)
            assertEquals(3, result.roundsWon)
            assertEquals(SparRules.SPIRIT_ON_WIN, result.spiritGained)
            assertEquals(TitanVitals(60, 60, 58), titan.value.vitals)
            assertEquals(listOf(SparBout("last_week", result.rival.weekStart, won = true)), spars.bouts.value)
        }

    @Test
    fun `a lost bout still pays a little`() =
        runTest {
            val pet = hatch()
            roll = 99

            val result = spar(pet, SparRivalKind.LAST_WEEK) as SparResult.Fought

            assertEquals(false, result.won)
            assertEquals(3, result.roundsLost)
            assertEquals(SparRules.SPIRIT_ON_LOSS, result.spiritGained)
        }

    @Test
    fun `a tired titan is refused and nothing changes`() =
        runTest {
            val pet = hatch()
            titan.value = titan.value.copy(vitals = TitanVitals(60, 19, 50))
            val before = titan.value

            assertEquals(SparResult.NotAffordable(SparRules.ENERGY_COST), spar(pet, SparRivalKind.LAST_WEEK))
            assertEquals(before, titan.value)
            assertTrue(spars.bouts.value.isEmpty())
        }

    @Test
    fun `a rival not on offer, and a pet not there, are answered`() =
        runTest {
            val pet = hatch()

            assertEquals(SparResult.NoSuchRival, spar(pet, SparRivalKind.TWELVE_WEEKS_AGO))
            assertEquals(SparResult.NotFound, spar(pet + 1, SparRivalKind.LAST_WEEK))
        }

    @Test
    fun `ten wins unlock the dojo`() =
        runTest {
            val pet = hatch()
            roll = 0
            repeat(10) {
                titan.value = titan.value.copy(vitals = TitanVitals(60, 100, 50))
                spar(pet, SparRivalKind.LAST_WEEK)
            }

            assertTrue("dojo" in unlocks.unlocked)
        }

    @Test
    fun `the rivals on offer update as bouts are won`() =
        runTest {
            val pet = hatch()

            spar.observeRivals(pet).test {
                assertEquals(listOf(false, false), awaitItem().map { it.beaten })
                roll = 0
                spar(pet, SparRivalKind.LAST_WEEK)
                assertEquals(listOf(true, false), awaitItem().map { it.beaten })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a pet that is not there has no rivals`() =
        runTest {
            spar.observeRivals(404).test {
                assertTrue(awaitItem().isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    /** Hatched two weeks before [NOW]: offered last week and its first week. */
    private suspend fun hatch(): Long =
        pets.hatch(collectedItemId = 1, speciesId = "iron_cub", hatchedAt = Instant.ofEpochMilli(NOW - 14 * DAY)).pet.id

    private class FakeSpars : SparRepository {

        val bouts = MutableStateFlow<List<SparBout>>(emptyList())

        override fun observeSnapshots(petId: Long): Flow<List<PowerSnapshot>> = MutableStateFlow(emptyList())

        override fun observeBouts(petId: Long): Flow<List<SparBout>> = bouts.map { it }

        override suspend fun recordBout(
            petId: Long,
            bout: SparBout,
            power: Int,
            rivalPower: Int,
            roundsWon: Int,
            roundsLost: Int,
            foughtAt: Long,
        ) {
            bouts.value += bout
        }

        override suspend fun sparsWon(): Int = bouts.value.count { it.won }

        override suspend fun <T> atomically(block: suspend () -> T): T = block()
    }

    private class OneTitan(private val state: MutableStateFlow<TitanState>) : TitanStateRepository {

        override fun observe(petId: Long): Flow<TitanState?> = state

        override suspend fun <R> modify(
            petId: Long,
            change: (state: TitanState, now: Long) -> TitanChange<R>,
        ): R? {
            val decided = change(state.value, NOW)
            decided.state?.let { state.value = it }
            return decided.result
        }
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        val NOW: Long = Instant.parse("2026-11-12T12:00:00Z").toEpochMilli()
    }
}
