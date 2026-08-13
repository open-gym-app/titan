package com.opengym.core.domain.plate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The bounds one plate size is held to.
 *
 * None of them describe a plate anybody sells. They exist so that an inventory read back from a
 * store — or typed into an editor — cannot hand [PlateCalculator] a search it will not finish;
 * see [PlateStock.MIN_PLATE_WEIGHT].
 */
class PlateStockTest {

    @Test
    fun `a plate lighter than the lightest micro-plate is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlateStock(weight = PlateStock.MIN_PLATE_WEIGHT - 1, pairs = 2)
        }
        assertThrows(IllegalArgumentException::class.java) { PlateStock(weight = 0, pairs = 2) }
    }

    @Test
    fun `a plate heavier than a strongman plate is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlateStock(weight = PlateStock.MAX_PLATE_WEIGHT + 1, pairs = 2)
        }
    }

    @Test
    fun `the bounds themselves are legal`() {
        assertEquals(PlateStock.MIN_PLATE_WEIGHT, PlateStock(PlateStock.MIN_PLATE_WEIGHT, 1).weight)
        assertEquals(PlateStock.MAX_PLATE_WEIGHT, PlateStock(PlateStock.MAX_PLATE_WEIGHT, 1).weight)
    }

    @Test
    fun `a negative count is refused and a count of none is not`() {
        assertThrows(IllegalArgumentException::class.java) { PlateStock(kg(20.0), pairs = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            PlateStock(kg(20.0), pairs = PlateStock.MAX_PAIRS + 1)
        }
        // Zero is the editor's "I do not have this size", not an absent answer.
        assertEquals(0, PlateStock(kg(20.0), pairs = 0).pairs)
    }

    @Test
    fun `a pair is two plates`() {
        assertEquals(kg(80.0), PlateStock(weight = kg(20.0), pairs = 2).totalWeight)
        assertEquals(0L, PlateStock(weight = kg(20.0), pairs = 0).totalWeight)
    }
}
