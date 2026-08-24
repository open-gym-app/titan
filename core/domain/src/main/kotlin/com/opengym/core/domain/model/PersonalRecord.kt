package com.opengym.core.domain.model

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
 * Six [PrType]s share these five figures, not six of their own: [weight] doubles for
 * [PrType.MAX_VOLUME], which stores the session's total milligram-reps for the exercise (see
 * `VolumeCalculator.totalVolume`) rather than a lift's weight. A sixth `Long?` field for a
 * quantity that is never live at the same time as [PrType.MAX_WEIGHT] would only restate the
 * type in a second place; [duration] already sets the precedent by doubling for a timed hold
 * on top of [PrType.BEST_PACE].
 *
 * A [PrType.BEST_PACE] record carries **both** [distance] and [duration] — the winning set's
 * own figures, not a precomputed ratio — because seconds-per-centimetre is not a quantity
 * this schema stores anywhere else, and a reader (`:core:ui`'s `format` package) needs the
 * two raw figures to render pace in whatever unit the user's [UnitSystem] calls for anyway.
 * "Better" for this type means the *lower* `duration / distance`, the one comparison in this
 * model that does not mean "bigger" — see [com.opengym.core.domain.usecase.PrDetectionUseCase].
 *
 * Units are the canonical ones the whole app stores in: milligrams for [weight] and
 * [estimated1Rm], centimetres for [distance], seconds for [duration], epoch milliseconds for
 * [achievedAt]. Formatting them for a reader is `:core:ui`'s `format` package.
 */
data class PersonalRecord(
    val id: Long = 0,
    val exerciseId: Long,
    val workoutSetId: Long,
    val type: PrType,
    /** Milligrams, for [PrType.MAX_WEIGHT] — or milligram-reps, for [PrType.MAX_VOLUME]. */
    val weight: Long? = null,
    /** For [PrType.MAX_REPS]. */
    val reps: Int? = null,
    /** Centimetres, for [PrType.MAX_DISTANCE] and [PrType.BEST_PACE]. */
    val distance: Long? = null,
    /** Seconds, for [PrType.BEST_PACE] and for a timed hold. */
    val duration: Long? = null,
    /** Milligrams, for [PrType.ESTIMATED_1RM]. */
    val estimated1Rm: Long? = null,
    /** Epoch milliseconds. */
    val achievedAt: Long,
)
