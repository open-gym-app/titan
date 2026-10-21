package com.titan.core.ui.format

import com.titan.core.domain.model.WorkoutSet

/*
 * Rate of perceived exertion, from the tenths it is stored in to the `6`, `7.5`, `10` a
 * lifter writes.
 *
 * Stored as RPE x 10 ([WorkoutSet.RPE_MIN]..[WorkoutSet.RPE_MAX]) so a half-point is an
 * integer rather than a float that has to survive a database round trip. The scale is
 * unitless and identical in both unit systems, which is why this is the one formatter here
 * that does not read [com.titan.core.ui.theme.LocalUnitSystem].
 *
 * The half point is the entire reason it is not simply divided by ten and printed: RPE 7.5
 * is a real and commonly-logged value, and `7` for it is wrong in the direction that makes a
 * user add weight.
 */

/** How many decimals an RPE shows. One: the scale moves in half points. */
private const val RPE_FRACTION_DIGITS = 1

private const val RPE_SCALE = 10.0

/** [tenths] as the number a lifter reads: `6`, `7.5`, `10`. */
fun formatRpe(tenths: Int): String = formatNumber(tenths / RPE_SCALE, RPE_FRACTION_DIGITS)

/**
 * The stored value for an RPE the user picked, or null if it is off the scale.
 *
 * Null rather than a clamp: a caller handing this a value outside the scale has a bug, and
 * clamping would store 10 for it and leave nothing to notice.
 */
fun rpeTenths(rpe: Double): Int? {
    val tenths = Math.round(rpe * RPE_SCALE).toInt()
    return tenths.takeIf { it in WorkoutSet.RPE_MIN..WorkoutSet.RPE_MAX }
}
