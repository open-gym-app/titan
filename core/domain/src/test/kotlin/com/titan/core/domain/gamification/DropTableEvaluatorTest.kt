package com.titan.core.domain.gamification

import com.titan.core.domain.model.Collectible
import com.titan.core.domain.model.DropSource
import com.titan.core.domain.model.Rarity
import com.titan.core.domain.random.RandomSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The engine, pinned against sequences rather than against a seed.
 *
 * Every case below states the numbers the roll draws and the drop those numbers buy, which is
 * what [RandomSource] exists for: a test that seeded a `Random` and asserted a collectible would
 * be pinning the JDK's generator to an id, and would break for a reason that has nothing to do
 * with the table. Here a failure means the table changed.
 *
 * The catalogue is usually the test's own, for the same reason: "the last roll that still drops a
 * rare" is a statement about [DropRates], and it should not stop being true because somebody
 * stocked another common. The shipped catalogue gets its own section at the end.
 */
class DropTableEvaluatorTest {

    // ─── Determinism ────────────────────────────────────────────────────────────────────

    @Test
    fun `the same sequence always yields the same drop`() {
        val sequence = intArrayOf(160, 0)

        val first = DropTableEvaluator(FixedRandomSource(*sequence)).roll(DropSource.SESSION_FINISHED)
        val second = DropTableEvaluator(FixedRandomSource(*sequence)).roll(DropSource.SESSION_FINISHED)

        assertNotNull("the sequence was chosen to drop something", first)
        assertEquals(first, second)
    }

    @Test
    fun `the first number picks the band and the second picks within it`() {
        val random = FixedRandomSource(bandStartOf(Rarity.COMMON), 2)

        val drop = DropTableEvaluator(random, THREE_COMMONS).roll(DropSource.SESSION_FINISHED)

        assertEquals(THREE_COMMONS[2], drop)
        // The second draw is bounded by the pool, not by the whole catalogue: a band is picked
        // first and only then is anything filtered, so the index cannot point outside its band.
        assertEquals(listOf(DropRates.TOTAL_WEIGHT, THREE_COMMONS.size), random.bounds)
    }

    // ─── The band boundaries ────────────────────────────────────────────────────────────

    @Test
    fun `each band's first roll lands in that band`() {
        Rarity.entries.forEach { rarity ->
            assertEquals(rarity, bandRolledBy(bandStartOf(rarity)))
        }
    }

    @Test
    fun `each band's last roll lands in that band`() {
        Rarity.entries.forEach { rarity ->
            val lastOfBand = bandStartOf(rarity) + DropRates.WEIGHTS.getValue(rarity) - 1
            assertEquals(rarity, bandRolledBy(lastOfBand))
        }
    }

    @Test
    fun `a band's first roll is the one past the band below it`() {
        // The two assertions above meeting in the middle: no roll between two bands belongs to
        // neither, and none belongs to both.
        Rarity.entries.zipWithNext { commoner, rarer ->
            val boundary = bandStartOf(rarer)
            assertEquals(commoner, bandRolledBy(boundary - 1))
            assertEquals(rarer, bandRolledBy(boundary))
        }
    }

    // ─── Nothing drops ──────────────────────────────────────────────────────────────────

    @Test
    fun `the last roll of the last band still drops`() {
        assertEquals(Rarity.LEGENDARY, bandRolledBy(DropRates.DROP_WEIGHT - 1))
    }

    @Test
    fun `the first roll past every band drops nothing`() {
        val random = FixedRandomSource(DropRates.DROP_WEIGHT)

        assertNull(DropTableEvaluator(random, ONE_OF_EACH).roll(DropSource.SESSION_FINISHED))
    }

    @Test
    fun `the highest roll of all drops nothing`() {
        val random = FixedRandomSource(DropRates.TOTAL_WEIGHT - 1)

        assertNull(DropTableEvaluator(random, ONE_OF_EACH).roll(DropSource.SESSION_FINISHED))
    }

    @Test
    fun `a roll that drops nothing draws one number and stops`() {
        // The fixture throws when asked for a number it was not given, so the assertion is that
        // this call returns at all — plus the count, which says the pool was never indexed.
        val random = FixedRandomSource(DropRates.DROP_WEIGHT)

        DropTableEvaluator(random, ONE_OF_EACH).roll(DropSource.SESSION_FINISHED)

        assertEquals(listOf(DropRates.TOTAL_WEIGHT), random.bounds)
    }

    // ─── Tables the shipped catalogue is not ────────────────────────────────────────────

    @Test
    fun `a table with a single entry drops it whenever its band comes up`() {
        val only = listOf(collectible("only_one", Rarity.UNCOMMON))
        val random = FixedRandomSource(bandStartOf(Rarity.UNCOMMON), 0)

        assertEquals(only.single(), DropTableEvaluator(random, only).roll(DropSource.SESSION_FINISHED))
    }

