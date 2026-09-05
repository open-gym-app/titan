package com.opengym.core.domain.usecase

import com.opengym.core.domain.gamification.DropRates
import com.opengym.core.domain.gamification.DropTableEvaluator
import com.opengym.core.domain.model.CollectedItem
import com.opengym.core.domain.model.Collectible
import com.opengym.core.domain.model.DropSource
import com.opengym.core.domain.model.Rarity
import com.opengym.core.domain.random.RandomSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The step between a decision and a row: what a finished session's roll means, and the once.
 *
 * `DropTableEvaluatorTest` already pins what the table *says* — the bands, the boundaries and
 * the no-drop remainder — over a catalogue it writes itself, and nothing here restates any of
 * it. What is left is the part only this class decides: that the trigger is
 * [DropSource.SESSION_FINISHED], that the row is dated to the workout rather than to the roll,
 * and that a session is rolled for exactly once however many times the cascade re-enters.
 *
 * The catalogue is a single common, so "what dropped" is never in question and every assertion
 * below is about the claim and the write rather than about which entry came up.
 */
class LootRollUseCaseTest {

    private val gamification = FakeGamificationRepository()
    private val inventory = FakeCollectedItemRepository()

    /** Rolls the lowest number there is, which lands in the first band and takes its first entry. */
    private fun rolling(vararg numbers: Int) =
        LootRollUseCase(
            dropTable = DropTableEvaluator(SequencedRandomSource(numbers.toList()), listOf(ONLY_COLLECTIBLE)),
            collectedItemRepository = inventory,
            gamificationRepository = gamification,
        )

    private val rollLoot = rolling(0, 0)

    @Test
    fun `a finished session's drop is written under the session-finished trigger`() =
        runTest {
            val dropped = rollLoot(SESSION_ID, FINISHED_AT)

            assertEquals(ONLY_COLLECTIBLE, dropped)
            assertEquals(
                listOf(CollectedItem(1L, ONLY_COLLECTIBLE.id, DropSource.SESSION_FINISHED.key, FINISHED_AT)),
                inventory.recorded,
            )
        }

    @Test
    fun `the drop is dated to the workout, not to the roll`() =
        runTest {
            // The cascade re-entered from a boot an hour later still hands `finishedAt` in, and
            // this must stamp what it was given rather than reaching for a clock of its own.
            rollLoot(SESSION_ID, FINISHED_AT)

            assertEquals(FINISHED_AT, inventory.recorded.single().acquiredAt)
        }

    @Test
    fun `a table that pays out nothing writes no row`() =
        runTest {
            // Past the last band: the remainder of the roll is the no-drop case, and it is the
            // common answer rather than an edge — see `DropRates`.
            val dropped = rolling(DropRates.TOTAL_WEIGHT - 1)(SESSION_ID, FINISHED_AT)

            assertNull(dropped)
            assertTrue(inventory.recorded.isEmpty())
        }

    @Test
    fun `rolling the same session again drops nothing and writes nothing`() =
        runTest {
            // The whole point of the fence. The evaluator is handed enough numbers for two
            // rolls, so a second roll would succeed if the claim did not stop it happening.
            val rollTwice = rolling(0, 0, 0, 0)
            val first = rollTwice(SESSION_ID, FINISHED_AT)

            val second = rollTwice(SESSION_ID, FINISHED_AT)

            assertEquals(ONLY_COLLECTIBLE, first)
            assertNull(second)
            assertEquals(1, inventory.recorded.size)
        }

    @Test
    fun `another session is rolled for on its own claim`() =
        runTest {
            val rollTwice = rolling(0, 0, 0, 0)
            rollTwice(SESSION_ID, FINISHED_AT)

            val other = rollTwice(SESSION_ID + 1, FINISHED_AT)

            assertEquals(ONLY_COLLECTIBLE, other)
            assertEquals(2, inventory.recorded.size)
        }

    @Test
    fun `a claim spent on a roll that paid nothing is not returned`() =
        runTest {
            // The claim fences the roll, not the payout. A fence that only closed on a drop
            // would let a re-entered cascade roll again until one landed, which is a drop rate
            // of one per session for anybody whose device crashes.
            val rollTwice = rolling(DropRates.TOTAL_WEIGHT - 1, 0, 0)
            rollTwice(SESSION_ID, FINISHED_AT)

            val second = rollTwice(SESSION_ID, FINISHED_AT)

            assertNull(second)
            assertTrue(inventory.recorded.isEmpty())
        }

    @Test
    fun `a store that refuses the drop throws, and the claim stays spent`() =
        runTest {
            // The tear this deliberately does not recover from: the claim is taken before the
            // write, so a refused row costs the session its collectible outright. The throw is
            // the pipeline's to catch and record — see its failure contract.
            val rollTwice = rolling(0, 0, 0, 0)
            inventory.failNext = IllegalStateException("the inventory is not writable")

            val thrown = runCatching { rollTwice(SESSION_ID, FINISHED_AT) }.exceptionOrNull()
            val afterwards = rollTwice(SESSION_ID, FINISHED_AT)

            assertTrue("expected the store's refusal to propagate, got $thrown", thrown is IllegalStateException)
            assertNull(afterwards)
            assertTrue(inventory.recorded.isEmpty())
        }

    private companion object {

        const val SESSION_ID = 7L

        val FINISHED_AT: Instant = Instant.parse("2026-03-02T18:00:00Z")

        /**
         * The whole catalogue these tests roll against — one common, dropped by a finished
         * session, so a roll of zero always yields it and no assertion here is a statement
         * about what the shipped table happens to stock.
         */
        val ONLY_COLLECTIBLE = Collectible(
            id = "test_chalk",
            rarity = Rarity.COMMON,
            source = DropSource.SESSION_FINISHED,
        )
    }
}

/** Hands the evaluator exactly the numbers a test wrote down, and refuses to invent more. */
private class SequencedRandomSource(private val numbers: List<Int>) : RandomSource {

    private var drawn = 0

    override fun nextInt(bound: Int): Int {
        check(drawn < numbers.size) { "asked for ${drawn + 1} numbers, given ${numbers.size}" }
        return numbers[drawn++]
    }
}
