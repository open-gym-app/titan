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
 * Units are the canonical ones the whole app stores in: milligrams for [weight] and
 * [estimated1Rm], centimetres for [distance], seconds for [duration], epoch milliseconds for
 * [achievedAt]. Formatting them for a reader is `:core:ui`'s `format` package.
 */
data class PersonalRecord(
    val id: Long = 0,
    val exerciseId: Long,
    val workoutSetId: Long,
    val type: PrType,
    /** Milligrams, for [PrType.MAX_WEIGHT]. */
    val weight: Long? = null,
    /** For [PrType.MAX_REPS]. */
    val reps: Int? = null,
    /** Centimetres, for [PrType.MAX_DISTANCE]. */
    val distance: Long? = null,
    /** Seconds, for [PrType.BEST_PACE] and for a timed hold. */
    val duration: Long? = null,
    /** Milligrams, for [PrType.ESTIMATED_1RM]. */
    val estimated1Rm: Long? = null,
    /** Epoch milliseconds. */
    val achievedAt: Long,
)
