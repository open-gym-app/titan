package com.opengym.core.domain.model

/**
 * The rep counts a working set is meant to land in, both ends inclusive: `3–6` for strength,
 * `8–12` for hypertrophy.
 *
 * What the next-load engine (#M7-15) measures a session against. A set under [min] says the
 * weight was too heavy, a session with every set at or over [max] says it was too light, and
 * anything between says keep the weight and add a rep — see
 * [com.opengym.core.domain.load.NextLoadEngine], which is the only reader.
 *
 * Its own type rather than an [IntRange], because it is a stored setting: the CUSTOM goal's
 * range is typed by the user, round-trips through the backup, and has to refuse `10–6` at the
 * door rather than in whichever reader first divides by it. The bounds are
 * [com.opengym.core.domain.validation.SetValidator]'s own — a range no logged set could fall
 * in is not a target anybody can train towards.
 *
 * @property min the fewest reps a working set should get. At least one.
 * @property max the most it should get before the weight goes up. At least [min].
 */
data class RepRange(
    val min: Int,
    val max: Int,
) {
    init {
        require(min >= MIN_REPS) { "a rep range must start at $MIN_REPS or more, was $min" }
        require(max <= MAX_REPS) { "a rep range must end at $MAX_REPS or fewer, was $max" }
        require(max >= min) { "a rep range must not end before it starts, was $min–$max" }
    }

    /** Whether [reps] is inside the range, both ends inclusive. */
    operator fun contains(reps: Int): Boolean = reps in min..max

    companion object {

        /**
         * The lowest a range may start: one rep, the least a set can hold — matching
         * `SetValidator.REPS_MIN`, restated here because `:core:domain`'s model package does
         * not read its validation package.
         */
        const val MIN_REPS: Int = 1

        /**
         * The highest a range may end. Well under `SetValidator.REPS_MAX`'s ten thousand, which
         * bounds what a *logged* set may hold: a target of a hundred reps is already past what
         * any progression literature calls a working set, and a range that ends at ten
         * thousand is a typo the settings dialog should refuse.
         */
        const val MAX_REPS: Int = 100

        /** Heavy and few: `3–6`, the range [TrainingGoal.STRENGTH] targets. */
        val STRENGTH: RepRange = RepRange(min = 3, max = 6)

        /** `8–12`, the range [TrainingGoal.HYPERTROPHY] targets. */
        val HYPERTROPHY: RepRange = RepRange(min = 8, max = 12)

        /** Light and many: `15–20`, the range [TrainingGoal.ENDURANCE] targets. */
        val ENDURANCE: RepRange = RepRange(min = 15, max = 20)

        /**
         * What [TrainingGoal.CUSTOM] targets before the user has typed their own: the
         * hypertrophy range, the middle of the three and the one most programmes are written
         * around. A user who picked CUSTOM and never opened the dialog gets a sensible
         * suggestion rather than none.
         */
        val DEFAULT_CUSTOM: RepRange = HYPERTROPHY
    }
}
