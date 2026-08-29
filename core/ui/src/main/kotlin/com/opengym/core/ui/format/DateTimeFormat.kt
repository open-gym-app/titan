package com.opengym.core.ui.format

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/*
 * Dates and clock times, from the epoch milliseconds every `*_at` column stores to what a
 * user reads: `Mon 28 Apr 2026`, `10:14`.
 *
 * The fourth quantity in the table in CONTRIBUTING.md § Using the design system, and here for
 * the same reason as the other three: a session header that built its own `SimpleDateFormat`
 * would pick a pattern, and the pattern would be somebody's local one. `28/04/2026` is the
 * fourth of the twenty-eighth to an American reader before it is anything else, which is the
 * date equivalent of the `"$value kg"` problem [formatNumber] exists to prevent.
 *
 * ### Why `java.time` and not `android.text.format.DateUtils`
 *
 * `DateUtils` is the platform's answer and formats against the *device configuration* rather
 * than against a locale it is handed, which makes it untestable outside an instrumented run
 * and unable to follow [formattingLocale]. Everything here is a pure function of its
 * arguments, so `DateTimeFormatTest` can pin a zone and a locale and assert the string.
 * `minSdk` is 26, so `java.time` is on the platform and needs no desugaring.
 *
 * ### The zone is the device's, and the instant is UTC
 *
 * A session is stamped in epoch millis UTC and read back in whatever zone the phone is in.
 * That is the right answer for the case that actually happens — a lifter flies, and a workout
 * logged at 18:00 in Paris should not read as 18:00 in New York — and it is why [ZoneId] is a
 * parameter rather than a constant: a test that could not pin it would be asserting the
 * machine it runs on.
 *
 * ### What is not localised, and why it is left that way
 *
 * [formatSessionDate] puts the weekday in front of the date, which is the order most western
 * locales write it in and not the order CJK ones do. Splitting it correctly means a localised
 * skeleton per locale, which is the localisation pass's job (#40) — the same call it makes
 * for [formatAsEstimate]'s `h`/`m` and [weightSuffix]'s `kg`/`lb`. The date *itself* is
 * localised here; only the join is provisional.
 */

/**
 * The day a session happened, with the weekday in front of it: `Mon 28 Apr 2026`,
 * `Mon, 28. Apr. 2026`, `月 2026/04/28`.
 *
 * The weekday is there because it is the half a lifter actually reads. "Was that the Monday
 * session or the Thursday one" is a question a training week is organised around, and
 * `28 Apr` alone does not answer it without counting.
 *
 * The year is always shown, even for a session logged this morning. Dropping it would mean a
 * second localised pattern with the year fields cut out of it, and the pattern surgery that
 * takes is a worse trade than four extra glyphs on a line that is already mono and quiet.
 *
 * @param zone the zone to read the instant in. The device's, except in a test.
 * @param locale the locale to render in. [formattingLocale] by default, so the date follows
 *   the same setting as every weight and duration beside it.
 */
fun formatSessionDate(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = formattingLocale(),
): String = formatSessionDate(localDateOf(epochMillis, zone), locale)

/**
 * The same date, for a caller that already has a [LocalDate] rather than an instant — a
 * calendar cell, which is a day and never a moment.
 *
 * The overload exists so both spell the day the same way: a calendar announcing
 * `28 April 2026` beside a list saying `Mon 28 Apr 2026` would be two renderings of one fact.
 */
fun formatSessionDate(
    date: LocalDate,
    locale: Locale = formattingLocale(),
): String {
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    val rest = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

    return "$weekday $rest"
}

/**
 * The time of day something happened, as the device's clock writes it: `10:14`, `10:14 AM`.
 *
 * [FormatStyle.SHORT], so a 24-hour locale gets no seconds and a 12-hour one gets its
 * meridiem. Seconds are deliberately absent: this labels *when* a session started, and the
 * length of it is a duration, which [formatAsClock] renders and is the only thing on the
 * screen precise to the second.
 */
fun formatTimeOfDay(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = formattingLocale(),
): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/*
 * The three below are the calendar's vocabulary: a month to head it with, a number for each
 * cell, and the seven letters across the top. The two that draw digits go through a formatter
 * carrying [DecimalStyle.of], which is what keeps a Persian or a Bengali phone drawing its own
 * numerals — `28.toString()` is ASCII whatever the locale, and so is a `DateTimeFormatter`
 * built without the decimal style, since `ofPattern(pattern, locale)` uses the locale to pick
 * *text* and leaves [DecimalStyle.STANDARD] in place.
 *
 * That matters beside the counts, not on its own: `pluralStringResource` formats its argument
 * through `DecimalFormatSymbols`, so a row whose set count is in Bengali digits and whose date
 * rail is in ASCII ones is two numbering systems on one line.
 */

