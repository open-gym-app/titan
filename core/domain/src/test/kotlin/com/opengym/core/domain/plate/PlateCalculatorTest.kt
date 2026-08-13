package com.opengym.core.domain.plate

import com.opengym.core.domain.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The solver, against racks that exist and targets people actually call out.
 *
 * Every case is stated as *the loading a lifter would put on the bar*, not as an intermediate
 * number, because that is the only form of the answer that can be checked against reality: if
 * the assertion for a 100 kg bench does not read as plates somebody would rack, the test has
 * stopped describing the gym.
 */
class PlateCalculatorTest {

    private val metric = PlateInventory.METRIC
    private val imperial = PlateInventory.IMPERIAL
    private val olympicBar = kg(20.0)

    /** The 45 lb plate as the rack holds it — see `PlateInventory.rack` for why it is not `lb(45.0)`. */
    private val fortyFivePound = imperial.stock.first().weight

    private fun solve(target: Long, bar: Long = olympicBar, rack: PlateInventory = metric) =
        PlateCalculator.solve(target = target, barWeight = bar, inventory = rack)

    // ─── Metric, reachable ──────────────────────────────────────────────────────────────

    @Test
    fun `a hundred kilos is a twenty-five and a fifteen a side`() {
        val solution = solve(kg(100.0)) as PlateSolution.Loaded

        // Two twenties weigh the same and use as many plates; the heavier pair wins the tie.
        assertEquals(listOf(kg(25.0) to 1, kg(15.0) to 1), solution.perSide())
        assertEquals(kg(100.0), solution.achieved)
        assertTrue(solution.isExact)
        assertFalse(solution.isBarOnly)
    }

    @Test
    fun `a change plate is used when the target needs one`() {
        // 102.5 kg is 41.25 a side, which no combination of the big plates reaches. The rack's
        // smallest plate is the whole reason the target is loadable at all.
        val solution = solve(kg(102.5)) as PlateSolution.Loaded

        assertEquals(listOf(kg(25.0) to 1, kg(15.0) to 1, kg(1.25) to 1), solution.perSide())
        assertEquals(kg(102.5), solution.achieved)
        assertTrue(solution.isExact)
    }

    @Test
    fun `a loading is listed heaviest first, the way it goes on the bar`() {
        // 142.5 kg: 61.25 a side. The order is not incidental — it is the order the plates are
        // racked in, so a legend read top to bottom is a set of instructions.
        val solution = solve(kg(142.5)) as PlateSolution.Loaded

        assertEquals(
            solution.perSide().map { it.first }.sortedDescending(),
            solution.perSide().map { it.first },
        )
        assertEquals(kg(142.5), solution.achieved)
    }

    // ─── Metric, not reachable ──────────────────────────────────────────────────────────

    @Test
    fun `an unreachable target is loaded short and says by how much`() {
        // 101 kg is half a kilo a side off the 1.25 kg grid the rack steps in. The answer is
        // 100 kg and a kilo of shortfall, never 100 kg presented as though it were 101.
        val solution = solve(kg(101.0)) as PlateSolution.Loaded

        assertEquals(listOf(kg(25.0) to 1, kg(15.0) to 1), solution.perSide())
        assertEquals(kg(100.0), solution.achieved)
        assertEquals(kg(1.0), solution.shortfall)
        assertFalse(solution.isExact)
    }

    @Test
    fun `a rack that runs out is loaded with what is left`() {
        val solution = solve(kg(100.0), rack = rackOf(kg(20.0) to 1)) as PlateSolution.Loaded

        assertEquals(listOf(kg(20.0) to 1), solution.perSide())
        assertEquals(kg(60.0), solution.achieved)
        assertEquals(kg(40.0), solution.shortfall)
    }

    @Test
    fun `a bar with no plates to load reports the whole target as short`() {
        val solution = solve(kg(100.0), rack = PlateInventory(emptyList())) as PlateSolution.Loaded

        assertTrue(solution.isBarOnly)
        assertEquals(olympicBar, solution.achieved)
        assertEquals(kg(80.0), solution.shortfall)
    }

    // ─── The two edges of the bar itself ────────────────────────────────────────────────

    @Test
    fun `a target under the bar is refused rather than loaded`() {
        val solution = solve(kg(15.0))

        assertEquals(PlateSolution.BelowBar(target = kg(15.0), barWeight = olympicBar), solution)
    }

    @Test
    fun `the bar on its own is a loading, not a failure`() {
        val solution = solve(olympicBar) as PlateSolution.Loaded

        assertTrue(solution.isBarOnly)
        assertTrue(solution.isExact)
        assertEquals(0L, solution.shortfall)
    }

    @Test
    fun `a target just over the bar reports the gap rather than inventing a plate`() {
        // Under the smallest plate the rack has, so nothing goes on — and the user is told,
        // rather than being shown a bar and left to wonder where the kilo went.
        val solution = solve(olympicBar + kg(1.0)) as PlateSolution.Loaded

        assertTrue(solution.isBarOnly)
        assertEquals(kg(1.0), solution.shortfall)
    }