    @Test
    fun `a table with a single entry drops nothing for every other band`() {
        // The cost of an unstocked band, stated: the weight is not handed to a neighbour, so a
        // one-entry table pays out at its own band's rate and not at the table's.
        val only = listOf(collectible("only_one", Rarity.UNCOMMON))

        Rarity.entries.filter { it != Rarity.UNCOMMON }.forEach { rarity ->
            val random = FixedRandomSource(bandStartOf(rarity))

            assertNull(
                "a $rarity roll fell through to the uncommon entry",
                DropTableEvaluator(random, only).roll(DropSource.SESSION_FINISHED),
            )
        }
    }

    @Test
    fun `an empty table draws its band and drops nothing`() {
        val random = FixedRandomSource(bandStartOf(Rarity.COMMON))

        assertNull(DropTableEvaluator(random, emptyList()).roll(DropSource.SESSION_FINISHED))
        // Drawn regardless, which is what keeps a sequence's meaning independent of what is
        // stocked — see [DropTableEvaluator].
        assertEquals(listOf(DropRates.TOTAL_WEIGHT), random.bounds)
    }

    // ─── The shipped catalogue ──────────────────────────────────────────────────────────

    @Test
    fun `a default evaluator rolls the shipped catalogue`() {
        // The four ends of the shipped table, pinned by id: a change to the catalogue's order or
        // to the rates moves these, and both are storage-visible.
        assertEquals(Collectibles.CHALK_BLOCK, shippedDropFor(0, 0))
        assertEquals(Collectibles.WORN_WRISTBAND, shippedDropFor(bandStartOf(Rarity.UNCOMMON) - 1, 2))
        assertEquals(Collectibles.PET_EGG, shippedDropFor(bandStartOf(Rarity.RARE), 0))
        assertEquals(Collectibles.IRON_CROWN, shippedDropFor(bandStartOf(Rarity.LEGENDARY), 0))
    }

    @Test
    fun `the shipped catalogue drops nothing past its bands`() {
        assertNull(shippedDropFor(DropRates.DROP_WEIGHT))
    }

    // ─── The fixture itself ─────────────────────────────────────────────────────────────

    @Test
    fun `the fixture refuses a reading its bound cannot produce`() {
        // Otherwise a sequence that silently indexed outside a pool would pass here and throw in
        // production, which is the one failure this seam exists to make impossible.
        val random = FixedRandomSource(DropRates.TOTAL_WEIGHT)

        assertThrows(IllegalArgumentException::class.java) { random.nextInt(DropRates.TOTAL_WEIGHT) }
    }

    @Test
    fun `the fixture refuses to be drawn from twice over`() {
        val random = FixedRandomSource(0)
        random.nextInt(1)

        assertThrows(IllegalStateException::class.java) { random.nextInt(1) }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────────────

    /** The band [roll] lands in, read back off a catalogue holding exactly one of each. */
    private fun bandRolledBy(roll: Int): Rarity? =
        DropTableEvaluator(FixedRandomSource(roll, 0), ONE_OF_EACH)
            .roll(DropSource.SESSION_FINISHED)
            ?.rarity

    /** What [Collectibles.ALL] drops for [numbers], through an evaluator built the way production builds it. */
    private fun shippedDropFor(vararg numbers: Int): Collectible? =
        DropTableEvaluator(FixedRandomSource(*numbers)).roll(DropSource.SESSION_FINISHED)

    private companion object {

        /** One entry per band, so a drop names the band the first number landed in. */
        val ONE_OF_EACH: List<Collectible> = Rarity.entries.map { collectible("band_${it.ordinal}", it) }

        /** A band with room in it, so the second number has somewhere to point. */
        val THREE_COMMONS: List<Collectible> = List(3) { collectible("common_$it", Rarity.COMMON) }

        fun collectible(
            id: String,
            rarity: Rarity,
        ): Collectible = Collectible(id = id, rarity = rarity, source = DropSource.SESSION_FINISHED)

        /** Where [rarity]'s band starts, computed from the table rather than restated. */
        fun bandStartOf(rarity: Rarity): Int =
            DropRates.WEIGHTS.entries.takeWhile { it.key != rarity }.sumOf { it.value }
    }
}

/**
 * A [RandomSource] that hands out exactly the numbers it was given, in order.
 *
 * Strict on both sides on purpose. Running out means the evaluator drew more numbers than the
 * case describes, and a fixture that looped or padded with zeroes would turn that into a passing
 * test asserting the wrong thing. A reading outside its own bound means the test wrote a sequence
 * production could never produce, which would pin behaviour reachable only from the fixture.
 */
private class FixedRandomSource(vararg numbers: Int) : RandomSource {

    private val numbers = numbers.toList()
    private var drawn = 0

    /** The bounds asked for, in order — what the sequence was actually spent on. */
    val bounds = mutableListOf<Int>()

    override fun nextInt(bound: Int): Int {
        check(drawn < numbers.size) { "asked for ${drawn + 1} numbers, given ${numbers.size}" }
        val number = numbers[drawn++]
        require(number in 0 until bound) { "$number is not a reading of nextInt($bound)" }
        bounds += bound
        return number
    }
}
