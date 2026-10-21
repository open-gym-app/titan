package com.titan.core.ui.format

import android.icu.util.Measure
import android.icu.util.MeasureUnit
import com.titan.core.domain.model.ExerciseType
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Durations, as a clock reads them.
 *
 * A rest is `2:00` and never `120 s` or `2 min`, because the value is watched counting down:
 * the digits have to mean the same thing at 2:00 and at 0:07, and a unit that changes as the
 * number shrinks is one the eye has to re-read. Rendered in a numeric type role, which is
 * monospaced with tabular figures, so the layout does not twitch as the seconds tick.
 *
 * Two shapes, because the two things being timed are different:
 *
 * - [formatAsClock] for a countdown — a rest, an interval, a hold. Minutes and seconds, and
 *   an hours field only if there is one to show.
 * - [formatAsElapsed] for how long a session has been running. Always hours, minutes and
 *   seconds, so the field does not gain a column an hour into a workout and shove the rest
 *   of the row sideways.
 * - [formatAsEstimate] for how long something is *expected* to take. Not a clock at all —
 *   see its own note.
 *
 * Unlike the other formatters here there is no unit system to read: a minute is a minute in
 * both. The locale still comes into it twice over — in a dozen locales the digits themselves
 * are not the ASCII ones, and `h`/`m` are translated text wherever a duration is written out
 * rather than punched onto a clock face. The first three shapes are clock faces and carry no
 * unit at all; the last two go through `MeasureFormats.kt`.
 */

private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60
private const val SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR

/**
 * `M:SS`, or `H:MM:SS` once there is an hour to show: `2:00`, `0:45`, `1:05:30`.
 *
 * The leading field is unpadded and the rest are padded, which is what makes a column of
 * these line up on the colon while a single one does not read as `02:00`. Sub-second
 * precision is truncated rather than rounded — a rest timer showing `0:01` with 400 ms left
 * is right, and showing `0:00` for the last half-second is not.
 */
fun Duration.formatAsClock(): String =
    with(ClockFields(this)) {
        if (hours > 0) {
            String.format(formattingLocale(), "%s%d:%02d:%02d", sign, hours, minutes, seconds)
        } else {
            String.format(formattingLocale(), "%s%d:%02d", sign, minutes, seconds)
        }
    }

/**
 * `H:MM:SS`, always, however short: `0:00:07`, `0:52:14`, `2:03:00`.
 *
 * The hours field is kept even while it reads zero, which is the whole difference from
 * [formatAsClock]. A session's elapsed time sits in a header that is on screen for the
 * length of the workout, and a field that grows from `52:14` to `1:00:00` at the hour mark
 * moves everything beside it — the one thing tabular figures were chosen to prevent.
 */
fun Duration.formatAsElapsed(): String =
    with(ClockFields(this)) {
        String.format(formattingLocale(), "%s%d:%02d:%02d", sign, hours, minutes, seconds)
    }

/**
 * How long something is expected to take: `~55m`, `~1h 5m`, `~5m`.
 *
 * The one duration here that is **not** a clock reading, and it is deliberately drawn to
 * look nothing like one. A template's estimate
 * ([com.titan.core.domain.template.TemplateDuration]) is a pacing figure assembled from
 * a per-set allowance and a default rest; rendered as `55:00` it would read as a measured
 * time and invite a comparison with the session timer that would then be wrong every time.
 * The tilde and the unit letters are what say "about".
 *
 * **Rounded to the nearest [ESTIMATE_STEP_MINUTES] minutes**, because that is roughly the
 * precision the underlying formula has and a row reading `~53m` claims more than it knows.
 * A non-zero estimate never rounds down to nothing: the floor is one step, so a single
 * exercise reads `~5m` rather than `~0m`. [Duration.ZERO] — an empty template, or one whose
 * exercises all plan no sets — reads `~0m`, and a caller with nothing to pace should
 * usually draw no estimate at all rather than that.
 *
 * The unit letters come from CLDR at narrow width, so `~55m` is `~55 min` to a French reader
 * and the two fields of `~1h 5m` are joined the way that locale joins them. The zero-padding
 * on the minutes field went with the hand-built string: the padding lined a column up, and a
 * locale that writes `1 h 5 min` has already decided the column is not that shape.
 *
 * The tilde stays a literal. It is a mathematical symbol rather than a word — the same
 * character in every locale this app can render, and the one thing on the line that is not
 * CLDR's to translate.
 */
fun Duration.formatAsEstimate(): String = APPROXIMATELY + formatWholeMinutes(roundedEstimateMinutes())

