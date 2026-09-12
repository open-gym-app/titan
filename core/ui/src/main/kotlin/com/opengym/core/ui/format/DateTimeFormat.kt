package com.opengym.core.ui.format

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import android.icu.text.DateFormat as IcuDateFormat
import android.icu.util.TimeZone as IcuTimeZone

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
 * ### Where `java.time` stops, and ICU starts
 *
 * `java.time` offers four localized date *styles* — full, long, medium, short — and nothing
 * in between. Three of the shapes below are in between: a weekday with a medium date, a month
 * with a year and no day, a day with a month and no year. Assembling those from parts is what
 * this file used to do, and it is what put the weekday in front in every locale, including the
 * ones that write it behind.
 *
 * A **skeleton** is the request CLDR actually answers: `EEEdMMMy` asks for those four fields
 * and lets the locale decide their order, their separators and how each is abbreviated —
 * `Tue, Apr 28, 2026`, `mar. 28 avr. 2026`, `ter., 28 de abr. de 2026`. Only
 * [android.icu.text.DateFormat.getInstanceForSkeleton] can do that lookup; `java.time` has no
 * equivalent. So the three joins go through ICU and everything else stays on `java.time`,
 * which already answers per locale for the fields it does have. Both draw their digits in the
 * locale's own numbering system, so the two halves of a screen cannot disagree about numerals.
 *
 * ### The zone is the device's, and the instant is UTC
 *
 * A session is stamped in epoch millis UTC and read back in whatever zone the phone is in.
 * That is the right answer for the case that actually happens — a lifter flies, and a workout
 * logged at 18:00 in Paris should not read as 18:00 in New York — and it is why [ZoneId] is a
 * parameter rather than a constant: a test that could not pin it would be asserting the
 * machine it runs on.
 */

/**
 * The day a session happened, with its weekday: `Tue, Apr 28, 2026`, `mar. 28 avr. 2026`,
 * `ter., 28 de abr. de 2026`.
 *
 * Where the weekday goes is the locale's answer rather than this file's — see the note above
 * on skeletons. The weekday is *there* because it is the half a lifter actually reads: "was
 * that the Monday session or the Thursday one" is a question a training week is organised
 * around, and `Apr 28` alone does not answer it without counting.
 *
 * The year is always shown, even for a session logged this morning. Dropping it would mean a
 * second skeleton and a second shape to keep in step, which is a worse trade than four extra
 * glyphs on a line that is already mono and quiet.
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
 * `April 28, 2026` beside a list saying `Tue, Apr 28, 2026` would be two renderings of one
 * fact.
 */
fun formatSessionDate(
    date: LocalDate,
    locale: Locale = formattingLocale(),
): String = date.formatWithSkeleton(WEEKDAY_DATE_SKELETON, locale)

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
): String = localizedTimeFormatter(locale).format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/*
 * The three below are the calendar's vocabulary: a month to head it with, a number for each
 * cell, and the seven letters across the top. Both of the ones that draw digits draw them in
 * the locale's own numerals — ICU does it for the heading, and [numberFormatter]'s explicit
 * [DecimalStyle.of] does it for the cell. `28.toString()` is ASCII whatever the locale, and so
 * is a `DateTimeFormatter` built without the decimal style, since `ofPattern(pattern, locale)`
 * uses the locale to pick *text* and leaves [DecimalStyle.STANDARD] in place.
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
 * The order and the separator are the locale's: `April 2026`, `avril 2026`, `abril de 2026`.
 * Portuguese is the one that shows why this is a skeleton lookup rather than a join — the
 * `de` between the two fields is not a space, and nothing about the two fields on their own
 * predicts it.
 */
fun formatMonthAndYear(
    month: YearMonth,
    locale: Locale = formattingLocale(),
): String = month.atDay(1).formatWithSkeleton(MONTH_YEAR_SKELETON, locale)

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

/**
 * The day a table's row is dated by: `28 Apr`, `28 avr.`, `4月28日`.
 *
 * The shortest form that still says *which* session — [formatSessionDate] is four words wide
 * and does not fit a 60dp column, and [formatMonthAbbreviation] alone puts three rows of the
 * same month under one label. The year is dropped because the table is bounded by a timeframe
 * the screen states above it.
 *
 * A skeleton without a year field rather than a full date with the year cut back out of it:
 * asking CLDR for the two fields is what gets `Apr 28` in the United States and `28 avr.` in
 * France, where a string built day-first would have been wrong in one of them.
 */
fun formatDayAndMonth(
    date: LocalDate,
    locale: Locale = formattingLocale(),
): String = date.formatWithSkeleton(DAY_MONTH_SKELETON, locale)

/** The same day, for a caller holding the instant a session was closed out at. */
fun formatDayAndMonth(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = formattingLocale(),
): String = formatDayAndMonth(localDateOf(epochMillis, zone), locale)

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

/**
 * A wall-clock time with no date behind it: `18:00`, `6:00 PM`.
 *
 * The twin of [formatTimeOfDay] for a caller holding a [LocalTime] rather than an instant — a
 * reminder is "18:00 on Mondays", which is a time that has not happened and may happen many
 * times. Both go through the same localized SHORT style, so the reminder row and a session's
 * start time are written the same way on the same phone.
 */
