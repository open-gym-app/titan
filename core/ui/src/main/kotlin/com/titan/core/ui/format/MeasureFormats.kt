package com.titan.core.ui.format

import android.icu.text.MeasureFormat
import android.icu.text.MeasureFormat.FormatWidth
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/*
 * The seam between a quantity and the unit it is read in — the twin of `NumberFormats.kt`,
 * which owns the number half of the same string.
 *
 * ### Why this is not `"$value ${unit.suffix}"`
 *
 * Because none of the three things that expression assumes are true outside English.
 *
 * 1. **The symbol is translated.** An inch is `in` to an English reader, `po` to a French one
 *    and `pol.` to a Brazilian one; an hour is `h` in most of Europe and `hr` in the United
 *    States. A `kg` that survives translation intact does so because the SI symbol happens to
 *    be the same everywhere, which is a fact about kilograms and not a rule about units.
 * 2. **The join is not a space.** Some locales set a non-breaking space, some a narrow one,
 *    some nothing at all, and the pattern differs per unit inside one locale.
 * 3. **The order is not universal.** The number leads in every locale this app ships in, but
 *    the unit pattern is what decides that, not the code that builds the string.
 *
 * CLDR answers all three per locale and per unit, and [MeasureFormat] is how it is asked.
 * `android.icu` rather than a bundled ICU4J: it has been on the platform since API 24 and
 * `minSdk` is 26, so this costs nothing in the APK and is the same data the rest of the
 * system formats against. Robolectric provides it on the JVM, which is why the formatter
 * tests are `@RunWith(RobolectricTestRunner::class)`.
 *
 * ### Two widths, for two jobs
 *
 * - [FormatWidth.SHORT] renders a value with its unit: `20 kg`, `44.09 lb`, `5,2 km`. It is
 *   the width whose pattern carries the conventional symbol, and [unitSymbol] reads the bare
 *   label out of it.
 * - [FormatWidth.NARROW] is tighter still — `3.5h` rather than `3.5 hr` — and is used for
 *   durations alone. Its patterns for the other units are the ones a recipe uses rather than
 *   the ones a barbell does: it writes a pound as `#` and an inch as `″`.
 *
 * [formatAsLength] says why a duration is the one quantity rendered narrow.
 */

/**
 * [value] of [unit], as one string in the formatting locale: `20 kg`, `44.09 lb`, `5,2 km`.
 *
 * The number is rendered by the same [numberFormat] a bare figure goes through, so a stat
 * tile that draws the value and the unit in two type roles and a row that draws them as one
 * string cannot disagree about a decimal separator or a thousands group.
 *
 * @param maxFractionDigits as [formatNumber] — the value is rounded to it, not truncated.
 * @param grouping whether to separate thousands. As [formatNumber]: off for anything a user
 *   can type back into a field, on for a total that is only ever read.
 */
internal fun formatMeasure(
    value: Double,
    unit: MeasureUnit,
    maxFractionDigits: Int,
    grouping: Boolean = false,
): String =
    measureFormat(FormatWidth.SHORT, maxFractionDigits, grouping)
        .format(Measure(value.withoutNegativeZero(maxFractionDigits), unit))

/**
 * A whole [value] of [unit], as one string in the formatting locale: `100 m`, `100 yd`.
 *
 * [formatMeasure] for a count that is an integer to begin with, so it reaches CLDR as one.
 */
internal fun formatWholeMeasure(
    value: Long,
    unit: MeasureUnit,
): String = measureFormat(FormatWidth.SHORT, maxFractionDigits = 0).format(Measure(value, unit))