/**
 * How long something *took*: `52m`, `1h 5m`, `0m`.
 *
 * The third duration role, and the one a list row wears. [formatAsElapsed] is a running
 * clock — it keeps its seconds because it is watched while it moves — and reading a
 * finished session as `0:52:14` in a row of five puts three fields of precision on a figure
 * the user is scanning past. This drops the seconds and keeps the tilde off, because unlike
 * [formatAsEstimate] it is a measurement rather than a guess: a session that ran 52:14 reads
 * `52m` and not `~50m`.
 *
 * **Truncated to the minute, not rounded**, so a session and the sum of its parts cannot
 * come out a minute apart in opposite directions. A session shorter than a minute reads
 * `0m`, which is the honest rendering of one somebody opened and closed.
 *
 * The unit letters are CLDR's, at narrow width — see [formatDurationMeasures], which says
 * why a duration is the one quantity here rendered narrow.
 */
fun Duration.formatAsLength(): String = formatWholeMinutes(inWholeMinutes.coerceAtLeast(0))

/**
 * How long something took in **total**, over a period: `32h`, `48m`, `0m`.
 *
 * The fourth duration role, and the coarsest. [formatAsLength] measures one session and keeps
 * its minutes because a workout is an hour long and five of them are what a lifter compares;
 * this measures a quarter's worth of them, where `32h 47m` claims a precision nobody reads and
 * costs a stat tile the width its label needs. Past an hour the minutes go.
 *
 * **Truncated, like [formatAsLength]**, so a total and the sum of its parts cannot drift in
 * opposite directions — a period of 32h 47m reads `32h`, never `33h`. Under an hour it falls
 * back to whole minutes, because `0h` would be the one rendering that says nothing happened
 * when something did.
 *
 * The unit letters are CLDR's, as in the two above.
 */
fun Duration.formatAsTotal(): String {
    val minutes = inWholeMinutes.coerceAtLeast(0)

    return if (minutes >= MINUTES_PER_HOUR) {
        formatDurationMeasures(Measure(minutes / MINUTES_PER_HOUR, MeasureUnit.HOUR))
    } else {
        formatDurationMeasures(Measure(minutes, MeasureUnit.MINUTE))
    }
}

/**
 * This duration in whole minutes, snapped to [ESTIMATE_STEP_MINUTES] and floored at one
 * step for anything that is not zero. Negative durations — which nothing produces, but an
 * imported template could — read as zero rather than as time owed.
 */
private fun Duration.roundedEstimateMinutes(): Long {
    val seconds = inWholeSeconds
    if (seconds <= 0) return 0

    val step = ESTIMATE_STEP_MINUTES * SECONDS_PER_MINUTE
    val rounded = (seconds + step / 2) / step * ESTIMATE_STEP_MINUTES
    return rounded.coerceAtLeast(ESTIMATE_STEP_MINUTES.toLong())
}

/** The granularity an estimate is honest to. See [formatAsEstimate]. */
private const val ESTIMATE_STEP_MINUTES = 5

/**
 * [minutes] as hours and minutes, or as minutes alone when there is not an hour in it.
 *
 * The shared body of [formatAsEstimate] and [formatAsLength], which differ only in where
 * their minute count came from and in the tilde one of them wears. The hours field is
 * dropped rather than shown as zero — unlike [formatAsElapsed], which keeps it — because
 * neither of these is watched while it moves, so there is no column for a growing field to
 * shove sideways.
 */
private fun formatWholeMinutes(minutes: Long): String =
    if (minutes >= MINUTES_PER_HOUR) {
        formatDurationMeasures(
            Measure(minutes / MINUTES_PER_HOUR, MeasureUnit.HOUR),
            Measure(minutes % MINUTES_PER_HOUR, MeasureUnit.MINUTE),
        )
    } else {
        formatDurationMeasures(Measure(minutes, MeasureUnit.MINUTE))
    }

/** The one glyph on an estimate that is not CLDR's. See [formatAsEstimate]. */
private const val APPROXIMATELY = "~"

/**
 * A duration split into the fields a clock shows, magnitude and sign kept apart.
 *
 * The sign is separated because `-90` seconds naively fielded is `-1:-30`, which is not a
 * duration anybody can read. Negative values are not supposed to reach a timer — but "not
 * supposed to" has produced that string in every codebase that assumed it, and a clock
 * skewing backwards mid-session is the case that produces it here.
 */
private class ClockFields(duration: Duration) {
    private val totalSeconds = duration.inWholeSeconds.absoluteValue

    /** Prefixed to the whole reading, never to one field. Empty for a duration that is not negative. */
    val sign = if (duration.isNegative()) "-" else ""

