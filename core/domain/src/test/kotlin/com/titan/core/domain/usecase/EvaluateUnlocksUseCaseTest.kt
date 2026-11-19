package com.titan.core.domain.usecase

import com.titan.core.domain.random.RandomSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class EvaluateUnlocksUseCaseTest {

    private val pets = FakePetRepository()
    private val unlocks = FakeUnlockRepository()

    private val evaluate = EvaluateUnlocksUseCase(pets, unlocks, RandomSource { 0 })

    @Test
    fun `no titan, no drop`() =
        runTest {
            assertEquals(emptySet<String>(), evaluate(levelsGained = 3, at = 1))
            assertEquals(emptyMap<String, Long>(), unlocks.unlocked)
        }

    @Test
    fun `no level gained, no drop`() =
        runTest {
            hatch()

            assertEquals(emptySet<String>(), evaluate(levelsGained = 0, at = 1))
        }

    @Test
    fun `each level gained drops one item and stores it`() =
        runTest {
            hatch()

            assertEquals(setOf("dojo"), evaluate(levelsGained = 1, at = 1))
            assertEquals(setOf("chalk", "cap"), evaluate(levelsGained = 2, at = 2))
            assertEquals(mapOf("dojo" to 1L, "chalk" to 2L, "cap" to 2L), unlocks.unlocked)
        }

    @Test
    fun `an empty pool drops nothing more`() =
        runTest {
            hatch()
            evaluate(levelsGained = 50, at = 1)

            assertEquals(emptySet<String>(), evaluate(levelsGained = 1, at = 2))
            assertEquals(6, unlocks.unlocked.size)
        }

    private suspend fun hatch() {
        pets.hatch(collectedItemId = 1, speciesId = "titan", hatchedAt = Instant.EPOCH)
    }
}
