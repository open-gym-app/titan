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

    private val OFFSET_DATE_TIME = TemporalQuery(OffsetDateTime::from)
    private val LOCAL_DATE_TIME = TemporalQuery(LocalDateTime::from)

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L

    /**
     * Wall-clock date and time, `2024-03-02 18:04:11`: a space or a `T` between the two, seconds
     * and a fraction of one optional, and an offset (`Z`, `+01:00`) optional too, for the sources
     * that write one.
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
     * [text], a calendar date with no time of day, as the instant a workout logged on it is taken
     * to have started: noon on that day in [zone], in epoch milliseconds. The year comes first,
     * `2024-03-02` or `2024/03/02`; anything else is null, including a day-first or month-first
     * date, because `03/02/2024` is two different days and the file does not say which.
     *
     * Some apps record the day of a workout and not its time, and a workout needs a start. It has
     * to be the same instant every time the file is read, or importing the file again would write
     * every workout twice, so it cannot depend on when the import runs. Noon is the time chosen
     * because it keeps the workout on its day wherever the day is counted: the history shows it
     * in [zone], and the streak, the weekly volume and the quests cut their weeks in UTC, where
     * noon in any zone within twelve hours of UTC is still the same date. Midnight would put a
     * Monday workout east of Greenwich into the week before. No zone changes its clocks at noon
     * either, so the time is never skipped or repeated.
     *
     * The cost is a workout dated today and imported in the morning: it starts in the future,
     * which [ImportPlanner] refuses and counts, and an import run again after noon adds it.
     */
    fun localDateAtNoon(
        text: String?,
        zone: ZoneId,
    ): Long? {
        val parts = YEAR_FIRST_DATE.matchEntire(text?.trim().orEmpty())?.groups ?: return null
        fun part(name: String): Int = checkNotNull(parts[name]).value.toInt()
        return try {
            LocalDate.of(part("year"), part("month"), part("day"))
                .atTime(LocalTime.NOON)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
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
