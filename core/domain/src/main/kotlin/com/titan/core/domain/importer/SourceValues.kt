package com.titan.core.domain.importer

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.time.temporal.TemporalQuery
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Reads the values every export writes as text — numbers, elapsed times, timestamps, notes — the
 * same way for every source.
 *
 * These answer "what does this cell say", never "is that allowed": a negative weight or a
 * thirty-hour workout comes back as written, because [ImportPlanner] is where Titan's rules are
 * applied, for every source at once. Each returns null for a blank cell and for text it cannot
 * read, and a parser decides what an unreadable cell costs.
 */
object SourceValues {

    private val PLAIN_NUMBER = Regex("[+-]?(?:\\d+(?:[.,]\\d*)?|[.,]\\d+)")
    private val GROUPED_WITH_COMMAS = Regex("[+-]?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?")
    private val GROUPED_WITH_POINTS = Regex("[+-]?\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?")

    private val CLOCK = Regex("(\\d{1,9}):([0-5]\\d)(?::([0-5]\\d))?")
    private val SPELLED_OUT = Regex(
        "(?:(\\d{1,9})\\s*(?:hours|hour|hrs|hr|h))?\\s*" +
            "(?:(\\d{1,9})\\s*(?:minutes|minute|mins|min|m))?\\s*" +
            "(?:(\\d{1,9})\\s*(?:seconds|second|secs|sec|s))?",
        RegexOption.IGNORE_CASE,
    )

    private val YEAR_FIRST_DATE = Regex(
        "(?<year>\\d{4})(?<separator>[-/])(?<month>\\d{1,2})\\k<separator>(?<day>\\d{1,2})",
    )

    /** A date with its year last, `02/05/17` or `02.05.2017`: which of the first two is the day varies. */
    private val YEAR_LAST_DATE = Regex(
        "(?<first>\\d{1,2})(?<separator>[-/.])(?<second>\\d{1,2})\\k<separator>(?<year>\\d{4}|\\d{2})",
    )

    /**
     * A clock, seconds optional, with whatever word a 12-hour one has before it, or after it —
     * where the word may have a space inside it (`p. m.`).
     */
    private val TIME_OF_DAY = Regex(
        "(?<before>\\S*?) ?(?<hour>\\d{1,2}):(?<minute>\\d{2})(?::(?<second>\\d{2}))? ?(?<after>.*)",
    )

    /** The spaces a formatter puts in a time: ordinary, no-break, and the narrow one before `PM`. */
    private val SPACES = Regex("[\\s\\u00A0\\u202F\\u2009]+")

    /**
     * The words a formatter puts beside a 12-hour time, to the hours each adds once twelve
     * o'clock has been read as zero: none for a morning, twelve for the rest. Traditional
     * Chinese names six parts of the day where English has two. Looked up in lower case and
     * without full stops or spaces, so Canadian English `p.m.` and Latin American Spanish `p. m.`
     * are `pm`.
     */
    private val DAY_PERIODS: Map<String, Int> = mapOf(
        "am" to 0,
        "pm" to HALF_DAY,
        "오전" to 0,
        "오후" to HALF_DAY,
        "凌晨" to 0,
        "清晨" to 0,
        "早上" to 0,
        "上午" to 0,
        "中午" to HALF_DAY,
        "下午" to HALF_DAY,
        "晚上" to HALF_DAY,
    )

    private val OFFSET_DATE_TIME = TemporalQuery(OffsetDateTime::from)
    private val LOCAL_DATE_TIME = TemporalQuery(LocalDateTime::from)

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val MILLIS_PER_SECOND = 1_000L
    private const val HALF_DAY = 12
    private const val MONTHS_PER_YEAR = 12
    private const val LAST_DAY_OF_ANY_MONTH = 31

    /** The century a two-digit year is in: no tracker this reads existed before 2000. */
    private const val TWO_DIGIT_YEAR_BASE = 2000

