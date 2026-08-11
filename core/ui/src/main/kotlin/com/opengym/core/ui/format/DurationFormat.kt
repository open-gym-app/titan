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
fun Duration.formatAsClock(): String = with(ClockFields(this)) {
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
fun Duration.formatAsElapsed(): String = with(ClockFields(this)) {
    String.format(Locale.getDefault(), "%s%d:%02d:%02d", sign, hours, minutes, seconds)
}

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
