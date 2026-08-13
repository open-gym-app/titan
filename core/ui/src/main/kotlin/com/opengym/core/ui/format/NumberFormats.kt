package com.opengym.core.ui.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale

/*
 * The single seam between a number and the text a user reads or types.
 *
 * Every formatter in this package goes through [formatNumber] and every parser through
 * [parseNumber], which is what makes "the decimal separator follows the device locale" a
 * property of the package rather than of whichever call site remembered. `String.format`
 * with a `%f` gets the separator right and the *grouping* separator wrong; plain
 * concatenation gets both wrong; and neither has an inverse, which is the half that
 * matters once a value can be typed back in.
 */

/**
 * [value] as text in the device's formatting locale.
 *
 * Trailing zeros never survive: `minimumFractionDigits` is 0, so 100 kg is `100` and not
 * `100.0`. A whole number is what a person would write, and a forced decimal reads as an
 * instrument reading rather than as a lift.
 *
 * @param maxFractionDigits the most decimals to show. The value is rounded to it, not
 *   truncated — see [RoundingMode.HALF_UP]. A weight must never read *low*: a 2.5 kg plate
 *   that displays as 2.4 is a plate the user will go looking for on the rack.
 * @param grouping whether to separate thousands. Off for a value that can be typed back
 *   into a field, on for a total that is only ever read — see [formatVolumeValue].
 */
internal fun formatNumber(value: Double, maxFractionDigits: Int, grouping: Boolean = false): String {
    val format = NumberFormat.getInstance(formattingLocale()).apply {
        maximumFractionDigits = maxFractionDigits
        minimumFractionDigits = 0
        isGroupingUsed = grouping
        roundingMode = RoundingMode.HALF_UP
    }
    val formatted = format.format(value)

    // A value that rounds to nothing at the display precision but is not quite zero prints
    // with a minus sign in front of it — `-0 kg` for a weight four decimals below the
    // rounding step. So does -0.0 itself, which `value < 0` does not catch. Both are a
    // minus sign on a quantity the user reads as nothing, so both become the plain zero.
    return if (value <= ZERO && formatted == format.format(NEGATIVE_ZERO)) format.format(ZERO) else formatted
}

/**
 * [this] rounded to the decimals [formatNumber] would show it to.
 *
 * What lets a parser land on a value its own display can hold: a field that shows whole
 * metres and is handed `25.5` stores 26 m, rather than storing 25.5 m, rendering it as `26`
 * and reading 26 m back the next time it is opened. Keeping precision the format then rounds
 * away is not extra fidelity, it is a step of drift per edit — see [parseDistanceCentimetres].
 *
 * Rounded through the shortest decimal that round-trips ([Double.toString]) rather than
 * through the raw binary value, because that is what [java.text.DecimalFormat] rounds through
 * too: `0.145` has to land on the same side here as it does on screen.
 */
internal fun Double.roundToFractionDigits(fractionDigits: Int): Double =
    BigDecimal(toString()).setScale(fractionDigits, RoundingMode.HALF_UP).toDouble()

/**
 * [text] as a number, or null when it is not one.
 *
 * Null means *malformed* and nothing else. Range is deliberately not judged here: a weight
 * of -5 kg and a weight of 40 tonnes are both numbers, and refusing them is
 * `SetValidator`'s job, which says *why* — "weight must be within 0 kg..10000 kg" is a
 * message a user can act on, and "not a number" about a number is not. See the note on
 * [parseWeightMilligrams].
 *
 * Two passes, in this order:
 *
 * 1. **Strict ASCII, with either separator.** A phone keyboard is not required to agree
 *    with the locale — a French user on an English keyboard types `22.5` and means 22.5,
 *    and refusing it would be the app being right about something nobody asked. Running it
 *    first is also what makes `22.5` mean 22.5 in a locale where `.` groups thousands.
 * 2. **The locale's own numerals and grouping**, which is what a field is *seeded* with:
 *    [formatNumber] renders through the default locale, so in Persian, Arabic-Egyptian,
 *    Bengali and a dozen others a stored 20 kg arrives as `۲۰` — digits [String.toDouble]
 *    has never heard of. Without this pass a dialog would open in its own error state, on
 *    a value the app itself had just written.
 */
internal fun parseNumber(text: String): Double? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null

    val parsed = trimmed.parseAsciiDecimal() ?: trimmed.parseLocalizedDecimal()

    // NaN and the infinities are what `Double` does with input no gym produces, and both
    // survive a multiplication into milligrams as a value `roundToLong` turns into 0 or
    // `Long.MAX_VALUE` — a silent wrong answer where the field should have shown an error.
    return parsed?.takeIf { it.isFinite() }
}

