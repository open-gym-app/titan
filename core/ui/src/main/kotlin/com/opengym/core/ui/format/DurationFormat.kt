package com.opengym.core.ui.format

import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.time.Duration

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
 * both. [Locale] still comes into it, because in a dozen locales the digits themselves are
 * not the ASCII ones.
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
            String.format(Locale.getDefault(), "%s%d:%02d:%02d", sign, hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%s%d:%02d", sign, minutes, seconds)
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
        String.format(Locale.getDefault(), "%s%d:%02d:%02d", sign, hours, minutes, seconds)
    }

/**
 * How long something is expected to take: `~55m`, `~1h 05m`, `~5m`.
 *
 * The one duration here that is **not** a clock reading, and it is deliberately drawn to
 * look nothing like one. A template's estimate
 * ([com.opengym.core.domain.template.TemplateDuration]) is a pacing figure assembled from
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
 * The unit letters are not translated, matching
 * [com.opengym.core.ui.format.weightSuffix]'s `kg`/`lb`: they are symbols on a figure in a
 * mono column, and the localization pass (#40) sweeps both together.
 */
fun Duration.formatAsEstimate(): String {
    val minutes = roundedEstimateMinutes()
    val locale = Locale.getDefault()

    return if (minutes >= MINUTES_PER_HOUR) {
        String.format(
            locale,
            "~%d%s %02d%s",
            minutes / MINUTES_PER_HOUR,
            HOUR_SUFFIX,
            minutes % MINUTES_PER_HOUR,
            MINUTE_SUFFIX,
        )
    } else {
        String.format(locale, "~%d%s", minutes, MINUTE_SUFFIX)
    }
}

/**
 * How long something *took*: `52m`, `1h 05m`, `0m`.
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
 * The unit letters are not translated, matching [formatAsEstimate] and
 * [com.opengym.core.ui.format.weightSuffix]: the localization pass (#40) sweeps them
 * together.
 */
fun Duration.formatAsLength(): String {
    val minutes = inWholeMinutes.coerceAtLeast(0)
    val locale = Locale.getDefault()

    return if (minutes >= MINUTES_PER_HOUR) {
        String.format(
            locale,
            "%d%s %02d%s",
            minutes / MINUTES_PER_HOUR,
            HOUR_SUFFIX,
            minutes % MINUTES_PER_HOUR,
            MINUTE_SUFFIX,
        )
    } else {
        String.format(locale, "%d%s", minutes, MINUTE_SUFFIX)
    }
}

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
 * The unit letters are not translated, matching the other three: the localization pass (#40)
 * sweeps them together.
 */
fun Duration.formatAsTotal(): String {
    val minutes = inWholeMinutes.coerceAtLeast(0)
    val locale = Locale.getDefault()

    return if (minutes >= MINUTES_PER_HOUR) {
        String.format(locale, "%d%s", minutes / MINUTES_PER_HOUR, HOUR_SUFFIX)
    } else {
        String.format(locale, "%d%s", minutes, MINUTE_SUFFIX)
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

private const val HOUR_SUFFIX = "h"
private const val MINUTE_SUFFIX = "m"

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
