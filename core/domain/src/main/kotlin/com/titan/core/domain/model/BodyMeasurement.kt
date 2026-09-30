package com.titan.core.domain.model

import kotlinx.serialization.Serializable

/**
 * One dated entry of what the user measured about their body — weight, circumferences and
 * body fat, together in one row.
 *
 * ### One row per session at the tape measure, not one per figure
 *
 * This replaced `BodyWeightLog`, which held weight alone. A user stepping on the scale and
 * reaching for the tape does both in the same two minutes, and #M5-15 logs them as one entry
 * pre-filled from the last: a companion table would have made that one write into two, and
 * given the history screen two lists to interleave by timestamp for no gain. The one case
 * that would have justified splitting them — measurements genuinely logged apart from weight
 * — is served by every field being independently optional instead.
 *
 * ### Every field is optional, including [weight]
 *
 * A user who only ever weighs themselves logs weight and nothing else; one tracking a cut
 * logs a waist without stepping on the scale. Neither may be forced to invent the other, so
 * there is no mandatory measurement here — [isEmpty] is what says an entry records nothing at
 * all, and `BodyMeasurementValidator` is what refuses one.
 *
 * ### Canonical integer units, as everywhere in the schema
 *
 * | Field | Unit |
 * | --- | --- |
 * | [weight] | milligrams |
 * | [chest], [waist], [arm], [thigh] | millimetres |
 * | [bodyFat] | tenths of a percent, so `155` is 15.5% |
 * | [createdAt], [updatedAt] | epoch milliseconds, UTC |
 *
 * Converting to kilograms, pounds, centimetres or inches is the UI's job — see PERSISTENCE.md.
 *
 * `@Serializable` because an entry the user is *typing* is one of these before it is a row: the
 * body screen keeps the open form in its saved screen state, so a process death does not take
 * away six figures somebody has just measured.
 */
@Serializable
data class BodyMeasurement(
    val id: Long = 0,

    /** Body weight in milligrams. */
    val weight: Long? = null,

    /** Chest circumference in millimetres. */
    val chest: Int? = null,

    /** Waist circumference in millimetres. */
    val waist: Int? = null,

    /**
     * Upper-arm circumference in millimetres.
     *
     * One figure rather than a left and a right: the app asks for the measurement a user
     * takes, and nobody tapes both arms to track a trend in one.
     */
    val arm: Int? = null,

    /** Thigh circumference in millimetres. */
    val thigh: Int? = null,

    /** Body fat in tenths of a percent — `155` is 15.5%. */
    val bodyFat: Int? = null,

    /** Whatever the user wrote about the entry. Not a measurement; see [isEmpty]. */
    val notes: String? = null,

    val createdAt: Long,
    val updatedAt: Long,
) {

    /**
     * Lean mass in milligrams, or null when either [weight] or [bodyFat] is missing.
     *
     * **Derived on read, with no column behind it**, for the reason `TemplateDuration` has
     * none: it is a pure function of two fields on this very row, so a stored copy could only
     * ever agree with them or be stale, and keeping it true would mean a second write whose
     * whole job is to restate what the row already says. An importer can compute it from the
     * fields it already has, so nothing in an export should carry one either.
     *
     * Integer arithmetic throughout — tenths of a percent *are* thousandths of the whole, so
     * the body-fat figure needs no scaling to divide by 1000. The division truncates, which
     * costs at most a milligram on a figure measured in tens of kilograms.
     *
     * A [bodyFat] outside `0..1000` — a corrupt row, since `BodyMeasurementValidator` bounds it
     * to `1..1000` — produces a value outside `0..weight` rather than a clamped fiction.
     */
    val leanMass: Long?
        get() {
            val weight = weight ?: return null
            val bodyFat = bodyFat ?: return null
            return weight * (BODY_FAT_WHOLE - bodyFat) / BODY_FAT_WHOLE
        }

    /**
     * Whether this entry records no measurement at all.
     *
     * [notes] deliberately does not count. A note annotates a measurement; an entry that is
     * only a note is a diary entry the body screen has nothing to plot for.
     */
    val isEmpty: Boolean
        get() = weight == null && chest == null && waist == null &&
            thigh == null && arm == null && bodyFat == null

    companion object {

        /** 100%, in [bodyFat]'s tenths of a percent. */
        const val BODY_FAT_WHOLE = 1_000L
    }
}
