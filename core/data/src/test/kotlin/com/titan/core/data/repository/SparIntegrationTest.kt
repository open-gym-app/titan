package com.titan.core.data.repository

import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.db.entity.CollectedItemEntity
import com.titan.core.domain.gamification.SparRules
import com.titan.core.domain.model.PetGain
import com.titan.core.domain.model.PetStats
import com.titan.core.domain.model.SparResult
import com.titan.core.domain.model.SparRivalKind
import com.titan.core.domain.model.TitanVitals
import com.titan.core.domain.random.RandomSource
import com.titan.core.domain.usecase.EvaluateUnlocksUseCase
import com.titan.core.domain.usecase.SparUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The spar over a real database: the snapshot growth files, the bout's one transaction, the tally. */
class SparIntegrationTest : RoomTestBase() {

    private var now = MONDAY + 3 * DAY
    private var roll = 0
    private val pets by lazy { PetRepositoryImpl(db.petDao()) }
    private val spars by lazy { SparRepositoryImpl(db) }
    private val titans by lazy { TitanStateRepositoryImpl(db) { now } }
    private val unlocks by lazy { UnlockRepositoryImpl(db.petUnlockDao()) }
    private val gamification by lazy {
        GamificationRepositoryImpl(
            userProgressDao = db.userProgressDao(),
            achievementDao = db.achievementDao(),
            sessionXpClaimDao = db.sessionXpClaimDao(),
            sessionLootClaimDao = db.sessionLootClaimDao(),
            sessionPetClaimDao = db.sessionPetClaimDao(),
        )
    }
    private val sessions by lazy {
        SessionRepositoryImpl(
            database = db,
            sessionMapper = com.titan.core.data.mapper.SessionMapper(),
            sessionExerciseMapper = com.titan.core.data.mapper.SessionExerciseMapper(),
            setMapper = com.titan.core.data.mapper.WorkoutSetMapper(),
        )
    }
    private val spar by lazy {
        SparUseCase(
            petRepository = pets,
            sparRepository = spars,
            titanStateRepository = titans,
            unlockRepository = unlocks,
            evaluateUnlocks = EvaluateUnlocksUseCase(gamification, sessions, spars, unlocks),
            random = RandomSource { roll },
            clock = { now },
        )
    }

    @Test
    fun `growth files a snapshot under its week, replacing that week's earlier one`() =
        runBlocking {
            val pet = hatch()

            pets.grow(PetGain(xp = 65, stats = PetStats(3, 0, 0)), at = MONDAY + DAY)
            pets.grow(PetGain(xp = 65, stats = PetStats(3, 0, 0)), at = MONDAY + 2 * DAY)
            pets.grow(PetGain(xp = 65, stats = PetStats(3, 0, 0)), at = MONDAY + WEEK)

            val snapshots = spars.observeSnapshots(pet).first()
            assertEquals(listOf(MONDAY, MONDAY + WEEK), snapshots.map { it.weekStart })
            assertEquals(SparRules.powerOf(pets.observeActive().first()!!), snapshots.last().power)
        }

    @Test
    fun `a bout debits energy, credits spirit and is recorded, together`() =
        runBlocking {
            val pet = hatch(weeksAgo = 2)
            val before = titans.observe(pet).first()!!.vitals
            roll = 0

            val result = spar(pet, SparRivalKind.LAST_WEEK)

            val fought = result as SparResult.Fought
            assertTrue(fought.won)
            val after = titans.observe(pet).first()!!.vitals
            assertEquals(before.energy - SparRules.ENERGY_COST, after.energy)
            assertEquals(1, spars.sparsWon())
            assertEquals(listOf(true), spars.observeBouts(pet).first().map { it.won })
        }

    @Test
    fun `a bout under 20 energy is refused and changes nothing`() =
        runBlocking {
            val pet = hatch(weeksAgo = 2)
            titans.modify(pet) { state, _ ->
                com.titan.core.domain.model.TitanChange(state.copy(vitals = TitanVitals(60, 19, 50)), Unit)
            }

            assertEquals(SparResult.NotAffordable(20), spar(pet, SparRivalKind.LAST_WEEK))
            assertEquals(19, titans.observe(pet).first()!!.vitals.energy)
            assertEquals(0, spars.observeBouts(pet).first().size)
        }

    @Test
    fun `spars won counts wins only, and ten unlock the dojo and the scarf`() =
        runBlocking {
            val pet = hatch(weeksAgo = 2)
            roll = 99
            spar(pet, SparRivalKind.LAST_WEEK)
            assertEquals(0, spars.sparsWon())

            roll = 0
            repeat(10) {
                titans.modify(pet) { state, _ ->
                    com.titan.core.domain.model.TitanChange(state.copy(vitals = TitanVitals(60, 100, 50)), Unit)
                }
                spar(pet, SparRivalKind.FIRST_WEEK)
            }

            assertEquals(10, spars.sparsWon())
            assertTrue(unlocks.observeUnlocked().first().containsAll(setOf("dojo", "scarf")))
        }

    @Test
    fun `a rival not on offer is refused`() =
        runBlocking {
            val pet = hatch(weeksAgo = 2)

            assertEquals(SparResult.NoSuchRival, spar(pet, SparRivalKind.TWELVE_WEEKS_AGO))
            assertEquals(SparResult.NotFound, spar(pet + 1, SparRivalKind.LAST_WEEK))
        }

    private suspend fun hatch(weeksAgo: Int = 0): Long {
        val egg = db.collectedItemDao().insert(
            CollectedItemEntity(collectibleId = "pet_egg", source = "session_finished", acquiredAt = 0),
        )
        return pets.hatch(egg, "iron_cub", Instant.ofEpochMilli(MONDAY - weeksAgo * WEEK + DAY)).pet.id
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val WEEK = 7 * DAY
        val MONDAY: Long = Instant.parse("2026-11-09T00:00:00Z").toEpochMilli()
    }
}