    val seconds = totalSeconds % SECONDS_PER_MINUTE
    val minutes = (totalSeconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
    val hours = totalSeconds / SECONDS_PER_HOUR
}

/*
 * ─── A time typed into a field ──────────────────────────────────────────────────────────
 *
 * The inverse of [formatAsClock], for the time field a hold or a run is logged in (#M7-17).
 * Kept beside it because the two have to agree: a stored 90 seconds is put back into the
 * field as `1:30`, and `1:30` has to parse back to 90, or a set corrected and left untouched
 * would drift on its way through the field.
 */

/**
 * What a bare number typed into a time field counts — `45` in a plank's field is forty-five
 * seconds, and `30` in a run's is half an hour.
 *
 * The one decision a time field makes on the user's behalf, and it is made by the *exercise*
 * rather than guessed from the digits: a hold is measured in seconds and an endurance effort
 * in minutes, and a field that read `30` as thirty seconds of running would store a set
 * nobody did. Anything with a separator in it says which field each number is and needs no
 * help.
 */
enum class BareClockNumber(internal val seconds: Long) {
    SECONDS(seconds = 1),
    MINUTES(seconds = SECONDS_PER_MINUTE.toLong()),
}

/**
 * A typed time back into whole seconds, or **null** when [text] is not a time.
 *
 * Accepts `M:SS`, `H:MM:SS` and a bare number of [bareNumber]s. The colon may also be typed as
 * a full stop or a comma, because the decimal pad a phone offers a number field has one of
 * those and no colon: `1.30` is a minute and a half.
 *
 * **The fields after the first are exactly two digits and under sixty**, which is what makes
 * that substitution safe. `1.5` would otherwise be a minute and five seconds to this parser and
 * a minute and a half to the lifter who typed it; refusing it puts a message under the row
 * instead of a set nobody did into the table. The first field is unbounded — `90:00` is an
 * hour and a half, and nobody should have to convert it to type it.
 *
 * Digits are read in any script, because [formatAsClock] writes them in the formatting
 * locale's and the field it fills is parsed back through here.
 *
 * As with [parseWeightMilligrams], null means *malformed* and range is left to
 * `SetValidator`, which is the one that can say which bound was missed — `0:00` parses, and is
 * refused there as a set that was not performed.
 */
fun parseClockSeconds(
    text: String,
    bareNumber: BareClockNumber = BareClockNumber.SECONDS,
): Long? {
    val fields = text.trim().split(':', '.', ',')
    if (fields.any { !it.isClockDigits() }) return null

    if (fields.size > MAX_CLOCK_FIELDS) return null

    val numbers = fields.map { field -> field.fold(0L) { total, digit -> total * DECIMAL + digit.digitToInt() } }
    // Every field after the leading one is a two-digit count of something sixty make one of.
    val trailing = fields.drop(1)
    if (trailing.any { it.length != 2 } || numbers.drop(1).any { it >= SECONDS_PER_MINUTE }) return null

    // A bare number is counted in what the exercise says; with separators, each field is sixty
    // of the one after it — `H:MM:SS` and `M:SS` are the same fold.
    return if (numbers.size == 1) {
        numbers.single() * bareNumber.seconds
    } else {
        numbers.fold(0L) { total, field -> total * SECONDS_PER_MINUTE + field }
    }
}

/** [seconds] as the text a time field holds: `1:30`, `27:14`, `1:05:30`. */
fun formatClockValue(seconds: Long): String = seconds.seconds.formatAsClock()

/**
 * Whether this is one field of a typed time: at least one digit, nothing else, and few enough
 * of them that the arithmetic above cannot overflow. Nine digits is thirty years of seconds,
 * which `SetValidator` refuses by name long before.
 */
private fun String.isClockDigits(): Boolean = length in 1..MAX_CLOCK_FIELD_DIGITS && all(Char::isDigit)

private const val MAX_CLOCK_FIELD_DIGITS = 9

/** Hours, minutes and seconds: the most fields a typed time has. */
private const val MAX_CLOCK_FIELDS = 3
private const val DECIMAL = 10L

/**
 * What a bare number in the time field of an exercise logged this way counts — see
 * [BareClockNumber]. Minutes for an endurance effort, seconds for anything else: a hold, and an
 * exercise deleted from the library, whose time field is a stopwatch's at most.
 */
val ExerciseType?.bareClockNumber: BareClockNumber
    get() = if (this == ExerciseType.ENDURANCE) BareClockNumber.MINUTES else BareClockNumber.SECONDS
