package com.titan.core.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.titan.core.ui.R
import com.titan.core.ui.theme.LocalUnitSystem

/**
 * A set as the one line everything writes it on: `80 kg × 5`.
 *
 * The same shape three places need — a logged row in a table that cannot be typed into, the
 * rest sheet's "what is coming", and anything later that quotes a set in a sentence — and it
 * has to be one function, because two of them would eventually disagree about a bodyweight set
 * or about where the unit goes.
 *
 * The weight is rendered in the ambient [LocalUnitSystem], with its unit; the reps are a bare
 * count, because `× 5 reps` reads as two nouns for one number.
 *
 * ### A timed set reads as what it was timed in
 *
 * `5 km · 27:14` for a run, `1:30` for a plank, `1:30 · +10 kg` for one held with a plate on
 * the back (#M7-17) — never `— × —`, which is what a set with no reps used to read as wherever
 * it was quoted. The rule is the count: a set that recorded **reps** is a set of reps and reads
 * as one, whatever else it carries, and only a set without them is read by its time and its
 * distance. That keeps a timed bench press (legal — `SetValidator` forbids no dimension) on the
 * line every other bench press is on, and needs nobody to pass the exercise's logging mode to a
 * function whose callers often do not have it.
 *
 * @param weightMilligrams what was lifted, or null for a set with no weight — a bodyweight
 *   movement, or a row half filled in. On a hold, the load added to it.
 * @param reps how many, or null for a set measured some other way.
 * @param durationSeconds how long it lasted, or null for a set not timed.
 * @param distanceCentimetres how far it went, or null for a set that covered no ground.
 * @return the line, or **null** when there is nothing to put on it. Null rather than a dash so
 *   the caller decides what an empty set looks like: a table draws a placeholder to keep its
 *   columns lined up, and a sentence simply leaves the clause out.
 */
@Composable
fun formatSet(
    weightMilligrams: Long?,
    reps: Int?,
    durationSeconds: Long? = null,
    distanceCentimetres: Long? = null,
): String? =
    when {
        weightMilligrams != null && reps != null ->
            stringResource(R.string.set_log_weight_and_reps, formatWeight(weightMilligrams), reps)

        reps != null -> pluralStringResource(R.plurals.set_log_reps_only, reps, reps)

        durationSeconds != null || distanceCentimetres != null ->
            formatTimedSet(weightMilligrams, durationSeconds, distanceCentimetres)

        weightMilligrams != null -> formatWeight(weightMilligrams)

        else -> null
    }

/**
 * A set with no reps and at least one of a time and a distance, as [formatSet] writes it.
 *
 * The distance leads where there is one, because it is what a run is called — "a 5 km" — and
 * the time is how it went; a hold has no distance and is its time. A load rides at the end with
 * a `+`, because on a timed set it is weight *added* to the body rather than weight lifted, and
 * `1:30 · 10 kg` reads as a plank and a separate ten kilos.
 */
@Composable
private fun formatTimedSet(
    weightMilligrams: Long?,
    durationSeconds: Long?,
    distanceCentimetres: Long?,
): String {
    val time = durationSeconds?.let(::formatClockValue)
    val distance = distanceCentimetres?.let { formatDistance(it) }
    val measured = when {
        distance != null && time != null -> stringResource(R.string.set_log_distance_and_time, distance, time)
        else -> distance ?: time.orEmpty()
    }
    return when (weightMilligrams?.takeIf { it > 0 }) {
        null -> measured
        else -> stringResource(R.string.set_log_timed_with_load, measured, formatWeight(weightMilligrams))
    }
}

/**
 * The same set with everything optional taken out of it: `60×10`.
 *
 * For the one place a set has to fit in a table *cell* rather than on a line — the previous
 * column, which is 52dp wide because the columns beside it are fields somebody is typing into.
 * The unit goes, and so do the spaces around the `×`: what the cell is for is a comparison
 * against the row it sits on, and that row's own header already says which unit the numbers are
 * in. Anything wider than this is a column that eats the weight field to say `kg` five times.
 *
 * Not a replacement for [formatSet], which is what a set reads as everywhere there is room for
 * it — including the description a screen reader is given for this very cell. Two renderings of
 * one fact, chosen by how much space there is, which is why they live next to each other.
 *
 * @return the cell, or **null** when there is nothing to put in it — same contract as
 *   [formatSet], and the caller draws its own placeholder.
 */
@Composable
fun formatSetCompact(
    weightMilligrams: Long?,
    reps: Int?,
    durationSeconds: Long? = null,
    distanceCentimetres: Long? = null,
): String? =
    when {
        weightMilligrams != null && reps != null -> stringResource(
            R.string.set_log_weight_and_reps_compact,
            formatWeightValue(weightMilligrams),
            reps,
        )

        // A bodyweight set, where the count is the whole of what was done. The `×` stays in front
        // of it so the cell cannot be read as a weight with the reps clipped off.
        reps != null -> stringResource(R.string.set_log_reps_only_compact, reps)

        // A timed set keeps the one figure it is compared by. The time, where there is one: it
        // is the dimension every timed set has to record, so a column of them always compares
        // like with like — a hold against a hold, a run's time against a run's — where a
        // distance would leave the rows that were logged by time alone blank beside it. The
        // distance is in the spoken form, which has the room.
        durationSeconds != null -> formatClockValue(durationSeconds)

        distanceCentimetres != null -> formatDistanceValue(distanceCentimetres)

        weightMilligrams != null -> formatWeightValue(weightMilligrams)

        else -> null
    }
