package com.titan.core.ui.format

import android.icu.util.MeasureUnit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.titan.core.domain.model.UnitSystem
import com.titan.core.ui.theme.LocalUnitSystem
import kotlin.math.roundToLong

/*
 * Elevation gain, from the centimetres it is stored in to the string a user reads — and back.
 *
 * ### Not a distance either
 *
 * [formatDistance] picks its unit from the magnitude, so the 1,200 m a mountain run climbs would
 * read as `1.2 km`, and its imperial branch answers in miles. A climb is quoted in metres or in
 * feet whatever its size — every watch and every route profile does — so that is the whole of
 * this file, with no threshold, as [formatLength] has none for a tape.
 *
 * ### Whole units
 *
 * A climb is read to the metre or the foot; nobody's watch reports a tenth of either, and a
 * decimal here would claim a precision the barometer does not have. Stored centimetres keep a
 * typed whole-foot value within a centimetre on the way back, so the figure survives being shown,
 * typed and shown again.
 */

/** The CLDR unit a climb is quoted in on this system. */
val UnitSystem.elevationUnit: MeasureUnit
    get() = when (this) {
        UnitSystem.METRIC -> MeasureUnit.METER
        UnitSystem.IMPERIAL -> MeasureUnit.FOOT
    }

/** `m` or `ft`, for a field label — from CLDR, as [UnitSystem.lengthSuffix] is. */
val UnitSystem.elevationSuffix: String
    get() = unitSymbol(elevationUnit)

/** How many centimetres make one of this system's elevation units; both factors are exact. */
val UnitSystem.centimetresPerElevationUnit: Double
    get() = when (this) {
        UnitSystem.METRIC -> 100.0
        UnitSystem.IMPERIAL -> 30.48
    }

/** [centimetres] in [unitSystem], as a whole number with no unit — what a field is seeded with. */
fun formatElevationValue(
    centimetres: Long,
    unitSystem: UnitSystem,
): String = formatNumber(centimetres / unitSystem.centimetresPerElevationUnit, 0)

/** [centimetres] in [unitSystem], with the unit: `120 m`, `394 ft`. */
fun formatElevation(
    centimetres: Long,
    unitSystem: UnitSystem,
): String =
    formatMeasure(
        value = centimetres / unitSystem.centimetresPerElevationUnit,
        unit = unitSystem.elevationUnit,
        maxFractionDigits = 0,
    )

/** [centimetres] in the ambient [LocalUnitSystem], with the unit. */
@Composable
@ReadOnlyComposable
fun formatElevation(centimetres: Long): String = formatElevation(centimetres, LocalUnitSystem.current)

/**
 * A typed climb back into storage: [text] read as [unitSystem]'s elevation unit, as centimetres,
 * or null when it is not a number.
 *
 * The inverse of [formatElevationValue], so the typed value is rounded to the whole unit the
 * field shows before it is scaled, for [parseDistanceCentimetres]'s reason: a field showing
 * whole metres that took `120.4` literally would store 12,040 cm, show it back as `120`, and
 * store 12,000 cm the next time the row was saved untouched.
 *
 * Null means malformed, not out of range, as for [parseLengthMillimetres]: refusing a negative
 * climb is `SetValidator`'s job, which can say why.
 */
fun parseElevationCentimetres(
    text: String,
    unitSystem: UnitSystem,
): Long? =
    parseNumber(text)
        ?.roundToFractionDigits(0)
        ?.let { (it * unitSystem.centimetresPerElevationUnit).roundToLong() }
