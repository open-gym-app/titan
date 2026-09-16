package com.opengym.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The two things a suggestion may not say: lift nothing, or do it no times. */
class LoadSuggestionTest {

    @Test
    fun `a bodyweight suggestion carries no weight, not a weight of zero`() {
        assertNull(suggestion(weight = null, reps = 8, reason = SuggestionReason.ADD_REP).weight)
        assertThrows(IllegalArgumentException::class.java) {
            suggestion(weight = 0, reps = 8, reason = SuggestionReason.ADD_REP)
        }
    }

    @Test
    fun `a suggestion prescribes at least one rep`() {
        assertThrows(IllegalArgumentException::class.java) {
            suggestion(weight = 100_000_000, reps = 0, reason = SuggestionReason.DELOAD)
        }
    }

    @Test
    fun `the weight it moves from is positive or absent, like the weight itself`() {
        assertThrows(IllegalArgumentException::class.java) {
            suggestion(weight = 100_000_000, reps = 5, reason = SuggestionReason.REPEAT, previousWeight = 0)
        }
    }

    @Test
    fun `the change is the difference between the two weights, and nothing without both`() {
        assertEquals(
            2_500_000L,
            suggestion(weight = 82_500_000, reps = 3, reason = SuggestionReason.INCREASE, previousWeight = 80_000_000)
                .weightChange,
        )
        assertEquals(
            -8_000_000L,
            suggestion(weight = 72_000_000, reps = 3, reason = SuggestionReason.DELOAD, previousWeight = 80_000_000)
                .weightChange,
        )
        assertEquals(
            0L,
            suggestion(weight = 80_000_000, reps = 6, reason = SuggestionReason.ADD_REP, previousWeight = 80_000_000)
                .weightChange,
        )
        // A press-up does not move by a weight.
        assertNull(suggestion(weight = null, reps = 9, reason = SuggestionReason.ADD_REP).weightChange)
    }

    private fun suggestion(
        weight: Long?,
        reps: Int,
        reason: SuggestionReason,
        previousWeight: Long? = null,
    ) = LoadSuggestion(
        weight = weight,
        reps = reps,
        reason = reason,
        previousWeight = previousWeight,
        basis = PastPerformance(listOf(BODYWEIGHT_SET)),
    )

    @Test
    fun `a performance holds at least one set`() {
        assertThrows(IllegalArgumentException::class.java) { PastPerformance(emptyList()) }
    }

    private companion object {
        val BODYWEIGHT_SET = WorkoutSet(
            sessionExerciseId = 1,
            setNumber = 1,
            reps = 8,
            isCompleted = true,
            completedAt = 0,
            createdAt = 0,
            updatedAt = 0,
        )
    }
}
