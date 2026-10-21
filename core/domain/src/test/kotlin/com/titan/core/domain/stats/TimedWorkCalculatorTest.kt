package com.titan.core.domain.stats

import com.titan.core.domain.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Test

private fun set(
    duration: Long? = null,
    distance: Long? = null,
    isCompleted: Boolean = true,
) = WorkoutSet(
    sessionExerciseId = 1,
    setNumber = 1,
    duration = duration,
    distance = distance,
    isCompleted = isCompleted,
    completedAt = if (isCompleted) 1L else 0L,
    createdAt = 0,
    updatedAt = 0,
)

/** The time and distance totals, pinned to the same rules as [VolumeCalculator]'s volume. */
class TimedWorkCalculatorTest {

    @Test
    fun `two runs add up their times and their distances`() {
        val sets = listOf(set(duration = 1_634, distance = 500_000), set(duration = 600, distance = 200_000))

        assertEquals(2_234L, TimedWorkCalculator.totalDuration(sets))
        assertEquals(700_000L, TimedWorkCalculator.totalDistance(sets))
    }

    @Test
    fun `an empty list is zero time and zero distance`() {
        assertEquals(0L, TimedWorkCalculator.totalDuration(emptyList()))
        assertEquals(0L, TimedWorkCalculator.totalDistance(emptyList()))
    }

    @Test
    fun `a set with neither counts as zero rather than dropping the sets around it`() {
        // A hold has a time and no distance; a set of curls has neither. Mirrors the DAO's COALESCE.
        val sets = listOf(set(duration = 45), set(), set(duration = 60, distance = 10_000))

        assertEquals(105L, TimedWorkCalculator.totalDuration(sets))
        assertEquals(10_000L, TimedWorkCalculator.totalDistance(sets))
    }

    @Test
    fun `negative - a placeholder set is not counted`() {
        val sets = listOf(
            set(duration = 45, distance = 100),
            set(duration = 600, distance = 9_000, isCompleted = false),
        )

        assertEquals(45L, TimedWorkCalculator.totalDuration(sets))
        assertEquals(100L, TimedWorkCalculator.totalDistance(sets))
    }
}
