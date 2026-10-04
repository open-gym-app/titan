package com.titan.core.domain.model

/**
 * A best, and the set that achieved it.
 *
 * Derived data: every record points at the `workout_set` row it was read from, and deleting
 * that set deletes the record. Nothing here is authored — the PR engine (#30) writes these
 * as sessions are finished — so a screen that finds none is looking at a user who has not
 * logged anything yet, not at a failure.
 *
 * ## Why five nullable figures rather than one
 *
 * A record is *of* something ([type]), and what it measures decides which figure carries the
 * value: a heaviest-ever bench fills [weight], a rep record fills [reps], a distance record
 * fills [distance]. One `value: Long` would need a unit alongside it to be read back, which
 * is the type — and then two fields would say the same thing, with nothing keeping them in
 * step. The unused figures are null.
 *
 * Eight [PrType]s share these five figures, not eight of their own. [weight] doubles twice: for
 * [PrType.MAX_VOLUME] it stores the session's total milligram-reps for the exercise (see
 * `VolumeCalculator.totalVolume`) rather than a lift's weight, and for
 * [PrType.MAX_WEIGHTED_HOLD] the hold's added load times its seconds — milligram-seconds — with
 * the seconds themselves in [duration], so the load is [holdLoad] and never has to be stored
 * twice. Another `Long?` field for a quantity that is never live at the same time as
 * [PrType.MAX_WEIGHT] would only restate the type in a second place. A product is what the
 * engine compares, never what a screen shows: a weighted hold is drawn as its two parts.
 *
 * A [PrType.BEST_PACE] record carries **both** [distance] and [duration] — the winning set's
 * own figures, not a precomputed ratio — because a pace is not a quantity
 * this schema stores anywhere else, and a reader (`:core:ui`'s `format` package) needs the
 * two raw figures to render pace in whatever unit the user's [UnitSystem] calls for anyway.
 * "Better" for this type means the *lower* `duration / distance`, the one comparison in this
 * model that does not mean "bigger" — see [com.titan.core.domain.usecase.PrDetectionUseCase].
 *
 * Units are the canonical ones the whole app stores in: milligrams for [weight] and
 * [estimated1Rm], centimetres for [distance], seconds for [duration], epoch milliseconds for
 * [achievedAt]. Formatting them for a reader is `:core:ui`'s `format` package.
 *
 * ## [paceStyle] is read, not stored
 *
 * It is the exercise's, not the row's — whether its [PrType.BEST_PACE] reads as a pace or a
 * speed, from the `SPEED` and `SWIM` tags (see [PaceStyle]) — and the repository fills it in on
 * the way out, so every screen that draws a record draws it in the exercise's own terms without
 * looking the exercise up itself. A record built anywhere else, the engine's candidates
 * included, reads as a pace, which is also what an untagged exercise reads as.
 */
data class PersonalRecord(
    val id: Long = 0,
    val exerciseId: Long,
    val workoutSetId: Long,
    val type: PrType,
    /**
     * Milligrams, for [PrType.MAX_WEIGHT] — or milligram-reps, for [PrType.MAX_VOLUME], or
     * milligram-seconds, for [PrType.MAX_WEIGHTED_HOLD].
     */
    val weight: Long? = null,
    /** For [PrType.MAX_REPS]. */
    val reps: Int? = null,
    /** Centimetres, for [PrType.MAX_DISTANCE] and [PrType.BEST_PACE]. */
    val distance: Long? = null,
    /** Seconds, for [PrType.BEST_PACE], [PrType.MAX_DURATION] and [PrType.MAX_WEIGHTED_HOLD]. */
    val duration: Long? = null,
    /** Milligrams, for [PrType.ESTIMATED_1RM]. */
    val estimated1Rm: Long? = null,
    /** Epoch milliseconds. */
    val achievedAt: Long,
    /** How the exercise reads a [PrType.BEST_PACE]. Not a column — see the class doc. */
    val paceStyle: PaceStyle = PaceStyle.PACE,
)

/**
 * The pace a [PrType.BEST_PACE] is ranked on, in whole nanoseconds per centimetre — lower being
 * faster — or **null** for a record without both a positive distance and a positive duration.
 * See [paceOf] for the unit.
 *
 * One definition for the engine comparing two candidates and the records list measuring how far
 * one beat the other, which is the figure the store's own improvement query computes too.
 */
val PersonalRecord.pace: Long?
    get() = paceOf(duration, distance)

/**
 * The load a [PrType.MAX_WEIGHTED_HOLD] was held with, in milligrams — its stored product taken
 * back apart — or **null** for any other kind, or for a row without a positive duration to
 * divide by. Exact, because the engine wrote the product of two whole numbers.
 */
val PersonalRecord.holdLoad: Long?
    get() {
        if (type != PrType.MAX_WEIGHTED_HOLD) return null
        val seconds = duration?.takeIf { it > 0L } ?: return null
        return weight?.div(seconds)
    }
