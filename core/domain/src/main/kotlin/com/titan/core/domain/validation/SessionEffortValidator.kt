package com.titan.core.domain.validation

/**
 * Bounds on the two figures that describe a workout as a whole: the calories it burned and the
 * rating the user gave it.
 *
 * The session twin of [SetValidator], and it works the same way: rules Room has no `CHECK` to
 * express, run by a caller before it persists rather than forced by the write path. Both figures
 * are optional, so a null is never a violation.
 */
object SessionEffortValidator {

    /** A workout that burned nothing was not measured; "not measured" is null. */
    const val CALORIES_MIN = 1

    /** 50,000 kcal — a multi-day race, ten times a hard day's training. Past it is a typo. */
    const val CALORIES_MAX = 50_000

    /** The lowest rating: a rough session. */
    const val RATING_MIN = 1

    /** The highest rating: a great one. Five stars, the scale every rating control reads in. */
    const val RATING_MAX = 5

    /** Checks both figures, reporting every violation at once, as [SetValidator] does. */
    fun validate(
        calories: Int?,
        rating: Int?,
    ): ValidationResult {
        val violations = buildList {
            calories?.takeIf { it !in CALORIES_MIN..CALORIES_MAX }?.let {
                add("calories must be within $CALORIES_MIN..$CALORIES_MAX kcal, was $it")
            }
            rating?.takeIf { it !in RATING_MIN..RATING_MAX }?.let {
                add("rating must be within $RATING_MIN..$RATING_MAX, was $it")
            }
        }
        return if (violations.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(
                violations.joinToString("; "),
            )
        }
    }
}