    /**
     * Wall-clock date and time, `2024-03-02 18:04:11`: a space or a `T` between the two, seconds
     * and a fraction of one optional, and an offset optional too, for the sources that write one.
     * The offset is read as ISO writes it (`Z`, `+01:00`) and as Fitbod does, after a space and
     * without the colon (`2024-03-02 18:04:11 +0000`), because both spellings name the same instant
     * and refusing either would refuse every row of a file that uses it.
     */
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .append(DateTimeFormatter.ISO_LOCAL_DATE)
        .optionalStart()
        .appendLiteral('T')
        .optionalEnd()
        .optionalStart()
        .appendLiteral(' ')
        .optionalEnd()
        .appendValue(ChronoField.HOUR_OF_DAY, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .optionalStart()
        .appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .optionalEnd()
        .optionalEnd()
        .optionalStart()
        .appendLiteral(' ')
        .optionalEnd()
        .optionalStart()
        .appendOffset("+HHMM", "Z")
        .optionalEnd()
        .optionalStart()
        .appendOffsetId()
        .optionalEnd()
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    /**
     * [text] as a number, written with a decimal point or a decimal comma, with or without
     * thousands separators.
     *
     * A single separator is always the decimal one: `62,5` is sixty-two and a half, as a
     * spreadsheet in a decimal-comma locale writes it, and so is `1,234` one and a bit rather
     * than twelve hundred — a weight or a distance with four digits before the point is rarer
     * than a locale that writes a comma. Separators only read as thousands when there is no
     * other reading: `1,234,567`, or `1.234,5` beside a decimal comma.
     */
    fun decimal(text: String?): Double? {
        val trimmed = text?.trim().orEmpty()
        val canonical = when {
            trimmed.isEmpty() -> return null
            PLAIN_NUMBER.matches(trimmed) -> trimmed.replace(',', '.')
            GROUPED_WITH_COMMAS.matches(trimmed) -> trimmed.replace(",", "")
            GROUPED_WITH_POINTS.matches(trimmed) -> trimmed.replace(".", "").replace(',', '.')
            else -> return null
        }
        return canonical.toDoubleOrNull()?.takeIf(Double::isFinite)
    }

    /**
     * Whether [text] is there and is not a number [decimal] reads: a cell that was filled in with
     * something else, as opposed to one left blank.
     */
    fun isUnreadableDecimal(text: String?): Boolean = text != null && decimal(text) == null

    /**
     * [text] as an elapsed time, in whole seconds.
     *
     * Three spellings, because sources use all three: a bare number of seconds (`4419`,
     * `120.0`); a clock (`1:02:03`, or `2:30` read as minutes and seconds, since a stopwatch is
     * what a two-part clock is in a tracker); and units spelled out, largest first (`1h 5m`,
     * `38min`, `2h`, `45 s`).
     */
    fun elapsedSeconds(text: String?): Long? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return decimal(trimmed)?.roundToLong()
            ?: CLOCK.matchEntire(trimmed)?.let(::clockSeconds)
            ?: SPELLED_OUT.matchEntire(trimmed)?.let(::spelledSeconds)
    }

    /** `1:02:03` as hours, minutes and seconds; `2:30` as minutes and seconds. */
    private fun clockSeconds(clock: MatchResult): Long {
        val (first, second, third) = clock.destructured
        return if (third.isEmpty()) {
            first.toLong() * SECONDS_PER_MINUTE + second.toLong()
        } else {
            first.toLong() * SECONDS_PER_HOUR + second.toLong() * SECONDS_PER_MINUTE + third.toLong()
        }
    }

    /** `1h 5m 30s`, each unit optional. */
    private fun spelledSeconds(spelled: MatchResult): Long {
        val (hours, minutes, seconds) = spelled.destructured
        return hours.toLongOrZero() * SECONDS_PER_HOUR + minutes.toLongOrZero() * SECONDS_PER_MINUTE +
            seconds.toLongOrZero()
    }

