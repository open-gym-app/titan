package com.opengym.core.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.ui.theme.LocalUnitSystem
import kotlin.math.roundToLong

/**
 * Distances, from the centimetres they are stored in to the string a user reads.
 *
 * The same shape as [formatWeight] and for the same reasons — canonical storage, ambient
 * [LocalUnitSystem], a parse that is the inverse of the format — with one difference: the
 * *unit* is chosen per value rather than per system, because the distances a gym records
 * span four orders of magnitude. A 25 m pool length and a 42.2 km marathon are both sets,
 * and no single unit renders both. See [DistanceUnit] and [distanceUnitFor].
 *
 * That choice is why parsing takes an explicit [DistanceUnit] and formatting does not: a
 * readout can pick the unit that suits the value it already has, while an input field must
 * be told which unit its label promises, or `5` means 5 m to the formatter and 5 km to the
 * user.
 */

/** [centimetres] in [unitSystem], as a number with no unit. */
fun formatDistanceValue(centimetres: Long, unitSystem: UnitSystem): String =
    formatDistanceValue(centimetres, unitSystem.distanceUnitFor(centimetres))

/** [centimetres] in an explicitly chosen [unit], as a number with no unit. */
fun formatDistanceValue(centimetres: Long, unit: DistanceUnit): String =
    formatNumber(centimetres / unit.centimetres, unit.fractionDigits)

/** The unit [centimetres] would be shown in on this system: `m`, `km`, `yd` or `mi`. */
fun distanceSuffix(centimetres: Long, unitSystem: UnitSystem): String =
    unitSystem.distanceUnitFor(centimetres).suffix

/** [centimetres] in [unitSystem], with the unit: `400 m`, `5.2 km`, `437 yd`, `3.23 mi`. */
fun formatDistance(centimetres: Long, unitSystem: UnitSystem): String {
    val unit = unitSystem.distanceUnitFor(centimetres)
    return "${formatDistanceValue(centimetres, unit)} ${unit.suffix}"
}

/** [centimetres] in an explicitly chosen [unit], with the unit. */
fun formatDistance(centimetres: Long, unit: DistanceUnit): String =
    "${formatDistanceValue(centimetres, unit)} ${unit.suffix}"

/** [centimetres] in the ambient [LocalUnitSystem], with the unit. */
@Composable
@ReadOnlyComposable
fun formatDistance(centimetres: Long): String =
    formatDistance(centimetres, LocalUnitSystem.current)

/** [centimetres] in the ambient [LocalUnitSystem], without the unit. */
@Composable
@ReadOnlyComposable
fun formatDistanceValue(centimetres: Long): String =
    formatDistanceValue(centimetres, LocalUnitSystem.current)

/**
 * A user's typed distance back into storage: [text] read as [unit], as centimetres, or
 * null when it is not a number.
 *
 * The inverse of [formatDistanceValue] for the same [unit]. As with
 * [parseWeightMilligrams], null means *malformed* and range is left to `SetValidator`,
 * which is the one that can say which bound was missed.
 */
fun parseDistanceCentimetres(text: String, unit: DistanceUnit): Long? =
    parseNumber(text)?.let { (it * unit.centimetres).roundToLong() }