/**
 * What [unit] is called next to a number, with no number in front of it: `kg`, `lb`, `cm`,
 * `po`.
 *
 * For the call sites that draw the unit as its own element — a stat tile's unit role, a text
 * field's trailing label, a chart axis — where [formatMeasure] would put a figure inside a
 * slot that has none.
 *
 * ### Why this formats a number and then removes it
 *
 * Because the symbol lives in the unit *pattern*, and ICU exposes no way to ask for a pattern
 * on its own. The obvious call — `getUnitDisplayName` — answers a different question: it
 * returns CLDR's display *name*, which is `pounds` and `inches` at short width and, at narrow
 * width, is `metre` on a British phone and `m` on an American one. A column 40dp wide cannot
 * take `metre`, and neither reading is the symbol a barbell is stamped with.
 *
 * So the symbol is taken from where it actually is: format a known number with the unit, then
 * strike the number back out. The number is rendered through the same [numberFormat] the
 * measure is, so what is removed is exactly what was written — including in a locale whose
 * digits are not the Latin ones. What is left is trimmed of the separator CLDR put between
 * them, which in several locales is a non-breaking space and therefore not whitespace as far
 * as [Char.isWhitespace] is concerned.
 */
internal fun unitSymbol(unit: MeasureUnit): String =
    unitSymbols.getOrPut(formattingLocale() to unit) {
        val figure = numberFormat(maxFractionDigits = 0).format(SYMBOL_SAMPLE)

        measureFormat(FormatWidth.SHORT, maxFractionDigits = 0)
            .format(Measure(SYMBOL_SAMPLE, unit))
            .replaceFirst(figure, "")
            .trim { it.isWhitespace() || it.category == CharCategory.SPACE_SEPARATOR }
    }

/**
 * The symbol each unit resolves to, per locale.
 *
 * A symbol is a pure function of the locale and the unit, and `weightSuffix` and its
 * siblings are property getters read on every recomposition of a set-log header, a stat tile
 * and a text field's trailing label — so the lookup above, which builds a formatter and
 * formats twice, runs once per locale rather than once per frame. A plain string, so one map
 * can serve every thread.
 */
private val unitSymbols = ConcurrentHashMap<Pair<Locale, MeasureUnit>, String>()

/**
 * The number [unitSymbol] formats and then removes.
 *
 * One rather than zero: a few locales have a plural category for zero with a pattern of its
 * own, and none has one for a single thing that differs from the general case in its symbol.
 */
private const val SYMBOL_SAMPLE = 1

/**
 * A duration as its fields with their units: `52m`, `1h 5m`, `32h`.
 *
 * Narrow, unlike every other quantity here, and it is the one place the trade goes the other
 * way. These sit in table cells and stat tiles beside a figure — `1 hr, 5 min` is nearly
 * twice the width of `1h 5m` and says nothing more — and a duration's narrow symbols are the
 * ones people actually write, where a pound's are not. See the note above.
 *
 * Whole units only: a duration that needs a fraction has been split into two measures by its
 * caller.
 */
internal fun formatDurationMeasures(vararg measures: Measure): String =
    measureFormat(FormatWidth.NARROW, maxFractionDigits = 0).formatMeasures(*measures)

/**
 * One [MeasureFormat] per configuration per locale per thread.
 *
 * `getInstance` clones the `NumberFormat` it is handed and builds the formatter around the
 * clone on every call, and [formatMeasure] is what every set row and every history row draws
 * its weight and volume through — the per-row cost `NumberFormats.kt` caches its own
 * formatter against (#42). Keyed on the number configuration as well as the locale, because
 * the clone inside is fixed at the moment it is made: a formatter built for two decimals
 * never shows one.
 */
private fun measureFormat(
    width: FormatWidth,
    maxFractionDigits: Int,
    grouping: Boolean = false,
): MeasureFormat {
    val locale = formattingLocale()
    return measureFormats.perThread().getOrPut(MeasureFormatKey(locale, width, maxFractionDigits, grouping)) {
        MeasureFormat.getInstance(locale, width, numberFormat(maxFractionDigits, grouping))
    }
}

private data class MeasureFormatKey(
    val locale: Locale,
    val width: FormatWidth,
    val maxFractionDigits: Int,
    val grouping: Boolean,
)

private val measureFormats = ThreadLocal<MutableMap<MeasureFormatKey, MeasureFormat>>()
