package com.titan.core.domain.importer.hevy

import com.titan.core.domain.importer.SourceValues
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Reads the dates Hevy writes in its exports, in whichever language the user had chosen.
 *
 * Hevy does not write a machine format. Its app writes `d MMM yyyy, HH:mm` with the month in the
 * app's language — `26 Jan 2024, 18:03`, `26 janv. 2024, 18:03`, `26 янв. 2024 г., 18:03`,
 * `26 1月 2024, 18:03` — and its website writes the browser's own medium date and short time —
 * `Jan 26, 2024, 6:03 PM`, `26.01.2024, 18:03`, `2024/01/26 18:03`, `2024. 1. 26. 오후 6:03`,
 * `2024年1月26日 下午6:03`. Both are wall-clock time to the minute, with no zone.
 *
 * So rather than one pattern per language, this reads the few shapes those dates come in and
 * looks the words up: a month in [MONTHS], from any of the languages, and a word beside a
 * 12-hour time in [DAY_PERIODS]. A month word is never two different months in any pair of
 * languages, so nothing has to know which language a file is in, and a file in a language with
 * the same shapes and different words fails on the word, not on a guess. An ISO date, as a
 * spreadsheet re-saves one, is read by [SourceValues.localTimestamp].
 *
 * A date with its year last and slashes between numbers (`03/12/2023`) is refused: Hevy never
 * writes one, and whether it is the third of December or the twelfth of March is not something
 * the file says.
 */
internal object HevyTimestamps {

    /**
     * [text] as an instant, in epoch milliseconds, read as wall-clock time in [zone]; null when
     * it is blank or is not a date in any shape Hevy writes.
     *
     * A time a daylight-saving change skipped or repeated resolves as
     * [SourceValues.localTimestamp] resolves it.
     */
    fun parse(
        text: String?,
        zone: ZoneId,
    ): Long? {
        val cleaned = Normalizer.normalize(text.orEmpty(), Normalizer.Form.NFC)
            .replace(SPACES, " ")
            .trim()
        if (cleaned.isEmpty()) return null
        // Hevy's own shapes first: they are what nearly every cell holds, and the ISO reading
        // refuses each of them by throwing.
        return localDateTime(cleaned)?.let {
            try {
                it.atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeException) {
                null
            }
        } ?: SourceValues.localTimestamp(cleaned, zone)
    }

    /** [text] as a date and time in one of [SHAPES], or null when it is in none or is no date. */
    private fun localDateTime(text: String): LocalDateTime? {
        val (shape, match) = SHAPES.firstNotNullOfOrNull { shape -> shape.regex.matchEntire(text)?.let { shape to it } }
            ?: return null
        val groups = match.groups
        val month = if (shape.monthIsWord) MONTHS[monthKey(groups.text(WORD))] else groups.text(MONTH).toIntOrNull()
        val time = clockTime(groups.text(TIME_OF_DAY))
        return if (month == null || time == null) {
            null
        } else {
            try {
                LocalDateTime.of(LocalDate.of(groups.text(YEAR).toInt(), month, groups.text(DAY).toInt()), time)
            } catch (_: DateTimeException) {
                null
            }
        }
    }

    /**
     * [text] as a time of day: a 24-hour clock, or a 12-hour one with one word from
     * [DAY_PERIODS] before or after it. Null for anything else, including a word it does not know.
     */
    private fun clockTime(text: String): LocalTime? {
        val groups = CLOCK.matchEntire(text)?.groups ?: return null
        val periods = listOf(BEFORE, AFTER).map { periodKey(groups.text(it)) }.filter(String::isNotEmpty)
        val hour = groups.text(HOUR).toInt()
        val hourOfDay = when {
            periods.isEmpty() -> hour
            periods.size > 1 || hour !in 1..HALF_DAY -> null
            else -> DAY_PERIODS[periods.single()]?.let { shift -> hour % HALF_DAY + shift }
        }
        return hourOfDay?.let {
            try {
                LocalTime.of(it, groups.text(MINUTE).toInt())
            } catch (_: DateTimeException) {
                null
            }
        }
    }

    /** What the group called [name] matched, or nothing when it took no part in the match. */
    private fun MatchGroupCollection.text(name: String): String = this[name]?.value.orEmpty()

    /**
     * One shape a date comes in, ending in the time of day.
     *
     * @property monthIsWord whether the month is the group called [WORD], looked up in [MONTHS],
     *   rather than the number in the group called [MONTH].
     */
    private class DateShape(
        pattern: String,
        val monthIsWord: Boolean,
    ) {
        val regex = Regex(pattern)
    }

    /** A month word as [MONTHS] keys it: lower case, without the full stop or `॰` abbreviating it. */
    private fun monthKey(word: String): String = word.lowercase(Locale.ROOT).trimEnd('.', '॰')

    /**
     * A day-period word as [DAY_PERIODS] keys it: lower case, without the full stops and spaces
     * that Canadian English (`p.m.`) and Latin American Spanish (`p. m.`) write inside it.
     */
    private fun periodKey(word: String): String = word.lowercase(Locale.ROOT).filterNot { it == '.' || it == ' ' }

    private const val HALF_DAY = 12

