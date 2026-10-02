package com.titan.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Locks in the invariants the database schema declares as CHECK constraints but
 * that Room cannot express, so they must be enforced in the domain layer:
 *   CHECK (rpe IS NULL OR rpe BETWEEN 10 AND 100)
 *   CHECK (set_type IN (NORMAL, WARMUP, DROP_SET, FAILURE, INTERVAL))
 */
class WorkoutSetTest {

    private fun set(
        rpe: Int? = null,
        setType: SetType = SetType.NORMAL,
    ) = WorkoutSet(
        sessionExerciseId = 1,
        setNumber = 1,
        rpe = rpe,
        setType = setType,
        completedAt = 0,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun `rpe null is allowed`() {
        assertEquals(null, set(rpe = null).rpe)
    }

    @Test
    fun `rpe at lower bound is allowed`() {
        assertEquals(WorkoutSet.RPE_MIN, set(rpe = WorkoutSet.RPE_MIN).rpe)
    }

    @Test
    fun `rpe at upper bound is allowed`() {
        assertEquals(WorkoutSet.RPE_MAX, set(rpe = WorkoutSet.RPE_MAX).rpe)
    }

    @Test
    fun `rpe just below lower bound is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { set(rpe = WorkoutSet.RPE_MIN - 1) }
    }

    @Test
    fun `rpe just above upper bound is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { set(rpe = WorkoutSet.RPE_MAX + 1) }
    }

    @Test
    fun `rpe negative is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { set(rpe = -5) }
    }

    @Test
    fun `set type defaults to NORMAL`() {
        assertEquals(SetType.NORMAL, set().setType)
    }

    @Test
    fun `every set type value is constructible`() {
        SetType.entries.forEach { type ->
            assertEquals(type, set(setType = type).setType)
        }
    }

    @Test
    fun `set type enum matches the schema CHECK constraint set`() {
        // If this fails, the schema's CHECK (set_type IN ...) and the enum have
        // drifted apart; update both together.
        val expected = setOf("NORMAL", "WARMUP", "DROP_SET", "FAILURE", "INTERVAL")
        assertEquals(expected, SetType.entries.map { it.name }.toSet())
    }
}
