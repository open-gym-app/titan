package com.titan.core.domain.plate

import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.milligramsPerUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The rack: what it refuses to be, and what the two defaults are.
 *
 * The interesting assertions are the last few. Some pin the sizes a user who has never opened
 * the editor sees; the last pins the property [PlateCalculator] quietly depends on and nothing
 * else would notice was broken — that every size is a whole multiple of the smallest.
 */
class PlateInventoryTest {

    @Test
    fun `an inventory must be ordered heaviest first`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlateInventory(
                listOf(
                    PlateStock(weight = kg(10.0), pairs = 2),
                    PlateStock(weight = kg(20.0), pairs = 2),
                ),
            )
        }
    }

    @Test
    fun `a size cannot appear twice`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlateInventory(
                listOf(
                    PlateStock(weight = kg(20.0), pairs = 2),
                    PlateStock(weight = kg(20.0), pairs = 1),
                ),
            )
        }
    }

    @Test
    fun `an inventory cannot hold more sizes than a rack has`() {
        val tooMany = (1..PlateInventory.MAX_DENOMINATIONS + 1)
            .map { PlateStock(weight = kg(it.toDouble()), pairs = 1) }
            .sortedByDescending { it.weight }

        assertThrows(IllegalArgumentException::class.java) { PlateInventory(tooMany) }
    }

    @Test
    fun `an empty rack is a legal answer`() {
        // "I train with a fixed bar" — refusing it here would only push the special case into
        // every caller. `PlateCalculatorTest` covers what the solver does with one.
        assertEquals(emptyList<PlateStock>(), PlateInventory(emptyList()).stock)
    }

    @Test
    fun `of sorts and merges what it is given`() {
        // The forgiving door in, for a decoder reading a line somebody hand-edited. The larger
        // count wins, because the two entries are the same fact recorded twice and losing plates
        // is the worse of the two ways to be wrong.
        val inventory = PlateInventory.of(
            listOf(
                PlateStock(weight = kg(10.0), pairs = 1),
                PlateStock(weight = kg(20.0), pairs = 2),
                PlateStock(weight = kg(10.0), pairs = 4),
            ),
        )

        assertEquals(
            listOf(PlateStock(kg(20.0), 2), PlateStock(kg(10.0), 4)),
            inventory.stock,
        )
    }

    @Test
    fun `only the sizes actually held can be loaded`() {
        val inventory = rackOf(kg(20.0) to 2, kg(15.0) to 0, kg(10.0) to 1)

        assertEquals(listOf(kg(20.0), kg(10.0)), inventory.available.map { it.weight })
        // The switched-off size stays in the rack, so the editor still has a row to turn back on.
        assertEquals(3, inventory.stock.size)
    }

    @Test
    fun `the step is a pair of the smallest plate the user actually has`() {
        // 2.5 kg on the metric default, 5 lb on the imperial one — the change plates, doubled.
        assertEquals(kg(2.5), PlateInventory.METRIC.loadStep)
        assertEquals(2 * lb(2.5), PlateInventory.IMPERIAL.loadStep)

        // A size switched off is not a step the user can take.
        val noChangePlates = PlateInventory.METRIC.withPairs(kg(1.25), pairs = 0)
        assertEquals(kg(5.0), noChangePlates.loadStep)
    }

    @Test
    fun `a rack with nothing to load has no step`() {
        assertNull(PlateInventory(emptyList()).loadStep)
        assertNull(rackOf(kg(20.0) to 0).loadStep)
    }

    @Test
    fun `the total is every plate on the rack, both ends counted`() {
        assertEquals(kg(100.0), rackOf(kg(20.0) to 2, kg(10.0) to 1).totalWeight)
    }

    @Test
    fun `changing a count leaves every other size alone`() {
        val inventory = rackOf(kg(20.0) to 2, kg(10.0) to 2).withPairs(kg(10.0), 5)

        assertEquals(listOf(PlateStock(kg(20.0), 2), PlateStock(kg(10.0), 5)), inventory.stock)
    }

    @Test
    fun `changing a count for a size the rack does not have adds it, in order`() {
        val inventory = rackOf(kg(20.0) to 2, kg(10.0) to 2).withPairs(kg(15.0), 1)

        assertEquals(listOf(kg(20.0), kg(15.0), kg(10.0)), inventory.stock.map { it.weight })
    }

    @Test
    fun `a size switched off keeps its place in the rack`() {
        val inventory = rackOf(kg(20.0) to 2, kg(10.0) to 2).withPairs(kg(10.0), 0)

        assertEquals(2, inventory.stock.size)
        assertEquals(listOf(kg(20.0)), inventory.available.map { it.weight })
    }

    // ─── The defaults ───────────────────────────────────────────────────────────────────

    @Test
    fun `the default metric rack is what an ordinary gym stocks`() {
        assertEquals(
            listOf(kg(20.0), kg(10.0), kg(5.0), kg(2.5), kg(1.25)),
            PlateInventory.METRIC.stock.map { it.weight },
        )
    }

    @Test
    fun `the default metric rack has no competition sizes on it`() {
        // The 25 and the 15 are the two an IWF rack adds and an ordinary one does not have.
        // Pinned separately from the list above so that putting either back is a failure that
        // names what changed rather than a diff of a list of milligram figures.
        val sizes = PlateInventory.METRIC.stock.map { it.weight }

        assertFalse(kg(25.0) in sizes)
        assertFalse(kg(15.0) in sizes)
    }

    @Test
    fun `the default imperial rack is what an American gym stocks`() {
        // Read back as pounds rather than compared against milligram literals — nobody reviews
        // `20_411_658` — and with a tolerance, because the sizes are built as multiples of the
        // 2.5 lb plate rather than converted one by one. See the test below for why, and note
        // that the tolerance here is a millionth of a pound: these are the stamped weights.
        val pounds = PlateInventory.IMPERIAL.stock
            .map { it.weight / UnitSystem.IMPERIAL.milligramsPerUnit }

        listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5).forEachIndexed { index, stamped ->
            assertEquals(stamped, pounds[index], STAMPED_WEIGHT_TOLERANCE)
        }
        assertEquals(6, pounds.size)
    }

    @Test
    fun `every default size is a whole multiple of the smallest one`() {
        // The property `PlateCalculator` searches on, and the one nothing else would notice was
        // broken: converting each size to milligrams independently leaves them a milligram out
        // of step, which collapses their common divisor to one milligram and the solver's grid
        // to a hundred million cells.
        listOf(PlateInventory.METRIC, PlateInventory.IMPERIAL).forEach { rack ->
            val smallest = rack.stock.last().weight
            rack.stock.forEach { plate ->
                assertEquals("${plate.weight} is not a multiple of $smallest", 0L, plate.weight % smallest)
            }
        }
    }

    private companion object {
        /** A millionth of a pound. The multiples are exact plates to anything a user can see. */
        const val STAMPED_WEIGHT_TOLERANCE = 0.000_01
    }
}
