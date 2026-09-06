package com.opengym.core.domain.gamification

import com.opengym.core.domain.model.Collectible
import com.opengym.core.domain.model.DropSource
import com.opengym.core.domain.model.Rarity
import com.opengym.core.domain.random.RandomSource

/**
 * What a trigger drops, if anything.
 *
 * The engine half of the loot mechanic: [DropRates] says how often each band pays out,
 * [Collectibles] says what is in each band, and this turns a [DropSource] plus two numbers from
 * [RandomSource] into a [Collectible] or nothing at all. It reads no repository, writes nothing
 * and stamps nothing with a time — a roll is a decision, and #171 is what takes that decision,
 * fences it against a re-entered cascade and stores the result.
 *
 * ### Two rolls, and why the rarity is drawn first
 *
 * The first roll picks a band from [DropRates.WEIGHTS], or nothing; the second picks an entry
 * within it. That order is what makes the odds a property of the table rather than of the
 * catalogue's contents: a band's chance is its weight over [DropRates.TOTAL_WEIGHT] however many
 * collectibles are in it, so stocking a sixth common widens what a common can be and does not
 * make commons commoner. Weighting each *entry* by its rarity instead — one roll over the whole
 * pool — would have had the drop rates move every time somebody added a line to the catalogue,
 * which is the failure mode this shape exists to avoid.
 *
 * The band is drawn before the trigger's pool is even looked at, so what the *first* number
 * means is a property of [DropRates] alone — "160 is a rare" stays true whatever the catalogue
 * holds. The second number is not: it is an index into the pool that band filtered out, so both
 * its bound and whether it is drawn at all depend on what is stocked (an unstocked band spends
 * one number and stops). A test that wants to state "this sequence yields this drop" therefore
 * supplies its own catalogue; one written against the shipped one is restating the shipped one.
 *
 * ### A band with nothing in it drops nothing
 *
 * If the trigger stocks no collectible of the band that came up, the roll pays out nothing
 * rather than falling through to a neighbouring band. Re-rolling would quietly hand the missing
 * band's weight to whichever band caught the fallback, and the table would stop describing its
 * own odds. It is a catalogue error rather than a state to handle gracefully — `CollectiblesTest`
 * holds every priced band to at least one entry per trigger — and this is what it costs when it
 * happens anyway: a slightly cold table, not a skewed one.
 *
 * @property random where the two numbers come from. Defaulted, so production constructs this
 *   from nothing at all and only a test passes a sequence — the shape
 *   [com.opengym.core.domain.time.Clock] establishes and every use case that needs a moment
 *   follows. See [RandomSource].
 * @property catalogue what can drop. Defaulted to [Collectibles.ALL] for the same reason: the
 *   app has one catalogue, and the parameter exists so a test can pin behaviour against a table
 *   it wrote — a single entry, a missing band — without the assertions turning into statements
 *   about whatever the shipped catalogue happens to stock this month.
 */
class DropTableEvaluator(
    private val random: RandomSource = RandomSource.SYSTEM,
    private val catalogue: List<Collectible> = Collectibles.ALL,
) {

    /**
     * Rolls [source]'s drop table once.
     *
     * @return the collectible that dropped, or null if nothing did — which is the common answer,
     *   by design: on the shipped rates four triggers in five pay out nothing at all.
     */
    fun roll(source: DropSource): Collectible? {
        val rarity = rollRarity() ?: return null
        val pool = catalogue.filter { it.source == source && it.rarity == rarity }
        if (pool.isEmpty()) return null

        return pool[random.nextInt(pool.size)]
    }

    /**
     * Draws a band from [DropRates.WEIGHTS], or null for the remainder that drops nothing.
     *
     * The bands are laid end to end in [Rarity]'s order and the roll walks them, so the boundary
     * between two of them is exactly the running total of the weights before it: on the shipped
     * table 99 is the last common and 100 the first uncommon. Written as a subtraction rather
     * than as a comparison against a running sum so the number being tested is always "how far
     * into the bands that are left", which is the same question at every step.
     */
    private fun rollRarity(): Rarity? {
        var remaining = random.nextInt(DropRates.TOTAL_WEIGHT)
        for ((rarity, weight) in DropRates.WEIGHTS) {
            if (remaining < weight) return rarity
            remaining -= weight
        }
        return null
    }
}
