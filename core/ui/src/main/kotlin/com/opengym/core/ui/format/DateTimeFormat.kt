package com.opengym.core.ui.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
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
): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
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
): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    .withLocale(locale)
    .format(Instant.ofEpochMilli(epochMillis).atZone(zone))