/**
 * A plain decimal, with `.` or `,` between the whole and fractional parts.
 *
 * Anchored and digits-only on purpose: [String.toDoubleOrNull] alone also accepts `1e5`,
 * `0x1p3`, `NaN` and the trailing `1d`/`1f` type suffixes, none of which anybody typed into
 * a weight field on a decimal keypad.
 */
private fun String.parseAsciiDecimal(): Double? {
    if (!ASCII_DECIMAL.matches(this)) return null

    val separator = indexOfFirst { it == '.' || it == ',' }
    if (separator < 0) return toDoubleOrNull()

    return if (isGroupingSeparatorAt(separator)) {
        removeRange(separator, separator + 1).toDoubleOrNull()
    } else {
        replace(',', '.').toDoubleOrNull()
    }
}

/**
 * Whether the separator at [index] is grouping thousands rather than opening a fraction.
 *
 * `4,820` is 4820 to a British reader and 4.82 to a French one, and no amount of looking at
 * the string settles it — the two are the same string. So it is settled the only way it can
 * be: **the locale decides, and only for a string that could actually be a grouped number**.
 * All three of these have to hold, and the last two are what keep a decimal from being read
 * as a thousand:
 *
 * 1. Exactly three digits behind the separator — the one shape that is genuinely ambiguous.
 * 2. The separator is the character this locale groups with. Not merely "not the decimal
 *    separator": French groups with a narrow no-break space, so `1.125` on a French phone is
 *    not a grouped 1125 in any reading, it is a bar weight typed on an ASCII keyboard.
 * 3. The digits in front form a leading group — see [hasLeadingGroupBefore]. `0,125` is a
 *    fraction in every locale on earth; nobody writes 125 as `0,125`.
 *
 * Everything else is read as a decimal point regardless of locale, which is what keeps the
 * generous half of this parser: `22.5` on an English keyboard means 22.5 in France, and
 * `2,5` on a French keyboard means 2.5 in Britain.
 */
private fun String.isGroupingSeparatorAt(index: Int): Boolean {
    val isThousandsShaped = length - index - 1 == GROUP_SIZE
    return isThousandsShaped && this[index] == localeGroupingSeparator() && hasLeadingGroupBefore(index)
}

/**
 * Whether the digits before [index] are a group a grouped number can start with: one to three
 * of them, and not opening with a zero.
 *
 * `1234,567` fails the count — a grouped 1234567 is written `1,234,567`, and the parser only
 * ever sees one separator anyway. `0,125` fails the zero, which is the one that matters: it is
 * the shape a user types for an eighth of a kilo, and reading it as 125 kg is a thousandfold
 * error the field has no way to show as wrong.
 */
private fun String.hasLeadingGroupBefore(index: Int): Boolean {
    val start = if (first() == '+' || first() == '-') 1 else 0
    return index - start in 1..GROUP_SIZE && this[start] != '0'
}

private fun localeGroupingSeparator(): Char =
    DecimalFormatSymbols.getInstance(formattingLocale()).groupingSeparator

/**
 * [NumberFormat] parses leniently — it reads `12abc` as 12 and stops — so the position it
 * ends at is checked against the length. A partial parse is a typo, not a value.
 */
private fun String.parseLocalizedDecimal(): Double? {
    val position = ParsePosition(0)
    val parsed = NumberFormat.getInstance(formattingLocale()).parse(this, position)
    return parsed?.toDouble()?.takeIf { position.index == length }
}

private val ASCII_DECIMAL = Regex("""[+-]?(\d+([.,]\d*)?|[.,]\d+)""")

/** How many digits a thousands group holds, in every locale this app renders in. */
private const val GROUP_SIZE = 3

private const val ZERO = 0.0

/** The value whose rendering — a bare minus sign and a zero — [formatNumber] suppresses. */
private const val NEGATIVE_ZERO = -0.0

/**
 * The locale numbers are rendered in, which is not always the one the UI is translated to:
 * a user may read the app in English and still want their own digits and separators.
 *
 * `internal` rather than private because [formatSessionDate] renders through it too. A date is
 * a number the user reads, so it follows the same locale as the weights beside it — two locale
 * sources in one header is how a screen ends up showing Arabic digits in the weight and Latin
 * ones in the date.
 */
internal fun formattingLocale(): Locale = Locale.getDefault(Locale.Category.FORMAT)
