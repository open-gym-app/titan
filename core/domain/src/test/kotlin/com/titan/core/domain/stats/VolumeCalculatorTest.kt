package com.titan.core.domain.stats

import com.titan.core.domain.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Test

/** One kilogram in the canonical storage unit, to keep the fixtures readable. */
private const val KG = 1_000_000L

private fun set(
    reps: Int? = 10,
    weight: Long? = 100 * KG,
    isCompleted: Boolean = true,
    sessionExerciseId: Long = 1,
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
 * Fixed inputs, exact expected outputs. Volume is integer arithmetic in
 * milligram-reps, so every assertion here is an equality rather than a delta — the
 * 100-set case below pins that there is no drift to tolerate in the first place.
 */
class VolumeCalculatorTest {

    @Test
    fun `three sets of ten reps at a hundred kilograms is three thousand kilograms`() {
        val sets = List(3) { set(reps = 10, weight = 100 * KG) }
        assertEquals(3_000 * KG, VolumeCalculator.totalVolume(sets))
    }

    @Test
    fun `an empty list has zero volume`() {
        assertEquals(0L, VolumeCalculator.totalVolume(emptyList()))
    }

    @Test
    fun `a bodyweight set at zero kilograms contributes zero`() {
        assertEquals(0L, VolumeCalculator.totalVolume(listOf(set(reps = 12, weight = 0L))))
    }

    @Test
    fun `a bodyweight set does not drop the sets around it`() {
        val sets = listOf(set(reps = 12, weight = 0L), set(reps = 5, weight = 60 * KG))
        assertEquals(300 * KG, VolumeCalculator.totalVolume(sets))
    }

    @Test
    fun `a set with no weight counts as zero rather than being dropped`() {
        // A timed hold: reps but no load. Mirrors the DAO's COALESCE.
        assertEquals(0L, VolumeCalculator.totalVolume(listOf(set(reps = 3, weight = null))))
    }

    @Test
    fun `a set with no reps counts as zero`() {
        assertEquals(0L, VolumeCalculator.totalVolume(listOf(set(reps = null, weight = 80 * KG))))
    }

    @Test
    fun `a set with neither weight nor reps counts as zero`() {
        assertEquals(0L, VolumeCalculator.totalVolume(listOf(set(reps = null, weight = null))))
    }

    @Test
    fun `placeholder sets are excluded from the total`() {
        val sets = listOf(
            set(reps = 10, weight = 100 * KG, isCompleted = true),
            set(reps = 10, weight = 100 * KG, isCompleted = false),
        )
        assertEquals(1_000 * KG, VolumeCalculator.totalVolume(sets))
    }

    @Test
    fun `a session of only placeholder sets has zero volume`() {
        val sets = List(4) { set(isCompleted = false) }
        assertEquals(0L, VolumeCalculator.totalVolume(sets))
    }

    @Test
    fun `setVolume is weight times reps for one set`() {
        assertEquals(825 * KG, VolumeCalculator.setVolume(set(reps = 11, weight = 75 * KG)))
    }

    @Test
    fun `setVolume ignores the completed flag`() {
        // The filter belongs to totalVolume; the per-set arithmetic is unconditional.
        assertEquals(
            VolumeCalculator.setVolume(set(isCompleted = true)),
            VolumeCalculator.setVolume(set(isCompleted = false)),
        )
    }

    @Test
    fun `a hundred set session accumulates no rounding error`() {
        // 100 sets at 8 reps x 62.5 kg. Half-kilogram loads are exactly where a
        // Float accumulator would drift; in milligrams the sum is exact, so the
        // tolerance is 0 rather than the 0.01 kg the acceptance criteria allow.
        val sets = List(100) { set(reps = 8, weight = 62_500_000L) }
        assertEquals(50_000 * KG, VolumeCalculator.totalVolume(sets))
    }

    @Test
    fun `a session at the validator ceilings stays well inside Long`() {
        // The widest set SetValidator will accept is 10 tonnes x 10,000 reps = 1e14
        // milligram-reps. 100 of them is 1e16, two orders of magnitude below
        // Long.MAX_VALUE — so the ceilings can rise again before overflow is a risk.
        val widest = set(reps = 10_000, weight = 10_000_000_000L)
        val total = VolumeCalculator.totalVolume(List(100) { widest })

        assertEquals(10_000_000_000_000_000L, total)
        assertEquals(100L * VolumeCalculator.setVolume(widest), total)
    }

    @Test
    fun `volume matches the sum of its parts over a mixed session`() {
        val sets = listOf(
            set(reps = 5, weight = 100 * KG),
            set(reps = 8, weight = 0L),
            set(reps = null, weight = null),
            set(reps = 12, weight = 40 * KG),
        )
        assertEquals(sets.sumOf { VolumeCalculator.setVolume(it) }, VolumeCalculator.totalVolume(sets))
        assertEquals(980 * KG, VolumeCalculator.totalVolume(sets))
    }

    /** A restored weight past `SetValidator`'s bound wrapped the product, and the sum, negative. */
    @Test
    fun `a volume past what a Long holds is held at the bound rather than wrapping`() {
        val heavy = set(reps = 10, weight = Long.MAX_VALUE / 2)

        assertEquals(Long.MAX_VALUE, VolumeCalculator.setVolume(heavy))
        assertEquals(Long.MAX_VALUE, VolumeCalculator.totalVolume(listOf(heavy, heavy, set())))
        assertEquals(Long.MIN_VALUE, VolumeCalculator.setVolume(set(reps = 10, weight = Long.MIN_VALUE / 2)))
    }
}
