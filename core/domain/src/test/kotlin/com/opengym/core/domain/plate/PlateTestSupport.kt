package com.opengym.core.domain.plate

import com.opengym.core.domain.model.MILLIGRAMS_PER_KILOGRAM
import com.opengym.core.domain.model.MILLIGRAMS_PER_POUND
import kotlin.math.roundToLong

/*
 * Weights written the way a lifter says them.
 *
 * `kg(102.5)` rather than `102_500_000L`, because the assertions in this package are about
 * whether a rack makes a lift and every one of them would otherwise be read by counting
 * zeroes. The conversion is the app's own — a test that carried its own arithmetic could
 * agree with itself while disagreeing with the code under test.
 */

/** [value] kilograms, in milligrams. */
fun kg(value: Double): Long = (value * MILLIGRAMS_PER_KILOGRAM).roundToLong()

/** [value] pounds, in milligrams — as the app rounds them, not as the plates are built. */
fun lb(value: Double): Long = (value * MILLIGRAMS_PER_POUND).roundToLong()

/** A rack of [weight]-milligram plates and nothing else. */
fun rackOf(vararg plates: Pair<Long, Int>): PlateInventory =
    PlateInventory.of(plates.map { (weight, pairs) -> PlateStock(weight = weight, pairs = pairs) })

/** What [PlateSolution.Loaded.perSide] holds, as `weight to count` pairs, for a readable assert. */
fun PlateSolution.perSide(): List<Pair<Long, Int>> =
    (this as PlateSolution.Loaded).perSide.map { it.weight to it.count }