fun formatTimeOfDay(
    time: LocalTime,
    locale: Locale = formattingLocale(),
): String = localizedTimeFormatter(locale).format(time)

/**
 * Whether [locale] writes the time on a 24-hour clock.
 *
 * Read off the localized SHORT pattern — the same pattern [formatTimeOfDay] renders through —
 * so a control that *sets* a time and the row that reads it back cannot disagree about whether
 * there is a meridiem.
 *
 * Deliberately not `android.text.format.DateFormat.is24HourFormat(context)`, which reads the
 * device's own 12/24 setting. That setting would be the better answer if the rest of the app
 * followed it, and the rest of the app does not: every time in it goes through
 * `DateTimeFormatter.ofLocalizedTime`, which is locale-derived. One source, consistently
 * wrong-in-the-same-way, beats two that disagree on the same screen — and unlike the platform
 * call this needs no `Context`, so a preview and a screenshot can render it.
 */
internal fun is24HourClock(locale: Locale = formattingLocale()): Boolean {
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        // Null date style: the time half of the pattern on its own.
        null,
        FormatStyle.SHORT,
        IsoChronology.INSTANCE,
        locale,
    )
    // `a` is the meridiem field and `h`/`K` are the two 12-hour hour fields. A pattern with
    // none of them counts from 0 or 1 to 23.
    return TWELVE_HOUR_FIELDS.none(pattern::contains)
}

/** The pattern letters that mean a clock face runs to twelve. See [is24HourClock]. */
private val TWELVE_HOUR_FIELDS = listOf('a', 'h', 'K')

/** The first day of the week [locale] writes a calendar from — Monday, Sunday or Saturday. */
fun firstDayOfWeek(locale: Locale = formattingLocale()): DayOfWeek = WeekFields.of(locale).firstDayOfWeek

/** The day this instant falls on, in [zone]. What groups sessions into calendar days. */
fun localDateOf(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

private const val DAYS_PER_WEEK = 7

/**
 * [this] through the localized pattern CLDR picks for [skeleton] — the fields asked for, in
 * the order and with the separators [locale] writes them in.
 *
 * The day is handed over as an instant at UTC midnight with the formatter pinned to the same
 * zone, which is the whole of the conversion: ICU dates are moments and a [LocalDate] is not,
 * so a zone has to be chosen, and the only one that cannot shift the day is the one both
 * sides agree on.
 */
private fun LocalDate.formatWithSkeleton(
    skeleton: String,
    locale: Locale,
): String = skeletonFormatter(skeleton, locale).format(Date.from(atStartOfDay(ZoneOffset.UTC).toInstant()))

/**
 * One ICU formatter per skeleton per locale per thread.
 *
 * `getInstanceForSkeleton` resolves the skeleton against the locale's pattern generator and
 * builds a `SimpleDateFormat` from the result on every call, and [formatSessionDate] is the
 * first thing a history row draws — the same per-row cost [numberFormatter] is cached against
 * (#42). Per thread rather than shared because, unlike a [DateTimeFormatter], an ICU
 * [IcuDateFormat] is mutable and not safe to format through from two threads at once.
 */
private val skeletonFormatters = ThreadLocal<MutableMap<Pair<String, Locale>, IcuDateFormat>>()

private fun skeletonFormatter(
    skeleton: String,
    locale: Locale,
): IcuDateFormat =
    skeletonFormatters.perThread().getOrPut(skeleton to locale) {
        IcuDateFormat.getInstanceForSkeleton(skeleton, locale).apply { timeZone = IcuTimeZone.GMT_ZONE }
    }

/*
 * The fields each shape asks CLDR for. A skeleton names fields and widths and says nothing
 * about order or punctuation, which is the point — see the note at the top of the file.
 */

/** Abbreviated weekday, day, abbreviated month, year. [formatSessionDate]. */
private const val WEEKDAY_DATE_SKELETON = "EEEdMMMy"

/** Standalone full month and year, no day. [formatMonthAndYear]. */
private const val MONTH_YEAR_SKELETON = "LLLLy"

/** Day and abbreviated month, no year. [formatDayAndMonth]. */
private const val DAY_MONTH_SKELETON = "dMMM"

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
): DateTimeFormatter =
    numberFormatters.getOrPut(pattern to locale) {
        DateTimeFormatter.ofPattern(pattern, locale).withDecimalStyle(DecimalStyle.of(locale))
    }

private fun localizedTimeFormatter(locale: Locale): DateTimeFormatter =
    localizedTimeFormatters.getOrPut(locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    }

/*
 * Built once per locale. `ofPattern` parses its pattern and `ofLocalizedTime` runs a
 * `DateTimeFormatterBuilder` on every call, and a history row goes through several of these
 * to draw its rail and describe itself — the kind of per-row cost a `LazyColumn` pays on the
 * frame a row scrolls in (#42). A `DateTimeFormatter` is immutable, so one instance serves
 * every thread; the ICU ones in [skeletonFormatters] are not, and so are held per thread.
 */
private val numberFormatters = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()
private val localizedTimeFormatters = ConcurrentHashMap<Locale, DateTimeFormatter>()

/*
 * A single-field pattern, so there is no field order to get wrong in another locale — the
 * only thing the locale decides here is which digits are drawn.
 */
private const val DAY_PATTERN = "d"
