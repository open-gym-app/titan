package com.titan.core.domain.validation

import com.titan.core.domain.model.BodyMeasurement

/**
 * Bounds and shape checks on a body measurement entry, run before it is persisted.
 *
 * The counterpart of [SetValidator] for the body screen, and it works the same way: these are
 * rules Room has no `CHECK` to express, they are *not* enforced in [BodyMeasurement]'s `init`
 * (a half-filled form being typed into has to be representable), and they are a check a caller
 * runs rather than a gate the write path forces it through — anything that persists an entry
 * has to call this first or the bounds simply do not hold for those rows.
 *
 * Every bound is expressed in the canonical storage units: weight in milligrams,
 * circumferences in millimetres, body fat in tenths of a percent.
 *
 * The ceilings are typo-catchers rather than statements about what a body may be. They exist
 * to catch a stray digit or a pounds-for-kilograms mix-up, and they sit far enough above any
 * real measurement that nobody is ever refused their own.
 */
object BodyMeasurementValidator {

    /**
     * A weight of zero was not measured; "not measured" is null.
     *
     * The floor is 1 rather than 0 for the reason [SetValidator.REPS_MIN] is, and unlike
     * [SetValidator.WEIGHT_MIN_MG], which is zero because a bodyweight set genuinely carries
     * no load. Nobody weighs nothing.
     */
    const val WEIGHT_MIN_MG = 1L

    /** 1 tonne, in milligrams — well above the heaviest human ever recorded. */
    const val WEIGHT_MAX_MG = 1_000_000_000L

    /** A circumference of zero was not measured, the same reasoning as [WEIGHT_MIN_MG]. */
    const val CIRCUMFERENCE_MIN_MM = 1

    /** 5 metres, in millimetres — an order of magnitude past any human circumference. */
    const val CIRCUMFERENCE_MAX_MM = 5_000

    /** Body fat of zero was not measured, in tenths of a percent. */
    const val BODY_FAT_MIN = 1

    /**
     * 100%, in tenths of a percent. A body cannot be more than all fat.
     *
     * The same figure as [BodyMeasurement.BODY_FAT_WHOLE], which is what `leanMass` divides by;
     * the two are one unit convention and have to move together.
     */
    const val BODY_FAT_MAX = 1_000

    private const val MG_PER_KG = 1_000_000L
    private const val MM_PER_M = 1_000
    private const val TENTHS_PER_PERCENT = 10

    /**
     * Checks [measurement] against every rule, reporting *all* violations in one
     * [ValidationResult.Invalid] rather than stopping at the first — a user who mis-typed both
     * their weight and their waist should see both.
     *
     * A null field is never a violation: every measurement is independently optional, and
     * logging weight alone is the common case. What *is* refused is an entry with no
     * measurement in it at all ([BodyMeasurement.isEmpty]), which would be a dated row the
     * body screen has nothing to draw from and the history list nothing to show.
     */
    fun validate(measurement: BodyMeasurement): ValidationResult {
        val violations = measurement.boundViolations() + measurement.shapeViolations()

        return if (violations.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(violations.joinToString("; "))
        }
    }

    /** Range checks on whichever figures this entry actually carries. */
    private fun BodyMeasurement.boundViolations(): List<String> =
        buildList {
            weight?.let { value ->
                if (value !in WEIGHT_MIN_MG..WEIGHT_MAX_MG) {
                    add(
                        "weight must be within $WEIGHT_MIN_MG mg..${WEIGHT_MAX_MG / MG_PER_KG} kg " +
                            "($WEIGHT_MIN_MG..$WEIGHT_MAX_MG mg), was $value mg",
                    )
                }
            }
            circumferenceViolation("neck", neck)?.let(::add)
            circumferenceViolation("shoulders", shoulders)?.let(::add)
            circumferenceViolation("chest", chest)?.let(::add)
            circumferenceViolation("waist", waist)?.let(::add)
            circumferenceViolation("hips", hips)?.let(::add)
            circumferenceViolation("arm", arm)?.let(::add)
            circumferenceViolation("forearm", forearm)?.let(::add)
            circumferenceViolation("thigh", thigh)?.let(::add)
            circumferenceViolation("calf", calf)?.let(::add)
            bodyFat?.let { value ->
                if (value !in BODY_FAT_MIN..BODY_FAT_MAX) {
                    add(
                        "bodyFat must be within ${asPercent(BODY_FAT_MIN)}%.." +
                            "${asPercent(BODY_FAT_MAX)}% " +
                            "($BODY_FAT_MIN..$BODY_FAT_MAX tenths of a percent), was $value",
                    )
                }
            }
        }

    /**
     * Tenths of a percent as the percentage a user typed — `1` is `0.1`, not `0`.
     *
     * Integer division alone would render [BODY_FAT_MIN] as `0%` and tell a user who entered
     * 0.0% that the bound they just broke includes their value.
     */
    private fun asPercent(tenths: Int): String = "${tenths / TENTHS_PER_PERCENT}.${tenths % TENTHS_PER_PERCENT}"

    private fun circumferenceViolation(
        field: String,
        value: Int?,
    ): String? =
        value?.takeIf { it !in CIRCUMFERENCE_MIN_MM..CIRCUMFERENCE_MAX_MM }?.let {
            "$field must be within $CIRCUMFERENCE_MIN_MM mm..${CIRCUMFERENCE_MAX_MM / MM_PER_M} m " +
                "($CIRCUMFERENCE_MIN_MM..$CIRCUMFERENCE_MAX_MM mm), was $it mm"
        }

    /** An entry has to record something. */
    private fun BodyMeasurement.shapeViolations(): List<String> =
        if (isEmpty) listOf("an entry must record at least one measurement") else emptyList()
}
