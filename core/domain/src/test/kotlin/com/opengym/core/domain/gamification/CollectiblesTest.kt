package com.opengym.core.domain.gamification

import com.opengym.core.domain.model.Collectible
import com.opengym.core.domain.model.DropSource
import com.opengym.core.domain.model.Rarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue as data: what it contains, and that every band the table prices is stocked.
 *
 * Walks [Collectibles.ALL] rather than naming eight collectibles, the same call [AchievementsTest]
 * and [QuestsTest] both make, so a collectible added later is covered by these tests on the line
 * that adds it.
 */
class CollectiblesTest {

    // ─── The set itself ─────────────────────────────────────────────────────────────────

    @Test
    fun `the initial set ships eight collectibles`() {
        assertEquals(8, Collectibles.ALL.size)
    }

    @Test
    fun `no two collectibles share an id`() {
        assertEquals(Collectibles.ALL.size, Collectibles.ALL.map(Collectible::id).toSet().size)
    }

    // ─── The lookup a drop row is read back through ─────────────────────────────────────

    @Test
    fun `every catalogue entry is reachable by its own id`() {
        for (collectible in Collectibles.ALL) {
            assertEquals(collectible, Collectibles.byId(collectible.id))
        }
    }

    @Test
    fun `an id this build does not know reads back as nothing rather than throwing`() {
        // A drop written by a newer version, or one renamed without a migration. It is a row the
        // store can genuinely hold, so the lookup has to have an answer for it: #104 draws such a
        // drop generically rather than crashing on loot the user actually earned.
        assertNull(Collectibles.byId("a_collectible_from_the_future"))
    }

    @Test
    fun `the ids are the ones the collected_item table holds`() {
        // Pinned for the reason the achievement ids are: an id is written to
        // `CollectedItemEntity.collectibleId` and a rename orphans every drop already under the
        // old spelling — an inventory the user earned, that this build can no longer name or
        // style. A failure here is the reminder that the change needs a migration.
        assertEquals(
            setOf(
                "chalk_block",
                "spare_collar",
                "worn_wristband",
                "lifting_strap",
                "steel_shaker",
                "pet_egg",
                "golden_dumbbell",
                "iron_crown",
            ),
            Collectibles.ALL.map(Collectible::id).toSet(),
        )
    }

    @Test
    fun `an id is spelled the way every other stored key in this app is`() {
        // Lower snake case, like `first_session` and `weekly_volume`. The ids are resource keys
        // as well as column values, and a stray capital or dash is only found by the screen that
        // fails to resolve it.
        val key = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")
        Collectibles.ALL.forEach { collectible ->
            assertTrue("${collectible.id} is not a lower snake case key", key.matches(collectible.id))
        }
    }

    @Test
    fun `the source keys are the ones collected_item source holds`() {
        // The other half of the storage contract, and the one that is not the enum's own name —
        // see [DropSource.key].
        assertEquals(
            setOf("session_finished"),
            DropSource.entries.map(DropSource::key).toSet(),
        )
    }

    @Test
    fun `every trigger the engine knows about actually drops something`() {
        // The call [QuestsTest] makes over `QuestMetric`: a vocabulary constant with no data
        // behind it is a mechanic that does not exist. A second trigger lands with its
        // catalogue entries, not ahead of them.
        assertEquals(DropSource.entries.toSet(), Collectibles.ALL.map(Collectible::source).toSet())
    }

    @Test
    fun `collectibles are listed commonest band first`() {
        // Not read by the roll — [DropTableEvaluator] walks [Rarity] itself — but it is what
        // makes the catalogue legible as a ladder, and what a collection screen would draw.
        val rarities = Collectibles.ALL.map { it.rarity.ordinal }
        assertEquals(rarities.sorted(), rarities)
    }

    // ─── Every priced band is reachable ─────────────────────────────────────────────────

    @Test
    fun `every band the table prices is stocked for every trigger`() {
        // The rule that keeps the economy's numbers honest: a band with nothing in it is a share
        // of the roll that silently drops nothing, so the table would pay out less often than it
        // says. See [DropTableEvaluator], which does not re-roll to cover for it.
        DropSource.entries.forEach { source ->
            val stocked = Collectibles.ALL.filter { it.source == source }.map(Collectible::rarity).toSet()
            assertEquals("$source does not stock every priced band", DropRates.WEIGHTS.keys, stocked)
        }
    }

    @Test
    fun `a rarer band is never stocked wider than a commoner one`() {
        // Otherwise the band's weight would be split across more entries than the band below it,
        // and a specific legendary would be rarer than its own weight suggests while a specific
        // common was commoner — a curve the table no longer describes.
        DropSource.entries.forEach { source ->
            val bySize = Collectibles.ALL
                .filter { it.source == source }
                .groupingBy(Collectible::rarity)
                .eachCount()

            Rarity.entries.zipWithNext { commoner, rarer ->
                assertTrue(
                    "$source stocks $rarer wider than $commoner",
                    bySize[rarer].orZero() <= bySize[commoner].orZero(),
                )
            }
        }
    }

    // ─── Entries are coherent ───────────────────────────────────────────────────────────

    @Test
    fun `a collectible without an id is not a collectible`() {
        // It is the key its drops are stored under and the key its name is looked up by, so a
        // blank one is a drop nothing can read back.
        assertThrows(IllegalArgumentException::class.java) {
            Collectible(id = " ", rarity = Rarity.COMMON, source = DropSource.SESSION_FINISHED)
        }
    }

    /**
     * How wide a band is stocked, counting an unstocked one as the zero it is.
     *
     * `eachCount()` has no key for a band with nothing in it, so reading one through
     * `getValue` would abort this test with a `NoSuchElementException` naming the map rather
     * than fail it with the message above. An unstocked band is a real state — it is what
     * `every band the table prices is stocked for every trigger` exists to catch — and it
     * belongs to that test, not to this one.
     */
    private fun Int?.orZero(): Int = this ?: 0
}
