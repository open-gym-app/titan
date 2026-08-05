package com.opengym.core.domain.stats

import com.opengym.core.domain.model.ExerciseStats
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Test

/** One kilogram in the canonical storage unit, to keep the fixtures readable. */
private const val KG = 1_000_000L

private const val MINUTE_MILLIS = 60_000L

private fun set(
    sessionExerciseId: Long,
    reps: Int? = 10,
    weight: Long? = 100 * KG,
    isCompleted: Boolean = true,
) = WorkoutSet(
    sessionExerciseId = sessionExerciseId,
    setNumber = 1,
    reps = reps,
    weight = weight,
    isCompleted = isCompleted,
    completedAt = if (isCompleted) 1L else 0L,
    createdAt = 0,
    updatedAt = 0,
)

/**
 * Every case is a fixed set list against exact expected counts and volumes.
 * The breakdown is compared as a whole list, so a rule that silently reordered or
 * dropped an exercise fails here rather than in the UI.
 */
class SessionStatsCalculatorTest {

    @Test
    fun `aggregates across multiple exercises`() {
        val sets = listOf(
            set(sessionExerciseId = 1, reps = 10, weight = 100 * KG),
            set(sessionExerciseId = 1, reps = 8, weight = 100 * KG),
            set(sessionExerciseId = 2, reps = 12, weight = 40 * KG),
        )

        val stats = SessionStatsCalculator.calculate(sets, startedAt = 0, endedAt = 0)

        assertEquals(3, stats.totalSets)
        assertEquals(2_280 * KG, stats.totalVolume)
        assertEquals(
            listOf(
                ExerciseStats(sessionExerciseId = 1, totalSets = 2, totalVolume = 1_800 * KG),
                ExerciseStats(sessionExerciseId = 2, totalSets = 1, totalVolume = 480 * KG),
            ),
            stats.exerciseBreakdown,
        )
    }

    @Test
    fun `a single exercise session produces one breakdown entry`() {
        val sets = List(3) { set(sessionExerciseId = 7, reps = 10, weight = 100 * KG) }

        val stats = SessionStatsCalculator.calculate(sets, startedAt = 0, endedAt = 0)

        assertEquals(3, stats.totalSets)
        assertEquals(3_000 * KG, stats.totalVolume)
        assertEquals(
            listOf(ExerciseStats(sessionExerciseId = 7, totalSets = 3, totalVolume = 3_000 * KG)),
            stats.exerciseBreakdown,
        )
    }

    @Test
    fun `a session with no sets is all zeroes`() {
        val stats = SessionStatsCalculator.calculate(emptyList(), startedAt = 0, endedAt = 0)

        assertEquals(0, stats.totalSets)
        assertEquals(0L, stats.totalVolume)
        assertEquals(0L, stats.durationSeconds)
        assertEquals(emptyList<ExerciseStats>(), stats.exerciseBreakdown)
    }

    @Test
    fun `total volume equals the sum of the breakdown`() {
        val sets = listOf(
            set(sessionExerciseId = 1, reps = 5, weight = 140 * KG),
            set(sessionExerciseId = 2, reps = 15, weight = 0L),
            set(sessionExerciseId = 3, reps = null, weight = null),
            set(sessionExerciseId = 1, reps = 5, weight = 140 * KG),
        )

        val stats = SessionStatsCalculator.calculate(sets, startedAt = 0, endedAt = 0)

        assertEquals(stats.exerciseBreakdown.sumOf { it.totalVolume }, stats.totalVolume)
        assertEquals(stats.exerciseBreakdown.sumOf { it.totalSets }, stats.totalSets)
        assertEquals(1_400 * KG, stats.totalVolume)
    }

    @Test
    fun `an exercise of only bodyweight sets still appears with zero volume`() {
        val sets = listOf(
            set(sessionExerciseId = 1, reps = 10, weight = 60 * KG),
            set(sessionExerciseId = 2, reps = 20, weight = 0L),
        )

        val stats = SessionStatsCalculator.calculate(sets, startedAt = 0, endedAt = 0)

        assertEquals(
            ExerciseStats(sessionExerciseId = 2, totalSets = 1, totalVolume = 0L),
            stats.exerciseBreakdown.single { it.sessionExerciseId == 2L },
        )
    }

