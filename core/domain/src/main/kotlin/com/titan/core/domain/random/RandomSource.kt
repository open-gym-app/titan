package com.titan.core.domain.random

import kotlin.random.Random

/**
 * Source of uniformly distributed random integers.
 *
 * The drop table has to roll, which would otherwise make it untestable in the only way that
 * matters: a test that seeds a `Random` and asserts a distribution is asserting the JDK's
 * generator, and one that seeds it and asserts a *drop* is pinning an implementation detail of
 * that generator to a collectible id. Behind this seam a test hands the evaluator the exact
 * sequence of numbers it wants and asserts the exact drop that sequence buys — so the weights,
 * the boundaries and the no-drop case are all statements about the table rather than about
 * anybody's PRNG.
 *
 * The shape is [com.titan.core.domain.time.Clock]'s, deliberately, down to the [SYSTEM]
 * default: a `fun interface` taken as a *defaulted* constructor parameter, so production call
 * sites still construct the thing that needs it from its real collaborators alone
 * (`private val random: RandomSource = RandomSource.SYSTEM`) and only a test passes anything.
 * A second seam of this kind should look like this one rather than like a third convention.
 */
fun interface RandomSource {

    /**
     * A value in `0 until bound`, every value equally likely.
     *
     * Half-open and zero-based because every caller is indexing something — a band of a weight
     * table, an entry of a pool — and a closed range would put an off-by-one between the seam
     * and each of them.
     *
     * @param bound the exclusive upper bound, at least 1. **Implementations may throw** below
     *   that, and [SYSTEM] does: a bound of zero is an empty pool the caller should not have
     *   asked about, and answering it with a number would hand back an index into nothing.
     */
    fun nextInt(bound: Int): Int

    companion object {

        /**
         * The real thing: `kotlin.random.Random`'s default generator, unseeded.
         *
         * Unseeded on purpose — the point of the drop table is that the user cannot predict it,
         * and a fixed seed would make every install roll the same collectibles in the same
         * order. Reproducibility belongs to the tests, and they get it by substituting this
         * rather than by seeding it.
         */
        val SYSTEM = RandomSource { bound -> Random.nextInt(bound) }
    }
}
