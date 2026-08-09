package com.opengym.core.ui.format

import java.util.Locale
import kotlin.time.Duration

/**
 * Durations, as a clock reads them.
 *
 * A rest is `2:00` and never `120 s` or `2 min`, because the value is watched counting down:
 * the digits have to mean the same thing at 2:00 and at 0:07, and a unit that changes as the
 * number shrinks is one the eye has to re-read. Rendered in a numeric type role, which is
 * monospaced with tabular figures, so the layout does not twitch as the seconds tick.
 */

private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60

/**
 * `M:SS`, or `H:MM:SS` once there is an hour to show: `2:00`, `0:45`, `1:05:30`.
 *
 * The leading field is unpadded and the rest are padded, which is what makes a column of
 * these line up on the colon while a single one does not read as `02:00`. Sub-second
 * precision is truncated rather than rounded — a rest timer showing `0:01` with 400 ms left
 * is right, and showing `0:00` for the last half-second is not.
 */
fun Duration.formatAsClock(): String {
    val totalSeconds = inWholeSeconds
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    val minutes = (totalSeconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
    val hours = totalSeconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR)

    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}
