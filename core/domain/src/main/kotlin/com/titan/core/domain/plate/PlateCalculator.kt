package com.titan.core.domain.plate

/**
 * What to hang on each end of the bar to reach a target weight.
 *
 * ### It finds the best loading, not the first one
 *
 * The obvious implementation is greedy — take the heaviest plate that still fits, repeat — and
 * it is right for almost every rack, because gym plate sets are built so that the small sizes
 * fill in whatever the big ones leave over. It is *not* right in general, and the counter-
 * example is ordinary rather than contrived: a rack down to its last pair of 25s and holding
 * two pairs of 15s cannot make 30 kg a side greedily (25, then nothing fits) when 15 + 15 does
 * it exactly.
 *
 * That matters here more than it usually would, because the promise this class makes is that
 * an unreachable target is reported with **the closest weight that is reachable**. Greedy would
 * make that sentence false whenever it went wrong, and the user has no way to tell the two
 * apart: "97.5 kg is the closest you can get" reads identically whether it is true or whether
 * the solver simply gave up early. So the search is exhaustive — see [load] — and the answer is
 * the heaviest loading at or under the target, breaking ties first towards the fewest plates
 * and then towards the biggest ones.
 *
 * ### At or under, never over
 *
 * A loading that overshoots is the one mistake a lifter cannot catch: an extra pair of 2.5s
 * looks exactly like the pair that was supposed to be there, and the set goes into the log at
 * the weight they typed rather than the weight they lifted. So the solver stops at the target
 * and reports [PlateSolution.Loaded.shortfall]; going over is never an option it considers.
 *
 * ### Milligrams are exact; pounds are not
 *
 * Everything here is integer milligrams, and an imperial weight is never a whole number of
 * them. A target typed as "225 lb" and a rack of 45 lb plates are two independent roundings of
 * the same quantity and land a few milligrams apart, which — with no allowance — costs a whole
 * plate and reports a 2.5 lb shortfall on an ordinary bench. [PlateSolution.WEIGHT_TOLERANCE]
 * is that allowance, and it is the *only* place a weight here is not taken literally.
 *
 * ### The cost of being exhaustive
 *
 * The search is over multiples of the greatest common divisor of the sizes on the rack, capped
 * by whichever is smaller of the target and everything the user owns. [PlateStock]'s bounds on
 * plate weight and pair count, and [PlateInventory]'s on how many sizes there may be, are what
 * keep that a few thousand steps for any rack a person could describe.
 */
object PlateCalculator {

    /**
     * The loading for [target] on a [barWeight] bar, out of [inventory].
     *
     * @param target the weight the user wants on the bar, in milligrams. Anything under the bar
     *   comes back as [PlateSolution.BelowBar].
     * @throws IllegalArgumentException if [barWeight] is not positive — a bar of no weight is a
     *   missing answer rather than a preference, which is the same rule
     *   [com.titan.core.domain.model.UserPreferences] applies to the stored value.
     */
    fun solve(
        target: Long,
        barWeight: Long,
        inventory: PlateInventory,
    ): PlateSolution {
        require(barWeight > 0) { "the bar must weigh something, was $barWeight mg" }

        if (target < barWeight - PlateSolution.WEIGHT_TOLERANCE) {
            return PlateSolution.BelowBar(target = target, barWeight = barWeight)
        }

        // Integer division on purpose. A target an odd number of milligrams above the bar cannot
        // be split across two ends, and the leftover milligram is a shortfall like any other
        // rather than something to round away.
        //
        // The tolerance is added here, halved because it is being spent on one end of the bar.
        // See `PlateSolution.WEIGHT_TOLERANCE`: without it, a 45 lb plate and a bar the user
        // typed as "45" round to milligrams a few apart, and the solver refuses the plate that
        // makes the lift.
        val capacity = ((target - barWeight) / PlateStock.PLATES_PER_PAIR).coerceAtLeast(0) +
            PlateSolution.WEIGHT_TOLERANCE / PlateStock.PLATES_PER_PAIR
        val plates = inventory.available

        val perSide = if (plates.isEmpty()) emptyList() else load(capacity, plates)
        return PlateSolution.Loaded(target = target, barWeight = barWeight, perSide = perSide)
    }

    /**
     * The heaviest set of plates that fits in [capacity] on one end, using the fewest plates.
     *
     * A bounded knapsack, solved over a table rather than by recursion: `reachable[i]` holds the
     * smallest number of plates that makes exactly `i` steps of weight, built up one size at a
     * time, and `used[size][i]` remembers how many of that size the best route to `i` took so
     * the answer can be read back out at the end.
     *
     * The step is the greatest common divisor of the sizes present, which is what keeps the
     * table small: a metric rack steps in 1.25 kg, so a 150 kg side is 120 cells rather than
     * 150 million. It is also why the table is exact — every loading that exists is a whole
     * number of steps, so no reachable weight falls between two cells.
     *
     * @param plates sizes held in at least one pair, heaviest first.
     */
    private fun load(
        capacity: Long,
        plates: List<PlateStock>,
    ): List<PlateCount> {
        val step = plates.map { it.weight }.reduce(::greatestCommonDivisor)

        // Capped by the rack as well as by the target: a lifter with two 20s and a 500 kg target
        // should not be given a table with room for the 500 kg.
        val onePerSide = plates.sumOf { it.weight * it.pairs }
        val span = minOf(capacity, onePerSide) / step
        if (span > MAX_CELLS) return greedy(capacity, plates)

        val cells = span.toInt()
        var reachable = IntArray(cells + 1) { UNREACHABLE }
        reachable[0] = 0
        val used = Array(plates.size) { IntArray(cells + 1) }

        plates.forEachIndexed { index, plate ->
            reachable = admit(
                reachable = reachable,
                counts = used[index],
                weight = (plate.weight / step).toInt(),
                pairs = plate.pairs,
            )
        }

        return readBack(plates, used, reachable.indexOfLast { it != UNREACHABLE }, step)
    }