    /** The spaces a formatter puts in a date: ordinary, no-break, and the narrow one before `PM`. */
    private val SPACES = Regex("[\\s\\u00A0\\u202F\\u2009]+")

    private const val DAY = "day"
    private const val MONTH = "month"
    private const val WORD = "word"
    private const val YEAR = "year"
    private const val TIME_OF_DAY = "time"
    private const val BEFORE = "before"
    private const val HOUR = "hour"
    private const val MINUTE = "minute"
    private const val AFTER = "after"

    /**
     * A clock, with whatever word a 12-hour one has before it, or after it — where the word may
     * have a space inside it (`p. m.`).
     */
    private val CLOCK = Regex("(?<$BEFORE>\\S*?) ?(?<$HOUR>\\d{1,2}):(?<$MINUTE>\\d{2}) ?(?<$AFTER>.*)")

    /** The shapes Hevy's dates come in, each ending in the time of day, tried in order. */
    private val SHAPES = listOf(
        // `26 Jan 2024, 18:03` and every language's version of it: Portuguese's `de` around the
        // month, Catalan's `de` or `d’` before it, Russian's `г.` after the year, Turkish without
        // the comma, and Chinese, Japanese and Korean months written `1月` or `1월`.
        DateShape(
            "(?<$DAY>\\d{1,2})\\.? (?:de |d['’])?(?<$WORD>[^\\s\\d,][^\\s,]*|\\d{1,2}[月월]) (?:de )?" +
                "(?<$YEAR>\\d{4})(?: г\\.?)?,? (?<$TIME_OF_DAY>.+)",
            monthIsWord = true,
        ),
        // The website's English date: `Jan 26, 2024, 6:03 PM`.
        DateShape(
            "(?<$WORD>[^\\s\\d,]+) (?<$DAY>\\d{1,2}),? (?<$YEAR>\\d{4}),? (?<$TIME_OF_DAY>.+)",
            monthIsWord = true,
        ),
        // The website's dates that start with the year: Japanese `2024/01/26`, Korean
        // `2024. 1. 26.`, Chinese `2024年1月26日`, and the ISO-like `2024-01-26,`.
        DateShape(
            "(?<$YEAR>\\d{4}) ?[-./年] ?(?<$MONTH>\\d{1,2}) ?[-./月] ?(?<$DAY>\\d{1,2})[.日]?,? ?" +
                "(?<$TIME_OF_DAY>.+)",
            monthIsWord = false,
        ),
        // The website's German date: `26.01.2024, 18:03`.
        DateShape(
            "(?<$DAY>\\d{1,2})\\.(?<$MONTH>\\d{1,2})\\.(?<$YEAR>\\d{4}),? (?<$TIME_OF_DAY>.+)",
            monthIsWord = false,
        ),
    )

    /**
     * Every month word Hevy's app and website are known to write, in all of their languages, as
     * [monthKey] reduces it, to the month's number. Both the abbreviated and the standalone or
     * full forms are listed where a language has more than one (German `märz` and `mär`,
     * Russian `мая` and `май`), because the app and the website do not always pick the same.
     */
    private val MONTHS: Map<String, Int> = buildMap {
        fun months(vararg words: String) {
            words.forEachIndexed { index, word ->
                word.split('|').forEach { spelling ->
                    val previous = put(Normalizer.normalize(spelling, Normalizer.Form.NFC), index + 1)
                    check(previous == null || previous == index + 1) { "\"$spelling\" is two months" }
                }
            }
        }
        // English, including the `Sept` newer formatters write.
        months("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep|sept", "oct", "nov", "dec")
        // German.
        months("jan", "feb", "märz|mär", "apr", "mai", "juni|jun", "juli|jul", "aug", "sept|sep", "okt", "nov", "dez")
        // Spanish.
        months("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sept|sep", "oct", "nov", "dic")
        // French.
        months("janv", "févr", "mars", "avr", "mai", "juin", "juil", "août", "sept", "oct", "nov", "déc")
        // Italian.
        months("gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic")
        // Portuguese, European and Brazilian.
        months("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")
        // Catalan.
        months("gen", "febr", "març", "abr", "maig", "juny", "jul", "ag", "set", "oct", "nov", "des")
        // Polish.
        months("sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru")
        // Turkish.
        months("oca", "şub", "mar", "nis", "may", "haz", "tem", "ağu", "eyl", "eki", "kas", "ara")
        // Russian: the genitive a date uses, and the nominative some formatters write.
        months(
            "янв", "февр", "мар|март", "апр", "мая|май", "июн|июня|июнь",
            "июл|июля|июль", "авг", "сент", "окт", "нояб", "дек",
        )
        // Arabic.
        months(
            "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
            "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر",
        )
        // Hindi.
        months("जन", "फ़र", "मार्च", "अप्रैल|अप्रै", "मई", "जून", "जुल", "अग", "सित", "अक्टू", "नव", "दिस")
        // Chinese and Japanese, then Korean.
        months("1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月")
        months("1월", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월", "11월", "12월")
    }

    /**
     * The words the website puts beside a 12-hour time, to the hours each adds once twelve
     * o'clock has been read as zero: none for a morning, twelve for the rest. Traditional
     * Chinese names six parts of the day where English has two.
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
}
