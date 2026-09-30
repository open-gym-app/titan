package com.titan.core.ui.format

import android.icu.text.DecimalFormatSymbols
import android.icu.text.NumberFormat
import kotlin.math.roundToInt
import android.icu.math.BigDecimal as IcuBigDecimal

/*
 * A percentage that is a *quantity* rather than a comparison: body fat.
 *
 * [formatPercentChange] renders the other kind — how far a figure moved — and deliberately
 * drops the sign, because the arrow beside it carries the direction. Neither is right for a
 * reading: 15.5% body fat is not up or down from anything, it is what the calipers said, and
 * it is stored to a tenth of a percent because that is what a measurement claims.
 *
 * Both go through [NumberFormat.getPercentInstance] rather than a `%` glued onto a number,
 * for the reason [formatPercentChange]'s own note gives: the symbol's *position* is locale-bound —
 * Turkish writes `%15,5` and French `15,5 %` — and no format string answers that. `android.icu`
 * rather than `java.text`, as every number in this package — `NumberFormats.kt` says why.
 */

/**
 * The percent symbol this locale writes, for a label that shows it without a number in front
 * of it — a field's own suffix, a tile's unit.
 *
 * Read off the locale rather than written as a literal `%`, the same choice
 * [formatPercentTenths] makes for the symbol's *position*: Arabic writes `٪` and Persian `٪`,
 * and a hard-coded glyph would put a Latin one next to figures rendered in the locale's own
 * digits. It is the counterpart of `weightSuffix` and `lengthSuffix`, which name a unit for a
 * label the same way.
 */
val percentSuffix: String get() = DecimalFormatSymbols.getInstance(formattingLocale()).percent.toString()

/** How many decimals a percentage is shown to: one, which is the unit it is stored in. */
private const val PERCENT_FRACTION_DIGITS = 1

/** 100%, in the tenths of a percent every figure here is expressed in. */
private const val TENTHS_PER_WHOLE = 1_000.0

/** One percent, in tenths. */
private const val TENTHS_PER_PERCENT = 10.0

/**
 * [tenths] of a percent as the user reads it, symbol included: `155` becomes `15.5%`.
 *
 * The trailing zero goes with it — `140` is `14%`, not `14.0%` — which is [formatNumber]'s
 * rule applied by the same `minimumFractionDigits` of zero.
 */
fun formatPercentTenths(tenths: Int): String =
    NumberFormat.getPercentInstance(formattingLocale())
        .apply {
            maximumFractionDigits = PERCENT_FRACTION_DIGITS
            minimumFractionDigits = 0
            roundingMode = IcuBigDecimal.ROUND_HALF_UP
        }
        .format(tenths / TENTHS_PER_WHOLE)

/**
 * The same figure with no symbol: `155` becomes `15.5`.
 *
 * What a field is seeded with, where the `%` is drawn as the field's own suffix rather than
 * inside the value being edited — a symbol the user would have to type around.
 */
fun formatPercentTenthsValue(tenths: Int): String = formatNumber(tenths / TENTHS_PER_PERCENT, PERCENT_FRACTION_DIGITS)

/**
 * A user's typed percentage back into storage, in tenths: `15.5` becomes `155`.
 *
 * The inverse of [formatPercentTenthsValue], and null means *malformed* rather than out of
 * range — bounding it is `BodyMeasurementValidator`'s job, the same split
 * [parseWeightMilligrams] documents.
 */
fun parsePercentTenths(text: String): Int? = parseNumber(text)?.let { (it * TENTHS_PER_PERCENT).roundToInt() }
