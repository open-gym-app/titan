package com.titan.core.domain.model

/**
 * The kinds of personal record the engine writes.
 *
 * Four are a lift's — [MAX_WEIGHT], [MAX_REPS], [MAX_VOLUME] and [ESTIMATED_1RM] — and four are
 * a timed exercise's: [MAX_DISTANCE], [BEST_PACE] and [MAX_DURATION] for a run, a ride or a
 * swim, and [MAX_DURATION] and [MAX_WEIGHTED_HOLD] for a hold. Which set may earn which is
 * decided by the exercise's [ExerciseType], not by which figures a set happens to carry — see
 * [eligibleFor].
 *
 * @property higherIsBetter whether a bigger figure is a better record.
 *
 *   True for every kind but [BEST_PACE], which is a duration over a distance: finishing the same
 *   distance in less time is the improvement, so the comparison runs the other way. The rule
 *   lives here rather than at each place that compares two records, because there are now three
 *   of them — the engine deciding whether a candidate beats what is on file, the records list
 *   working out how much a record improved on the one before it, and anything that later ranks
 *   them — and a rule stated three times is a rule that eventually disagrees with itself.
 */
enum class PrType(val higherIsBetter: Boolean) {
    MAX_WEIGHT(higherIsBetter = true),
    MAX_REPS(higherIsBetter = true),
    MAX_VOLUME(higherIsBetter = true),
    ESTIMATED_1RM(higherIsBetter = true),
    MAX_DISTANCE(higherIsBetter = true),

    /**
     * The fastest set, stored once and *read* two ways: as a pace for a run or a swim and as a
     * speed for a ride — see [PaceStyle]. Speed is distance over time and pace is time over
     * distance, so the fastest set by one is always the fastest by the other, and a separate
     * speed kind would be broken by the same set every time it was.
     */
    BEST_PACE(higherIsBetter = false),

    /** The longest set by time: a plank held, or a run, ride or swim measured by the clock. */
    MAX_DURATION(higherIsBetter = true),

    /**
     * The most load held for the longest — added weight times seconds, for a weighted plank or a
     * loaded carry. Without it, putting a plate on a hold would be a regression on
     * [MAX_DURATION] and a record nowhere else.
     */
    MAX_WEIGHTED_HOLD(higherIsBetter = true),
    ;

    companion object {

        /**
         * The kinds a set of an exercise logged as [type] may earn.
         *
         * A gate on the exercise rather than on the set's figures, because `SetValidator` allows
         * a set to carry dimensions its mode does not ask for: a squat with a duration recorded
         * must not earn [MAX_DURATION], and a weighted hold must not earn [MAX_WEIGHT] — its
         * weight is load added to a body, not a lift.
         */
        fun eligibleFor(type: ExerciseType): Set<PrType> =
            when (type) {
                ExerciseType.STRENGTH -> setOf(MAX_WEIGHT, MAX_REPS, MAX_VOLUME, ESTIMATED_1RM)
                ExerciseType.ENDURANCE -> setOf(MAX_DISTANCE, BEST_PACE, MAX_DURATION)
                ExerciseType.ISOMETRIC -> setOf(MAX_DURATION, MAX_WEIGHTED_HOLD)
            }
    }
}
