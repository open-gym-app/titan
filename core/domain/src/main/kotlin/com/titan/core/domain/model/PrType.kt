package com.titan.core.domain.model

/**
 * The kinds of personal record the engine writes.
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
    BEST_PACE(higherIsBetter = false),
}
