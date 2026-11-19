package com.titan.core.domain.model

/**
 * One of the three things a pet's training shapes.
 *
 * One per [ExerciseType] — see [com.titan.core.domain.gamification.PetTraining.statFor], which
 * states the mapping once.
 */
enum class PetStat {

    /** Grown by weight-and-reps sets. */
    STRENGTH,

    /** Grown by distance-and-time sets. */
    ENDURANCE,

    /** Grown by timed holds. */
    MOBILITY,
}

/**
 * A pet's three stats, each a whole number in `0..`[MAX].
 *
 * These are the **base** stats: what training has built, before anything worn is counted.
 *
 * ### The bound is named so a reader can clamp
 *
 * A stat is restored from a backup like any other column, and a backup is a file somebody can
 * edit. [MAX] is what the restore boundary repairs to and what a reader degrades to (#256's
 * two-layer rule): the constructor refuses a value outside the range, and [clamped] is the way
 * in for a value that has not been checked yet.
 */
data class PetStats(
    val strength: Int,
    val endurance: Int,
    val mobility: Int,
) {

    init {
        require(strength in 0..MAX) { "strength must be in 0..$MAX, was $strength" }
        require(endurance in 0..MAX) { "endurance must be in 0..$MAX, was $endurance" }
        require(mobility in 0..MAX) { "mobility must be in 0..$MAX, was $mobility" }
    }

    /** The value of [stat]. */
    operator fun get(stat: PetStat): Int =
        when (stat) {
            PetStat.STRENGTH -> strength
            PetStat.ENDURANCE -> endurance
            PetStat.MOBILITY -> mobility
        }

    /**
     * These stats with [amount] added to [stat], held at [MAX].
     *
     * Saturating rather than throwing: growth is an increment applied at the end of a workout,
     * and a pet that has reached the ceiling has stopped growing, not broken the cascade.
     *
     * @throws IllegalArgumentException if [amount] is negative. Nothing takes a stat away.
     */
    fun plus(
        stat: PetStat,
        amount: Int,
    ): PetStats {
        require(amount >= 0) { "a stat only grows, was asked to add $amount" }
        val grown = (this[stat].toLong() + amount).coerceAtMost(MAX.toLong()).toInt()
        return when (stat) {
            PetStat.STRENGTH -> copy(strength = grown)
            PetStat.ENDURANCE -> copy(endurance = grown)
            PetStat.MOBILITY -> copy(mobility = grown)
        }
    }

    companion object {

        /**
         * The most any one stat can be.
         *
         * Far past what training reaches — a stat grows by one per paid set — and small enough
         * that sums and percentages of it stay comfortably inside an `Int`.
         */
        const val MAX = 99_999

        /** A newly hatched pet's stats: nothing yet. */
        val ZERO = PetStats(strength = 0, endurance = 0, mobility = 0)

        /**
         * Stats from values nobody has checked — a restored row, a hand-edited file — each pulled
         * into `0..`[MAX] rather than refused.
         */
        fun clamped(
            strength: Int,
            endurance: Int,
            mobility: Int,
        ): PetStats =
            PetStats(
                strength = strength.coerceIn(0, MAX),
                endurance = endurance.coerceIn(0, MAX),
                mobility = mobility.coerceIn(0, MAX),
            )
    }
}
