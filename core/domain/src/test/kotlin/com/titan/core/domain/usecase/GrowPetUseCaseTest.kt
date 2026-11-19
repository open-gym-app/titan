package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.PetGrowthRates
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.integration.FakeExerciseRepository
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.Pet
import com.titan.core.domain.model.PetStage
import com.titan.core.domain.model.PetStats
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GrowPetUseCaseTest {

    private val sessions = FakeSessionRepository()
    private val exercises = FakeExerciseRepository(
        listOf(
            Exercise(id = SQUAT, name = "Squat", type = ExerciseType.STRENGTH),
            Exercise(id = PLANK, name = "Plank", type = ExerciseType.ISOMETRIC),
        ),
    )
    private val pets = FakePetRepository()
    private val gamification = FakeGamificationRepository()

    private val grow = GrowPetUseCase(sessions, exercises, pets, gamification)

    @Test
    fun `a finished session grows the active pet once, and a re-run grows nothing`() =
        runTest {
            hatch()
            val session = session(SQUAT to 3)

            val first = grow(session, AT)
            val again = grow(session, AT)

            assertNotNull(first)
            val growth = first!!
            assertEquals(PetGrowthRates.XP_PER_SESSION + 3L * PetGrowthRates.XP_PER_SET, growth.xpGained)
            assertEquals(PetStats(strength = 3, endurance = 0, mobility = 0), growth.statsGained)
            assertNull(again)
            assertEquals(growth.pet, pets.held.single())
        }

    @Test
    fun `holds grow mobility, by the exercise the set belongs to`() =
        runTest {
            hatch()

            val growth = grow(session(SQUAT to 1, PLANK to 2), AT)!!

            assertEquals(PetStats(strength = 1, endurance = 0, mobility = 2), growth.statsGained)
        }

    @Test
    fun `with no active pet the claim is spent and nothing is written`() =
        runTest {
            val session = session(SQUAT to 3)

            assertNull(grow(session, AT))
            assertFalse("the claim was not taken", gamification.claimSessionPet(session))

            // A first pet hatched afterwards is not billed for the sessions before it.
            hatch()
            assertNull(grow(session, AT))
            assertEquals(0L, pets.held.single().xp)
        }

    @Test
    fun `a session with no paid set spends its claim and grows nothing`() =
        runTest {
            hatch()
            val session = session()

            assertNull(grow(session, AT))
            assertEquals(0L, pets.held.single().xp)
            assertFalse(gamification.claimSessionPet(session))
        }

    @Test
    fun `a read that fails leaves the claim untaken, so the re-entered run pays in full`() =
        runTest {
            hatch()
            val session = session(SQUAT to 3)
            val broken = GrowPetUseCase(FailingReads(sessions), exercises, pets, gamification)

            assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { broken(session, AT) } }

            val growth = grow(session, AT)!!
            assertEquals(PetGrowthRates.XP_PER_SESSION + 3L * PetGrowthRates.XP_PER_SET, growth.xpGained)
        }

    @Test
    fun `the pet that grows is the one active when the step runs`() =
        runTest {
            hatch()
            val second = pets.hatch(collectedItemId = 2, speciesId = "atlas", hatchedAt = Instant.EPOCH).pet
            pets.setActive(second.id)

            grow(session(SQUAT to 3), AT)

            assertEquals(listOf(0L, 65L), pets.held.map { it.xp })
        }

    @Test
    fun `a level and a stage crossing are reported when one happens, and only then`() =
        runTest {
            val juvenileAt = XpCalculator.xpRequiredForLevel(PetStage.JUVENILE.fromLevel)
            pets.put(pet(xp = juvenileAt - 1))

            val crossing = grow(session(SQUAT to 1), AT)!!
            val after = grow(session(SQUAT to 1), AT)!!

            assertTrue(crossing.leveledUp)
            assertTrue(crossing.stageChanged)
            assertEquals(PetStage.HATCHLING, crossing.stageBefore)
            assertEquals(PetStage.JUVENILE, crossing.stageAfter)
            assertFalse(after.leveledUp)
            assertFalse(after.stageChanged)
        }

    private suspend fun hatch() {
        pets.hatch(collectedItemId = 1, speciesId = "iron_cub", hatchedAt = Instant.EPOCH)
    }

    private fun pet(xp: Long) =
        Pet(
            id = 1,
            speciesId = "iron_cub",
            hatchedAt = Instant.EPOCH,
            xp = xp,
            stats = PetStats.ZERO,
            nickname = null,
            isActive = true,
        )

    /** A finished session with [sets] completed sets on each exercise named. */
    private suspend fun session(vararg sets: Pair<Long, Int>): Long {
        val id = sessions.insert(
            WorkoutSession(completedAt = 1, isInProgress = false, lastActiveAt = 1, createdAt = 0, updatedAt = 1),
        )
        sets.forEach { (exerciseId, count) ->
            val slot = sessions.addExercise(sessionId = id, exerciseId = exerciseId)
            repeat(count) { sessions.addSet(slot, reps = 5) }
        }
        return id
    }

    /** A session store whose set read throws, leaving everything else working. */
    private class FailingReads(delegate: SessionRepository) : SessionRepository by delegate {
        override fun getSetsForSession(sessionId: Long): Flow<List<com.titan.core.domain.model.WorkoutSet>> =
            error("the sets could not be read")
    }

    private companion object {
        const val SQUAT = 10L
        const val PLANK = 11L
        val AT: Instant = Instant.parse("2026-11-12T18:00:00Z")
    }
}