/**
 * The month a calendar page is showing: `April 2026`, `avril 2026`.
 *
 * Standalone month name rather than the genitive form some locales use inside a full date,
 * which is what `LLLL` asks for and what a heading needs — Russian's `апрель` here rather
 * than the `апреля` of "the 28th of April".
 *
 * The join is `month year` in every locale, which is the same provisional simplification
 * [formatSessionDate] makes about the weekday and is left for the localization pass (#40)
 * for the same reason.
 */
fun formatMonthAndYear(
    month: YearMonth,
    locale: Locale = formattingLocale(),
): String {
    val name = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
    val year = numberFormatter(YEAR_PATTERN, locale).format(month)

    return "$name $year"
}

/**
 * The month a chart's axis marks a stretch with: `Apr`, `avr.`, `4月`.
 *
 * Short rather than full, and without a year: the axis under a trend is a *hint* about where
 * in the calendar the marks sit, not a scale to read dates off — see `ChartAxisLabels`, which
 * draws three of these across a card at 10sp. [formatMonthAndYear] is the heading form, for a
 * calendar page that is genuinely about one month.
 *
 * Standalone rather than the genitive form some locales use inside a full date, for the reason
 * [formatMonthAndYear] uses it: this is a label, not part of "the 28th of April".
 */
fun formatMonthAbbreviation(
    date: LocalDate,
    locale: Locale = formattingLocale(),
): String = date.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale)

/** The number in one calendar cell, and on a session row's date rail: `28`, `٢٨`. */
fun formatDayOfMonth(
    date: LocalDate,
    locale: Locale = formattingLocale(),
): String = numberFormatter(DAY_PATTERN, locale).format(date)

/**
 * The short weekday a session row's rail carries under its date: `Mon`, `lun.`.
 *
 * Short rather than narrow, unlike [weekdayInitials]: the rail has a whole 44dp column and
 * one letter under a date reads as a stray glyph, where a calendar's header has seven cells
 * across 328dp and anything longer than a letter would not fit.
 */
fun formatWeekday(
    date: LocalDate,
    locale: Locale = formattingLocale(),
): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

/**
 * The seven letters across the top of a calendar, starting on the locale's own first day.
 *
 * The wireframes draw `M T W T F S S`, which is right in Britain and wrong in the United
 * States and in most of the Middle East. [WeekFields] answers it per locale, and the grid
 * below the header is laid out from the same value — see `TrainingCalendar`, which takes the
 * first day rather than assuming it.
 */
fun weekdayInitials(
    firstDayOfWeek: DayOfWeek,
    locale: Locale = formattingLocale(),
): List<String> =
    (0 until DAYS_PER_WEEK).map { offset ->
        weekdayInitial(firstDayOfWeek.plus(offset.toLong()), locale)
    }

/**
 * One weekday's letter.
 *
 * What [weekdayInitials] is built from, and what a header labels its columns with when it has
 * the days themselves rather than a starting weekday and a promise of seven — see `WeekStrip`,
 * where a letter derived from a position in a list is a letter that lands over the wrong cell
 * the moment the list is not the length that was assumed.
 */
fun weekdayInitial(
    day: DayOfWeek,
    locale: Locale = formattingLocale(),
): String = day.getDisplayName(TextStyle.NARROW, locale)

/** The first day of the week [locale] writes a calendar from — Monday, Sunday or Saturday. */
fun firstDayOfWeek(locale: Locale = formattingLocale()): DayOfWeek = WeekFields.of(locale).firstDayOfWeek

/** The day this instant falls on, in [zone]. What groups sessions into calendar days. */
fun localDateOf(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

private const val DAYS_PER_WEEK = 7

/**
 * A formatter for a bare number, in the locale's own numerals.
 *
 * [DateTimeFormatter.ofPattern] takes a locale but installs [DecimalStyle.STANDARD] regardless
 * — the locale it is handed decides month and weekday *text*, not digits. The explicit
 * [DecimalStyle.of] is the only thing that makes a Bengali or an Eastern Arabic phone draw
 * `২৮` rather than `28`.
 */
private fun numberFormatter(
    pattern: String,
    locale: Locale,
): DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, locale).withDecimalStyle(DecimalStyle.of(locale))

/*
 * Single-field patterns, so there is no field order to get wrong in another locale — the
 * only thing the locale decides here is which digits are drawn.
 */
private const val YEAR_PATTERN = "y"
private const val DAY_PATTERN = "d"
