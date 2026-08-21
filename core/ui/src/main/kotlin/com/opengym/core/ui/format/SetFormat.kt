package com.opengym.core.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opengym.core.ui.R
import com.opengym.core.ui.theme.LocalUnitSystem

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
 * @param weightMilligrams what was lifted, or null for a set with no weight — a bodyweight
 *   movement, or a row half filled in.
 * @param reps how many, or null for a set measured some other way.
 * @return the line, or **null** when there is nothing to put on it. Null rather than a dash so
 *   the caller decides what an empty set looks like: a table draws a placeholder to keep its
 *   columns lined up, and a sentence simply leaves the clause out.
 */
@Composable
fun formatSet(weightMilligrams: Long?, reps: Int?): String? = when {
    weightMilligrams != null && reps != null ->
        stringResource(R.string.set_log_weight_and_reps, formatWeight(weightMilligrams), reps)

    reps != null -> stringResource(R.string.set_log_reps_only, reps)
    weightMilligrams != null -> formatWeight(weightMilligrams)
    else -> null
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
fun formatSetCompact(weightMilligrams: Long?, reps: Int?): String? = when {
    weightMilligrams != null && reps != null -> stringResource(
        R.string.set_log_weight_and_reps_compact,
        formatWeightValue(weightMilligrams),
        reps,
    )

    // A bodyweight set, where the count is the whole of what was done. The `×` stays in front
    // of it so the cell cannot be read as a weight with the reps clipped off.
    reps != null -> stringResource(R.string.set_log_reps_only_compact, reps)
    weightMilligrams != null -> formatWeightValue(weightMilligrams)
    else -> null
}
