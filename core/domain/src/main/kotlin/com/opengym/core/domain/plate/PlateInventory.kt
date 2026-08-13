package com.opengym.core.domain.plate

import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.milligramsPerUnit
import kotlin.math.roundToLong

/**
 * What the user has to load a bar with: every plate size they can reach, heaviest first.
 *
 * ### Ordered, and that is an invariant rather than a convention
 *
 * The constructor refuses a list that is not strictly descending by weight. Two reasons, and
 * the second is the one that bites:
 *
 * - [PlateCalculator] wants the heaviest first anyway, and a solver that sorts its own input
 *   is a solver whose output order depends on what it was handed.
 * - An inventory is stored in [com.opengym.core.domain.model.UserPreferences], and preferences
 *   are compared for change — `distinctUntilChanged` on the settings flow, `==` in a
 *   ViewModel. Two inventories holding the same plates in a different order are `!=`, so a
 *   round trip through the store that happened to reorder them would republish settings that
 *   did not change and redraw every screen. Canonical order makes equality mean what it says.
 *
 * Build one with [of] when the order is not already known to be right; it sorts and merges.
 *
 * ### An empty inventory is legal
 *
 * "I train with a fixed-weight bar and no plates" is a real answer, and so is a user who has
 * switched every size off. [PlateCalculator] answers it honestly — the bar, and the whole
 * target as a shortfall — rather than being protected from it here.
 *
 * @property stock the sizes, strictly heaviest first, each appearing once. A size held in zero
 *   pairs stays in the list; see [PlateStock.pairs].
 */
data class PlateInventory(val stock: List<PlateStock>) {
    init {
        require(stock.size <= MAX_DENOMINATIONS) {
            "an inventory may hold at most $MAX_DENOMINATIONS sizes, had ${stock.size}"
        }
        require(stock.zipWithNext().all { (heavier, lighter) -> heavier.weight > lighter.weight }) {
            "an inventory must be ordered heaviest first with no size repeated, was " +
                stock.map { it.weight }
        }
    }

    /** The sizes the user actually has, which is the only part [PlateCalculator] can load. */
    val available: List<PlateStock> get() = stock.filter { it.pairs > 0 }

    /** Everything on the rack at once — the ceiling on what any target can reach. */
    val totalWeight: Long get() = stock.sumOf { it.totalWeight }

    /**
     * This inventory with [weight]'s count set to [pairs], adding the size if it is new.
     *
     * What the inventory editor's steppers call. Setting a count to zero keeps the row, which
     * is the difference between "I do not have any 15s" and "I have never heard of a 15".
     */
    fun withPairs(weight: Long, pairs: Int): PlateInventory =
        if (stock.any { it.weight == weight }) {
            PlateInventory(stock.map { if (it.weight == weight) it.copy(pairs = pairs) else it })
        } else {
            of(stock + PlateStock(weight = weight, pairs = pairs))
        }

    companion object {

        /** As many sizes as a rack has labels for, plus room for the odd home-gym oddity. */
        const val MAX_DENOMINATIONS: Int = 12

        /**
         * An inventory from plates in any order, with duplicates merged by taking the larger
         * count — the forgiving door in, for a decoder reading a file somebody hand-edited or
         * an editor that has just added a size.
         */
        fun of(stock: List<PlateStock>): PlateInventory = PlateInventory(
            stock.groupBy { it.weight }
                .map { (weight, duplicates) ->
                    PlateStock(weight = weight, pairs = duplicates.maxOf { it.pairs })
                }
                .sortedByDescending { it.weight },
        )

        /**
         * The rack a user gets before they have said anything about their own.
         *
         * Two racks rather than one converted rack, for the reason the bar presets in settings
         * give: a plate is a manufactured object stamped in the system it was made for, and
         * converting the metric set would offer an American lifter a 55.12 lb plate. The
         * metric set is the IWF sizes down to the 1.25 kg change plate; the imperial set is
         * what an American commercial gym racks.
         *
         * The counts describe a well-stocked commercial gym rather than a garage, because the
         * cost of guessing high is a target the calculator says is reachable when the user's
         * own rack is out of 20s — visible the moment they load it — and the cost of guessing
         * low is a shortfall warning on a perfectly ordinary lift, which reads as a broken
         * calculator. The user corrects either one in the same editor.
         */
        fun defaultFor(unitSystem: UnitSystem): PlateInventory = when (unitSystem) {
            UnitSystem.METRIC -> METRIC
            UnitSystem.IMPERIAL -> IMPERIAL
        }

        /** 25 / 20 / 15 / 10 / 5 / 2.5 / 1.25 kg. See [defaultFor]. */
        val METRIC: PlateInventory = rack(
            unitSystem = UnitSystem.METRIC,
            smallest = 1.25,
            plates = arrayOf(25.0 to 6, 20.0 to 4, 15.0 to 2, 10.0 to 2, 5.0 to 2, 2.5 to 2, 1.25 to 2),
        )

        /** 45 / 35 / 25 / 10 / 5 / 2.5 lb. See [defaultFor]. */
        val IMPERIAL: PlateInventory = rack(
            unitSystem = UnitSystem.IMPERIAL,
            smallest = 2.5,
            plates = arrayOf(45.0 to 6, 35.0 to 2, 25.0 to 2, 10.0 to 2, 5.0 to 2, 2.5 to 2),
        )

        /**
         * A default rack, written in the units it is stamped in.
         *
         * Milligram literals would be unreadable and unreviewable — nobody checks that
         * `20_411_657` is 45 lb — so the conversion happens here, once, from the same ratio
         * every other weight in the app goes through.
         *
         * ### Every size is a whole multiple of [smallest], in milligrams
         *
         * Which is what it already is in the units it is sold in: a 45 lb plate is eighteen
         * 2.5 lb plates, a 25 kg plate is twenty 1.25 kg ones. Converting each size
         * independently would lose that, because a pound is not a whole number of milligrams —
         * `45 lb` rounds to 20,411,657 while eighteen rounded `2.5 lb` come to 20,411,658, one
         * milligram apart. The difference is invisible on screen and ruinous underneath:
         * [PlateCalculator] searches a grid of the sizes' common divisor, and sizes that are one
         * milligram out of step have a common divisor of *one milligram*, which turns a
         * hundred-cell search into a hundred-million-cell one.
         *
         * @param smallest the size every other one is a multiple of, in [unitSystem]'s unit.
         */
        private fun rack(
            unitSystem: UnitSystem,
            smallest: Double,
            plates: Array<Pair<Double, Int>>,
        ): PlateInventory {
            val unit = (smallest * unitSystem.milligramsPerUnit).roundToLong()
            return PlateInventory(
                plates.map { (weight, pairs) ->
                    PlateStock(weight = (weight / smallest).roundToLong() * unit, pairs = pairs)
                },
            )
        }
    }
}