    @Test
    fun `placeholder sets count for neither the total nor the breakdown`() {
        val sets = listOf(
            set(sessionExerciseId = 1, isCompleted = true),
            set(sessionExerciseId = 1, isCompleted = false),
            // Planned but never performed: this exercise must not appear at all.
            set(sessionExerciseId = 2, isCompleted = false),
        )

        val stats = SessionStatsCalculator.calculate(sets, startedAt = 0, endedAt = 0)

        assertEquals(1, stats.totalSets)
        assertEquals(1_000 * KG, stats.totalVolume)
        assertEquals(
            listOf(ExerciseStats(sessionExerciseId = 1, totalSets = 1, totalVolume = 1_000 * KG)),
            stats.exerciseBreakdown,
        )
    }

    @Test
    fun `the breakdown follows the order the sets arrived in`() {
        val sets = listOf(
            set(sessionExerciseId = 9),
            set(sessionExerciseId = 3),
            set(sessionExerciseId = 9),
        )

        val stats = SessionStatsCalculator.calculate(sets, startedAt = 0, endedAt = 0)

        assertEquals(listOf(9L, 3L), stats.exerciseBreakdown.map { it.sessionExerciseId })
    }

    @Test
    fun `duration is the window in whole seconds`() {
        val stats = SessionStatsCalculator.calculate(
            emptyList(),
            startedAt = 1_000L,
            endedAt = 1_000L + 45 * MINUTE_MILLIS,
        )

        assertEquals(45 * 60L, stats.durationSeconds)
    }

    @Test
    fun `a sub second window truncates to zero`() {
        val stats = SessionStatsCalculator.calculate(emptyList(), startedAt = 0, endedAt = 999L)

        assertEquals(0L, stats.durationSeconds)
    }

    @Test
    fun `a backwards window is clamped to zero rather than reported negative`() {
        // A device clock adjusted mid-session can end a session before it began.
        val stats = SessionStatsCalculator.calculate(emptyList(), startedAt = 5_000L, endedAt = 1_000L)

        assertEquals(0L, stats.durationSeconds)
    }

    @Test
    fun `a session overload runs to completedAt once the session is closed out`() {
        val session = session(createdAt = 1_000L, completedAt = 1_000L + 30 * MINUTE_MILLIS)

        val stats = SessionStatsCalculator.calculate(session, emptyList(), now = Long.MAX_VALUE)

        assertEquals(30 * 60L, stats.durationSeconds)
    }

    @Test
    fun `a session overload runs to now while the session is still in progress`() {
        val session = session(createdAt = 1_000L, completedAt = null)

        val stats = SessionStatsCalculator.calculate(
            session,
            emptyList(),
            now = 1_000L + 10 * MINUTE_MILLIS,
        )

        assertEquals(10 * 60L, stats.durationSeconds)
    }

    @Test
    fun `a hundred set session accumulates no rounding error`() {
        // 100 sets at 8 reps x 62.5 kg spread over four exercises: milligram-reps
        // are exact, so the expected total is an equality, not a tolerance.
        val sets = List(100) { index ->
            set(sessionExerciseId = (index % 4).toLong(), reps = 8, weight = 62_500_000L)
        }

        val stats = SessionStatsCalculator.calculate(sets, startedAt = 0, endedAt = 0)

        assertEquals(100, stats.totalSets)
        assertEquals(50_000 * KG, stats.totalVolume)
        assertEquals(4, stats.exerciseBreakdown.size)
        stats.exerciseBreakdown.forEach { assertEquals(12_500 * KG, it.totalVolume) }
    }

    private fun session(createdAt: Long, completedAt: Long?) = WorkoutSession(
        id = 1,
        completedAt = completedAt,
        isInProgress = completedAt == null,
        lastActiveAt = createdAt,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
