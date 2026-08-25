package com.opengym.core.domain.plate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic a solution does on itself.
 *
 * Held apart from `PlateCalculatorTest` because these are the sums a *screen* reads — the figure
 * under the barbell, the caption that says how far short it is — and they have to be right for a
 * solution built by hand in a preview as well as for one the solver produced.
 */
class PlateSolutionTest {

    private fun loaded(
        target: Long,
        perSide: List<PlateCount>,
    ) = PlateSolution.Loaded(
        target = target,
        barWeight = kg(20.0),
        perSide = perSide,
    )

    @Test
    fun `the loaded weight counts both ends of the bar`() {
        val solution = loaded(kg(100.0), listOf(PlateCount(kg(20.0), 2)))

        assertEquals(kg(40.0), solution.perSideWeight)
        assertEquals(kg(100.0), solution.achieved)
    }

    @Test
    fun `a shortfall is what is missing, not what was asked for`() {
        val solution = loaded(kg(110.0), listOf(PlateCount(kg(20.0), 2)))

        assertEquals(kg(10.0), solution.shortfall)
        assertFalse(solution.isExact)
    }

    @Test
    fun `a miss inside the tolerance is not a miss`() {
        // Both directions, because an imperial target can land either side of what the rack
        // makes: the two are independent roundings of the same poundage, not two weights.
        assertTrue(loaded(kg(100.0) + 900L, listOf(PlateCount(kg(20.0), 2))).isExact)
        assertTrue(loaded(kg(100.0) - 900L, listOf(PlateCount(kg(20.0), 2))).isExact)
        assertEquals(0L, loaded(kg(100.0) - 900L, listOf(PlateCount(kg(20.0), 2))).shortfall)
    }

    @Test
    fun `a bar with nothing on it is loaded, not empty`() {
        val solution = loaded(kg(20.0), emptyList())

        assertTrue(solution.isBarOnly)
        assertTrue(solution.isExact)
        assertEquals(kg(20.0), solution.achieved)
    }

    @Test
    fun `a target under the bar carries both weights so a screen can say so`() {
        val solution = PlateSolution.BelowBar(target = kg(10.0), barWeight = kg(20.0))

        assertEquals(kg(10.0), solution.target)
        assertEquals(kg(20.0), solution.barWeight)
    }

    @Test
    fun `a plate that is not on the bar is not in the loading`() {
        // A zero-count entry would draw a legend row for a plate the user is not to pick up.
        assertThrows(IllegalArgumentException::class.java) { PlateCount(kg(20.0), 0) }
    }

    @Test
    fun `a plate count weighs its size times its number`() {
        assertEquals(kg(60.0), PlateCount(kg(20.0), 3).totalWeight)
    }
}