    /**
     * One more size added to the table: [reachable] extended with up to [pairs] plates of
     * [weight] steps each.
     *
     * @param counts the row of [load]'s `used` table for this size, filled in as a side effect —
     *   how many of it the best route into each cell took. Written here rather than derived
     *   afterwards because the arithmetic that finds it is the arithmetic being done anyway.
     * @return the table for every size up to and including this one.
     */
    private fun admit(
        reachable: IntArray,
        counts: IntArray,
        weight: Int,
        pairs: Int,
    ): IntArray {
        val next = IntArray(reachable.size) { UNREACHABLE }

        for (cell in reachable.indices) {
            for (taken in 0..pairs) {
                val remainder = cell - taken * weight
                if (remainder < 0) break

                val without = reachable[remainder]
                val total = if (without == UNREACHABLE) UNREACHABLE else without + taken
                // `<=`, so a tie replaces. Sizes are admitted heaviest first and `taken` only
                // ever increases, which makes that the second tie-break [load] documents: of two
                // loadings weighing the same and using the same number of plates, the one that
                // leans on the bigger plates wins. 25 + 15 rather than 20 + 20 for a hundred
                // kilos, which is what a lifter reaches for.
                if (total != UNREACHABLE && (next[cell] == UNREACHABLE || total <= next[cell])) {
                    next[cell] = total
                    counts[cell] = taken
                }
            }
        }

        return next
    }

    /**
     * The chosen plates, from the table [load] filled in.
     *
     * Walks the sizes backwards from the lightest, taking off however many that size contributed
     * to the best route into the cell it is standing on, then stepping back by their weight. The
     * list comes out lightest-first and is reversed, because a loading is read and racked
     * heaviest-first — inside the collar and outwards.
     */
    private fun readBack(
        plates: List<PlateStock>,
        used: Array<IntArray>,
        best: Int,
        step: Long,
    ): List<PlateCount> {
        var cell = best
        val loading = mutableListOf<PlateCount>()

        for (index in plates.indices.reversed()) {
            val taken = used[index][cell]
            if (taken > 0) {
                loading += PlateCount(weight = plates[index].weight, count = taken)
                cell -= taken * (plates[index].weight / step).toInt()
            }
        }

        return loading.reversed()
    }

    /**
     * The heaviest-first loading, taken one size at a time — the answer when the exhaustive
     * search would not fit.
     *
     * Reachable only through a rack whose sizes share no meaningful common divisor, which is not
     * a rack this app can produce: both default sets are built as whole multiples of their
     * smallest plate (see [PlateInventory]), and the inventory editor changes counts rather than
     * sizes. It is here for what a *stored* inventory could hold — a file edited by hand, or a
     * size some later build allows — where the alternative is a table of tens of millions of
     * cells built on the way to drawing a screen.
     *
     * Greedy is a real answer rather than a refusal, and for any rack a person owns it is the
     * same answer; where it can differ is the case [PlateCalculator]'s own note describes, and a
     * loading two plates off beats a frozen screen.
     */
    private fun greedy(
        capacity: Long,
        plates: List<PlateStock>,
    ): List<PlateCount> {
        var remaining = capacity
        val loading = mutableListOf<PlateCount>()

        for (plate in plates) {
            val count = minOf(plate.pairs.toLong(), remaining / plate.weight).toInt()
            if (count > 0) {
                loading += PlateCount(weight = plate.weight, count = count)
                remaining -= count * plate.weight
            }
        }

        return loading
    }

    /** Euclid, iteratively. Both arguments are positive plate weights, so neither can be zero. */
    private tailrec fun greatestCommonDivisor(
        a: Long,
        b: Long,
    ): Long = if (b == 0L) a else greatestCommonDivisor(b, a % b)

    /** No combination of plates makes this weight. Not a plate count, so it cannot be zero. */
    private const val UNREACHABLE = -1

    /**
     * How large the search table may get before [greedy] takes over.
     *
     * Every rack the app can build lands in the low hundreds — a metric set steps in 1.25 kg, so
     * even a 200 kg side is 160 cells. Sixty-odd thousand is three orders of magnitude of head
     * room and still a table that is built and thrown away inside a frame.
     */
    private const val MAX_CELLS = 65_536L
}
