package com.titan.core.domain.usecase

import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class EvaluateUnlocksUseCaseTest {

    private val gamification = FakeGamificationRepository()
    private val sessions = FakeSessionRepository()
    private val unlocks = FakeUnlockRepository()
    private var spars = 0

    private val evaluate = EvaluateUnlocksUseCase(gamification, sessions, { spars }, unlocks)

    @Test
    fun `nothing earned stores nothing`() =
        runTest {
            assertEquals(emptySet<String>(), evaluate(at = 1))
            assertEquals(emptyMap<String, Long>(), unlocks.unlocked)
        }

    @Test
    fun `ten spars won unlock the dojo and the scarf, once`() =
        runTest {
            spars = 10

            assertEquals(setOf("dojo", "scarf"), evaluate(at = 1))
            assertEquals(emptySet<String>(), evaluate(at = 2))
            assertEquals(mapOf("dojo" to 1L, "scarf" to 1L), unlocks.unlocked)
        }

    @Test
    fun `an unlock survives its condition lapsing`() =
        runTest {
            spars = 10
            evaluate(at = 1)

            spars = 0
            evaluate(at = 2)

            assertEquals(setOf("dojo", "scarf"), unlocks.unlocked.keys)
        }

    @Test
    fun `distance is every distance set's, across sessions`() =
        runTest {
            ranOnce(km = 60)
            ranOnce(km = 40)

            assertEquals(setOf("trail", "cap"), evaluate(at = 1))
        }

    @Test
    fun `just under the distance unlocks nothing`() =
        runTest {
            ranOnce(km = 99)

            assertEquals(emptySet<String>(), evaluate(at = 1))
        }

    private suspend fun ranOnce(km: Long) {
        val id = sessions.insert(
            WorkoutSession(completedAt = 1, isInProgress = false, lastActiveAt = 1, createdAt = 0, updatedAt = 1),
        )
        val slot = sessions.addExercise(sessionId = id)
        sessions.insertSet(
            WorkoutSet(
                sessionExerciseId = slot,
                setNumber = 1,
                distance = km * 100_000,
                duration = 600,
                isCompleted = true,
                completedAt = 1,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }
}