    /**
     * [text] as an instant, in epoch milliseconds: read in [zone] when it is wall-clock time, as
     * most exports write it, and at its own offset when it carries one.
     *
     * A wall-clock time that a daylight-saving change skipped is moved forward by the length of
     * the gap, and one it repeated is read as the earlier of the two — `java.time`'s own
     * resolution, and the only reading available, since the file does not say which was meant.
     */
    fun localTimestamp(
        text: String?,
        zone: ZoneId,
    ): Long? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            when (val parsed = TIMESTAMP.parseBest(trimmed, OFFSET_DATE_TIME, LOCAL_DATE_TIME)) {
                is OffsetDateTime -> parsed.toInstant().toEpochMilli()
                else -> (parsed as LocalDateTime).atZone(zone).toInstant().toEpochMilli()
            }
        } catch (_: DateTimeException) {
            null
        } catch (_: ArithmeticException) {
            // A year so far out its milliseconds overflow a Long: not a date anybody trained on.
            null
        }
    }

    /**
     * [text] as a calendar date: year first, `2024-03-02` or `2024/03/02`, or — when [order] says
     * which of the other two is the day — year last, `02/03/2024`, `02.03.24`, with a two-digit year
     * in the 2000s. Null for anything else, including a year-last date without an [order], because
     * `03/02/2024` is two different days and the date alone does not say which.
     *
     * A file can say it when its date cannot: see [dayMonthOrder].
     */
    fun calendarDate(
        text: String?,
        order: DayMonthOrder? = null,
    ): LocalDate? {
        val trimmed = text?.trim().orEmpty()
        val yearFirst = YEAR_FIRST_DATE.matchEntire(trimmed)?.groups
        val yearLast = YEAR_LAST_DATE.matchEntire(trimmed)?.groups?.takeIf { order != null }
        fun MatchGroupCollection.part(name: String): Int = checkNotNull(this[name]).value.toInt()
        return try {
            when {
                yearFirst != null -> LocalDate.of(
                    yearFirst.part("year"),
                    yearFirst.part("month"),
                    yearFirst.part("day"),
                )

                yearLast != null -> {
                    val written = checkNotNull(yearLast["year"]).value
                    val year = written.toInt() + if (written.length == 2) TWO_DIGIT_YEAR_BASE else 0
                    val (day, month) = when (order) {
                        DayMonthOrder.MONTH_FIRST -> yearLast.part("second") to yearLast.part("first")
                        else -> yearLast.part("first") to yearLast.part("second")
                    }
                    LocalDate.of(year, month, day)
                }

                else -> null
            }
        } catch (_: DateTimeException) {
            null
        }
    }

    /**
     * Which of the first two numbers is the day in [dates]' year-last dates, as the dates themselves
     * prove it: day first when one of them starts with a number only a day can be (`25/12/2023`),
     * month first when one has it second (`12/25/2023`). Null when none does, or when the dates
     * contradict each other — a file that cannot say, which is not a reason to guess.
     *
     * Some apps write a date the way the phone's region does, so whether `03/02` is March or
     * February depends on whose phone wrote it, and nothing in a single cell says. A whole history
     * nearly always does: a few weeks of workouts reach a thirteenth of the month.
     *
     * A date with a time after it, `25/12/2023 18:30` as [localDateTime] reads, is evidence the same
     * way: only the date, up to the first space, is looked at.
     */
    fun dayMonthOrder(dates: Iterable<String?>): DayMonthOrder? {
        var dayFirst = false
        var monthFirst = false
        dates.forEach { text ->
            val date = text?.trim().orEmpty().substringBefore(' ')
            val groups = YEAR_LAST_DATE.matchEntire(date)?.groups ?: return@forEach
            val first = checkNotNull(groups["first"]).value.toInt()
            val second = checkNotNull(groups["second"]).value.toInt()
            if (first in MONTHS_PER_YEAR + 1..LAST_DAY_OF_ANY_MONTH && second in 1..MONTHS_PER_YEAR) dayFirst = true
            if (second in MONTHS_PER_YEAR + 1..LAST_DAY_OF_ANY_MONTH && first in 1..MONTHS_PER_YEAR) monthFirst = true
        }
        return when {
            dayFirst == monthFirst -> null
            dayFirst -> DayMonthOrder.DAY_FIRST
            else -> DayMonthOrder.MONTH_FIRST
        }
    }

    /**
     * [text] as a time of day, to the minute or, when it has them, the second: a 24-hour clock,
     * `18:04` or `18:04:30`, or a 12-hour one with one word for the part of the day before or after
     * it — `6:04 PM`, `6:04 p. m.`, `오후 6:04`, `下午6:04`. Null for anything else, including a word
     * it does not know.
     *
     * Apps that write the time apart from the date write it as the phone formats a time, so the
     * same export holds `22:56` from one user and `10:56 PM`, with a narrow no-break space before
     * the `PM`, from the next. A spreadsheet writes the seconds too.
     */
    fun timeOfDay(text: String?): LocalTime? {
        val cleaned = text.orEmpty().replace(SPACES, " ").trim()
        val groups = TIME_OF_DAY.matchEntire(cleaned)?.groups ?: return null
        fun group(name: String): String = groups[name]?.value.orEmpty()
        val periods = listOf(group("before"), group("after")).filter(String::isNotEmpty)
        val hour = group("hour").toInt()
        val hourOfDay = when {
            periods.isEmpty() -> hour

            periods.size > 1 || hour !in 1..HALF_DAY -> null

            else -> DAY_PERIODS[periods.single().lowercase(Locale.ROOT).filterNot { it == '.' || it == ' ' }]
                ?.let { shift -> hour % HALF_DAY + shift }
        }
        return hourOfDay?.let {
            try {
                LocalTime.of(it, group("minute").toInt(), group("second").ifEmpty { "0" }.toInt())
            } catch (_: DateTimeException) {
                null
            }
        }
    }

    /**
     * [date], read as [calendarDate] reads it, at the [time] of day [timeOfDay] reads, in [zone],
     * in epoch milliseconds; null when either does not read.
     *
     * For the exports that keep a workout's day and its start in two columns. A time a
     * daylight-saving change skipped or repeated resolves as [localTimestamp] resolves it.
     */
    fun localDateAt(
        date: String?,
        time: String?,
        zone: ZoneId,
        order: DayMonthOrder? = null,
    ): Long? {
        val day = calendarDate(date, order) ?: return null
        val clock = timeOfDay(time) ?: return null
        return day.atTime(clock).atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * [text], a date and a time of day in one cell, as an instant in epoch milliseconds: a timestamp
     * [localTimestamp] reads, or a date [calendarDate] reads, a space, and a time [timeOfDay] reads —
     * `02/11/2026 18:30`, `11/2/2026 6:30 PM`, `2026-11-02 18:30:00`. Null for a date on its own, and
     * for anything else.
     *
     * A file that has been through a spreadsheet comes back with its dates and times in the
     * spreadsheet's locale rather than as they were written, and a parser reading a file a user
     * made by hand sees every spelling of both. The second form is what that leaves behind, read
     * in [zone], the date in [order] when its year is last, and a time a daylight-saving change
     * skipped or repeated as [localTimestamp] resolves it.
     */
    fun localDateTime(
        text: String?,
        zone: ZoneId,
        order: DayMonthOrder? = null,
    ): Long? {
        localTimestamp(text, zone)?.let { return it }
        val trimmed = text?.trim().orEmpty()
        val space = trimmed.indexOf(' ')
        if (space < 0) return null
        return localDateAt(trimmed.substring(0, space), trimmed.substring(space + 1), zone, order)
    }

    /**
     * [text], a calendar date with no time of day, as the instant a workout logged on it is taken
     * to have started: noon on that day in [zone], in epoch milliseconds. The date is read as
     * [calendarDate] reads it: year first, or year last when [order] says which is the day.
     *
     * Some apps record the day of a workout and not its time, and a workout needs a start. It has
     * to be the same instant every time the file is read, or importing the file again would write
     * every workout twice, so it cannot depend on when the import runs. Noon is the time chosen
     * because it keeps the workout on its day wherever the day is counted: the history shows it
     * in [zone], and the streak, the weekly volume and the quests cut their weeks in UTC, where
     * noon in any zone from eleven hours behind UTC to twelve ahead is still the same date.
     * Midnight would put a Monday workout east of Greenwich into the week before. The zones
     * further east than that — New Zealand's summer time, Tonga, Samoa, Kiribati's Line Islands —
     * are the exception no single time of day avoids: noon there is the day before in UTC. No
     * zone changes its clocks at noon either, so the time is never skipped or repeated.
     *
     * The cost is a workout dated today and imported in the morning: it starts in the future,
     * which [ImportPlanner] refuses and counts, and an import run again after noon adds it.
     */
    fun localDateAtNoon(
        text: String?,
        zone: ZoneId,
        order: DayMonthOrder? = null,
    ): Long? = calendarDate(text, order)?.atTime(LocalTime.NOON)?.atZone(zone)?.toInstant()?.toEpochMilli()

    /**
     * [text], a whole number of seconds since the start of 1970 in UTC, as an instant in epoch
     * milliseconds; null when it is blank, not a whole number, not positive, or too large to be
     * one.
     *
     * Some apps export a time as the integer their database stores rather than as a date, and
     * write `0` for a time they never recorded. Read as written, that would be a workout on the
     * first day of 1970, so zero and below read as no time at all; anything later is passed on as
     * it is, for [ImportPlanner] to judge.
     */
    fun epochSeconds(text: String?): Long? {
        val seconds = text?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return if (seconds > Long.MAX_VALUE / MILLIS_PER_SECOND) null else seconds * MILLIS_PER_SECOND
    }

    /**
     * [text] as a note, with every line break as `\n`, trimmed, or null when nothing is left.
     *
     * Two exporters break lines in ways the rest of the app does not. Some write a note's line
     * break as the two characters `\n` rather than quoting a real one — Strong on iOS and Hevy's
     * app both do — so those are restored; and a quoted CRLF or lone CR, from a spreadsheet or
     * a website's export, is made the `\n` everything else is stored with. A literal backslash
     * followed by an `n` in a note is rarer than either exporter's notes, so it is not kept.
     */
    fun note(text: String?): String? =
        text?.replace("\\n", "\n")
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun String.toLongOrZero(): Long = if (isEmpty()) 0L else toLong()
}

/**
 * Which of a year-last date's first two numbers is the day: [SourceValues.calendarDate] needs to be
 * told, and [SourceValues.dayMonthOrder] is how a file tells it.
 */
enum class DayMonthOrder {
    /** `25/12/2023`, as most of the world writes it. */
    DAY_FIRST,

    /** `12/25/2023`, as the United States writes it. */
    MONTH_FIRST,
}