    @Test
    fun `a bar of no weight is refused`() {
        assertThrows(IllegalArgumentException::class.java) { solve(kg(100.0), bar = 0) }
    }

    // ─── Imperial ───────────────────────────────────────────────────────────────────────

    @Test
    fun `two twenty five is two forty-fives a side`() {
        val solution = solve(lb(225.0), bar = lb(45.0), rack = imperial) as PlateSolution.Loaded

        assertEquals(listOf(fortyFivePound to 2), solution.perSide())
        assertTrue(solution.isExact)
    }

    @Test
    fun `an imperial rack reaches the odd poundage with its small plates`() {
        // 185 lb: 70 a side, which is 45 + 25.
        val solution = solve(lb(185.0), bar = lb(45.0), rack = imperial) as PlateSolution.Loaded

        assertEquals(listOf(fortyFivePound to 1, imperial.stock[2].weight to 1), solution.perSide())
        assertTrue(solution.isExact)
    }

    @Test
    fun `an imperial target off the rack's grid reports the shortfall in pounds`() {
        // 227 lb sits two pounds above what a 45 lb rack makes, and 2 lb is not a plate.
        val solution = solve(lb(227.0), bar = lb(45.0), rack = imperial) as PlateSolution.Loaded

        assertEquals(listOf(fortyFivePound to 2), solution.perSide())
        assertFalse(solution.isExact)
        // Two pounds, to within the allowance the solver itself works to — a shortfall the two
        // roundings could argue about is one the solver would have called zero.
        assertEquals(
            lb(2.0).toDouble(),
            solution.shortfall.toDouble(),
            PlateSolution.WEIGHT_TOLERANCE.toDouble(),
        )
    }

    // ─── Why the search is exhaustive ───────────────────────────────────────────────────

    @Test
    fun `a target the biggest plate cannot start is still found`() {
        // The case greedy gets wrong, and it is an ordinary rack: one pair of 25s left and two
        // pairs of 15s. Greedy takes the 25 and stops five kilos short; 15 + 15 is exact.
        val solution = solve(
            target = kg(80.0),
            rack = rackOf(kg(25.0) to 1, kg(15.0) to 2),
        ) as PlateSolution.Loaded

        assertEquals(listOf(kg(15.0) to 2), solution.perSide())
        assertTrue(solution.isExact)
    }

    @Test
    fun `the fewest plates wins when two loadings weigh the same`() {
        // 45 a side is either one 25 and one 20, or two 15s and a 15 — the rack has both. Two
        // plates beat three: it is two fewer things to lift onto the bar.
        val solution = solve(
            target = kg(110.0),
            rack = rackOf(kg(25.0) to 2, kg(20.0) to 2, kg(15.0) to 4),
        ) as PlateSolution.Loaded

        assertEquals(2, solution.perSide.sumOf { it.count })
        assertTrue(solution.isExact)
    }

    // ─── The milligram tolerance ────────────────────────────────────────────────────────

    @Test
    fun `a target a fraction of a gram off the rack counts as reached`() {
        // What an imperial weight looks like in milligrams: not wrong, just not expressible.
        // Half a gram is a thousandth of the smallest plate anybody owns.
        val solution = solve(kg(100.0) + 800L) as PlateSolution.Loaded

        assertEquals(kg(100.0), solution.achieved)
        assertTrue(solution.isExact)
        assertEquals(0L, solution.shortfall)
    }

    @Test
    fun `a target a gram past the rack does not`() {
        // The boundary the tolerance is drawn at, asserted from the other side so that widening
        // it silently is a failing test rather than a quiet change of meaning.
        val solution = solve(kg(100.0) + 2_000L) as PlateSolution.Loaded

        assertEquals(2_000L, solution.shortfall)
        assertFalse(solution.isExact)
    }

    // ─── The bounded search ─────────────────────────────────────────────────────────────

    @Test
    fun `a rack whose sizes share nothing is still answered`() {
        // Not reachable through the app — both default racks are whole multiples of their
        // smallest plate — but a stored inventory could hold it, and the answer has to arrive
        // rather than being searched for a hundred million times. See `PlateCalculator.greedy`.
        val awkward = rackOf(100_001L to 20, 100_000L to 20)

        val solution = solve(kg(500.0), rack = awkward) as PlateSolution.Loaded

        assertEquals(listOf(100_001L to 20, 100_000L to 20), solution.perSide())
    }

    // ─── The racks themselves ───────────────────────────────────────────────────────────

    @Test
    fun `the default rack follows the unit system`() {
        assertEquals(metric, PlateInventory.defaultFor(UnitSystem.METRIC))
        assertEquals(imperial, PlateInventory.defaultFor(UnitSystem.IMPERIAL))
    }
}
